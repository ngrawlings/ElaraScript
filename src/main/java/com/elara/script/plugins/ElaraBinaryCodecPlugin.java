package com.elara.script.plugins;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.DataShape;

import static com.elara.script.ElaraScript.Value;

/**
 * ElaraBinaryCodecPlugin
 *
 * Byte-aligned binary codec + bitfield support (u1..u7, i1..i7, bits:N).
 *
 * API (ElaraScript):
 *   - bin_encode(formatJson, envPairsArray) -> base64 string               (legacy)
 *   - bin_decode(formatJson, base64String) -> envPairsArray               (legacy)
 *
 * Native bytes API:
 *   - bin_encode_bytes(formatJson, envPairsArray) -> BYTES
 *   - bin_decode_bytes(formatJson, bytesValue) -> envPairsArray
 *
 * Format JSON:
 * {
 *   "endian": "LE" | "BE",          // default LE
 *   "bitOrder": "LSB0" | "MSB0",    // default LSB0 (bit packing order inside bytes)
 *   "fields": [
 *     {"name":"flags","type":"u3"},
 *     {"name":"mode","type":"u2"},
 *     {"name":"x","type":"u16"},
 *     {"name":"tag","type":"string"},
 *     {"name":"blob","type":"bytes:32"}
 *   ]
 * }
 */
public final class ElaraBinaryCodecPlugin {

    private ElaraBinaryCodecPlugin() {}

    // Optional: cache parsed formats by JSON string
    private static final Map<String, Format> FORMAT_CACHE = new ConcurrentHashMap<>();

    public static void register(ElaraScript engine, DataShape shapeOrNull) {

        // bin_encode(formatJson, envPairsArray) -> base64 string (legacy)
        engine.registerFunction("bin_encode", args -> {
            String fmtJson = args.get(0).asString();
            Value envPairs = args.get(1);

            Format fmt = parseFormatCached(fmtJson);
            Map<String, Object> env = pairsToMap(envPairs);

            byte[] bytes = encode(fmt, env);
            return Value.string(Base64.getEncoder().encodeToString(bytes));
        });

        // bin_decode(formatJson, base64String) -> envPairsArray (legacy)
        engine.registerFunction("bin_decode", args -> {
            String fmtJson = args.get(0).asString();
            String b64 = args.get(1).asString();

            Format fmt = parseFormatCached(fmtJson);
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("bin_decode: invalid base64");
            }

            Map<String, Object> env = decode(fmt, bytes);
            return Value.array(mapToPairs(env));
        });

        // bin_encode_bytes(formatJson, envPairsArray) -> BYTES (native)
        engine.registerFunction("bin_encode_bytes", args -> {
            String fmtJson = args.get(0).asString();
            Value envPairs = args.get(1);

            Format fmt = parseFormatCached(fmtJson);
            Map<String, Object> env = pairsToMap(envPairs);

            byte[] bytes = encode(fmt, env);
            return Value.bytes(bytes);
        });

        // bin_decode_bytes(formatJson, bytesValue) -> envPairsArray (native)
        engine.registerFunction("bin_decode_bytes", args -> {
            String fmtJson = args.get(0).asString();
            Value bytesV = args.get(1);

            Format fmt = parseFormatCached(fmtJson);

            byte[] bytes;
            if (bytesV.getType() == Value.Type.BYTES) {
                bytes = bytesV.asBytes();
            } else if (bytesV.getType() == Value.Type.STRING) {
                // allow base64 string too, for convenience
                try {
                    bytes = Base64.getDecoder().decode(bytesV.asString());
                } catch (IllegalArgumentException iae) {
                    throw new RuntimeException("bin_decode_bytes: expected BYTES (or base64 string)");
                }
            } else {
                throw new RuntimeException("bin_decode_bytes: expected BYTES");
            }

            Map<String, Object> env = decode(fmt, bytes);
            return Value.array(mapToPairs(env));
        });
    }

    // ===================== ENCODE/DECODE =====================

    private static byte[] encode(Format fmt, Map<String, Object> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        BitWriter bw = new BitWriter(out, fmt.bitOrder);

        for (Field f : fmt.fields) {
            Object raw = env.get(f.name);

            switch (f.kind) {
                case UBITS -> {
                    int u = toInt(raw, f.name, 0, (1 << f.bitLen) - 1);
                    bw.writeBits(u, f.bitLen);
                }
                case IBITS -> {
                    int min = -(1 << (f.bitLen - 1));
                    int max = (1 << (f.bitLen - 1)) - 1;
                    int s = toInt(raw, f.name, min, max);
                    int mask = (1 << f.bitLen) - 1;
                    int twos = s & mask;
                    bw.writeBits(twos, f.bitLen);
                }

                default -> {
                    // byte-aligned field, so align first
                    bw.alignToByte();

                    switch (f.kind) {
                        case U8 -> writeU8(out, toInt(raw, f.name, 0, 255));
                        case I8 -> writeI8(out, toInt(raw, f.name, -128, 127));
                        case U16 -> writeU16(out, fmt.order, toInt(raw, f.name, 0, 65535));
                        case I16 -> writeI16(out, fmt.order, toInt(raw, f.name, -32768, 32767));
                        case U32 -> writeU32(out, fmt.order, toLong(raw, f.name, 0L, 0xFFFF_FFFFL));
                        case I32 -> writeI32(out, fmt.order, toLong(raw, f.name, Integer.MIN_VALUE, Integer.MAX_VALUE));
                        case F32 -> writeF32(out, fmt.order, (float) toDouble(raw, f.name));
                        case F64 -> writeF64(out, fmt.order, toDouble(raw, f.name));
                        case BOOL -> writeU8(out, toBool(raw, f.name) ? 1 : 0);
                        case STRING -> writeString(out, fmt.order, toString(raw, f.name));
                        case BYTES_FIXED -> writeFixedBytes(out, f, raw);
                        default -> throw new RuntimeException("Unsupported field type: " + f.kind);
                    }
                }
            }
        }

        // flush any partial byte at end
        bw.alignToByte();
        return out.toByteArray();
    }

    private static Map<String, Object> decode(Format fmt, byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(fmt.order);
        BitReader br = new BitReader(bb, fmt.bitOrder);

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        for (Field f : fmt.fields) {
            switch (f.kind) {
                case UBITS -> {
                    int u = br.readBits(f.bitLen);
                    out.put(f.name, (double) u);
                }
                case IBITS -> {
                    int n = f.bitLen;
                    int x = br.readBits(n);
                    int signBit = 1 << (n - 1);
                    int s = (x ^ signBit) - signBit; // sign extend
                    out.put(f.name, (double) s);
                }

                default -> {
                    // byte-aligned read, so align first
                    br.alignToByte();

                    switch (f.kind) {
                        case U8 -> out.put(f.name, (double) readU8(bb));
                        case I8 -> out.put(f.name, (double) readI8(bb));
                        case U16 -> out.put(f.name, (double) readU16(bb));
                        case I16 -> out.put(f.name, (double) readI16(bb));
                        case U32 -> out.put(f.name, (double) readU32(bb));
                        case I32 -> out.put(f.name, (double) readI32(bb));
                        case F32 -> out.put(f.name, (double) readF32(bb));
                        case F64 -> out.put(f.name, readF64(bb));
                        case BOOL -> out.put(f.name, readU8(bb) != 0);
                        case STRING -> out.put(f.name, readString(bb));
                        case BYTES_FIXED -> out.put(f.name, readFixedBytes(bb, f.bytesLen)); // <-- native bytes
                        default -> throw new RuntimeException("Unsupported field type: " + f.kind);
                    }
                }
            }
        }

        return out;
    }

    // ===================== FORMAT PARSING =====================

    private static Format parseFormatCached(String json) {
        return FORMAT_CACHE.computeIfAbsent(json, ElaraBinaryCodecPlugin::parseFormat);
    }

    private static Format parseFormat(String json) {
        Object v = TinyJson.parse(json);
        if (!(v instanceof Map)) throw new RuntimeException("format must be a JSON object");

        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) v;

        String endian = asString(obj.get("endian"), "endian", "LE").toUpperCase(Locale.ROOT);
        ByteOrder order = "BE".equals(endian) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;

        String bo = asString(obj.get("bitOrder"), "bitOrder", "LSB0").toUpperCase(Locale.ROOT);
        BitOrder bitOrder = "MSB0".equals(bo) ? BitOrder.MSB0 : BitOrder.LSB0;

        Object fieldsV = obj.get("fields");
        if (!(fieldsV instanceof List)) throw new RuntimeException("format.fields must be an array");

        @SuppressWarnings("unchecked")
        List<Object> fieldsA = (List<Object>) fieldsV;

        ArrayList<Field> fields = new ArrayList<>(fieldsA.size());
        for (Object fo : fieldsA) {
            if (!(fo instanceof Map)) throw new RuntimeException("field entry must be object");
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) fo;

            String name = asString(fm.get("name"), "field.name", null);
            String type = asString(fm.get("type"), "field.type", null);

            fields.add(parseField(name, type));
        }

        return new Format(order, bitOrder, fields);
    }

    private static Field parseField(String name, String type) {
        type = type.toLowerCase(Locale.ROOT);

        return switch (type) {
            case "u8" -> Field.u8(name);
            case "i8" -> Field.i8(name);
            case "u16" -> Field.u16(name);
            case "i16" -> Field.i16(name);
            case "u32" -> Field.u32(name);
            case "i32" -> Field.i32(name);
            case "f32" -> Field.f32(name);
            case "f64" -> Field.f64(name);
            case "bool" -> Field.bool(name);
            case "string" -> Field.string(name);
            default -> {
                if (type.startsWith("bytes:")) {
                    int n;
                    try { n = Integer.parseInt(type.substring("bytes:".length())); }
                    catch (Exception e) { throw new RuntimeException("bytes:N must have integer N"); }
                    if (n < 0) throw new RuntimeException("bytes:N must be >= 0");
                    yield Field.bytesFixed(name, n);
                }

                // bits:N -> uN (1..7)
                if (type.startsWith("bits:")) {
                    int n = Integer.parseInt(type.substring("bits:".length()));
                    if (n < 1 || n > 7) throw new RuntimeException("bits:N only supports 1..7 for now");
                    yield Field.uBits(name, n);
                }

                // uN / iN for N=1..7
                if ((type.startsWith("u") || type.startsWith("i")) && type.length() > 1) {
                    int n = Integer.parseInt(type.substring(1));
                    if (n < 1 || n > 7) throw new RuntimeException(type + " only supports 1..7 for now");
                    yield (type.charAt(0) == 'u') ? Field.uBits(name, n) : Field.iBits(name, n);
                }

                throw new RuntimeException("Unknown field type: " + type);
            }
        };
    }

    private static String asString(Object v, String label, String def) {
        if (v == null) {
            if (def != null) return def;
            throw new RuntimeException("Missing " + label);
        }
        if (!(v instanceof String)) throw new RuntimeException(label + " must be a string");
        return (String) v;
    }

    // ===================== BIT PACKING =====================

    private static final class BitWriter {
        private final ByteArrayOutputStream out;
        private final BitOrder bitOrder;
        private int cur = 0;
        private int used = 0;

        BitWriter(ByteArrayOutputStream out, BitOrder bitOrder) {
            this.out = out;
            this.bitOrder = bitOrder;
        }

        void writeBits(int value, int nBits) {
            for (int i = 0; i < nBits; i++) {
                final int bit;
                if (bitOrder == BitOrder.LSB0) {
                    bit = (value >>> i) & 1;
                    cur |= (bit << used);
                } else {
                    bit = (value >>> (nBits - 1 - i)) & 1;
                    cur |= (bit << (7 - used));
                }

                used++;
                if (used == 8) flushByte();
            }
        }

        void alignToByte() {
            if (used != 0) flushByte();
        }

        private void flushByte() {
            out.write(cur & 0xFF);
            cur = 0;
            used = 0;
        }
    }

    private static final class BitReader {
        private final ByteBuffer bb;
        private final BitOrder bitOrder;
        private int cur = 0;
        private int used = 8;

        BitReader(ByteBuffer bb, BitOrder bitOrder) {
            this.bb = bb;
            this.bitOrder = bitOrder;
        }

        int readBits(int nBits) {
            int out = 0;
            for (int i = 0; i < nBits; i++) {
                int bit = read1();
                if (bitOrder == BitOrder.LSB0) {
                    out |= (bit << i);
                } else {
                    out = (out << 1) | bit;
                }
            }
            return out;
        }

        void alignToByte() {
            if (used != 8) used = 8;
        }

        private int read1() {
            if (used == 8) {
                if (!bb.hasRemaining()) throw new RuntimeException("bin_decode: truncated input (bitfield)");
                cur = bb.get() & 0xFF;
                used = 0;
            }

            final int bit;
            if (bitOrder == BitOrder.LSB0) bit = (cur >>> used) & 1;
            else bit = (cur >>> (7 - used)) & 1;

            used++;
            return bit;
        }
    }

    // ===================== ENV PAIRS <-> MAP =====================

    private static Map<String, Object> pairsToMap(Value pairs) {
        if (pairs.getType() != Value.Type.ARRAY) {
            throw new RuntimeException("envPairs must be an array of [key, value] pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        List<Value> list = pairs.asArray();
        for (Value pair : list) {
            List<Value> kv = pair.asArray();
            String k = kv.get(0).asString();
            Value vv = kv.get(1);
            m.put(k, vv == null || vv.getType() == Value.Type.NULL ? null : toPlainJava(vv));
        }
        return m;
    }

    private static List<Value> mapToPairs(Map<String, Object> env) {
        List<Value> out = new ArrayList<>(env.size());
        for (Map.Entry<String, Object> e : env.entrySet()) {
            out.add(Value.array(List.of(Value.string(e.getKey()), plainJavaToValue(e.getValue()))));
        }
        return out;
    }

    private static Object toPlainJava(Value v) {
        return switch (v.getType()) {
            case NULL -> null;
            case BOOL -> v.asBool();
            case NUMBER -> v.asNumber();
            case STRING -> v.asString();
            case BYTES -> v.asBytes(); // <-- native bytes
            case ARRAY -> {
                List<Object> a = new ArrayList<>();
                for (Value item : v.asArray()) a.add(toPlainJava(item));
                yield a;
            }
            case MATRIX -> {
                List<Object> rows = new ArrayList<>();
                for (List<Value> row : v.asMatrix()) {
                    List<Object> r = new ArrayList<>();
                    for (Value item : row) r.add(toPlainJava(item));
                    rows.add(r);
                }
                yield rows;
            }
            case MAP -> throw new IllegalStateException("BinaryCodecPlugin: MAP not supported");
        };
    }

    private static Value plainJavaToValue(Object v) {
        if (v == null) return Value.nil();
        if (v instanceof Boolean) return Value.bool((Boolean) v);
        if (v instanceof Number) return Value.number(((Number) v).doubleValue());
        if (v instanceof String) return Value.string((String) v);
        if (v instanceof byte[]) return Value.bytes((byte[]) v); // <-- native bytes
        if (v instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) v;
            byte[] out = new byte[bb.remaining()];
            bb.slice().get(out);
            return Value.bytes(out);
        }
        if (v instanceof List) {
            List<?> src = (List<?>) v;
            List<Value> out = new ArrayList<>(src.size());
            for (Object item : src) out.add(plainJavaToValue(item));
            return Value.array(out);
        }
        if (v instanceof Map) {
            throw new RuntimeException("Map/object values not supported in ElaraScript state yet");
        }
        throw new RuntimeException("Unsupported decoded type: " + v.getClass().getName());
    }

    // ===================== COERCIONS =====================

    private static int toInt(Object v, String name, int min, int max) {
        if (v == null) throw new RuntimeException("Missing field: " + name);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            long x = (long) d;
            if (x < min || x > max) throw new RuntimeException(name + " out of range");
            return (int) x;
        }
        throw new RuntimeException("Field " + name + " must be number");
    }

    private static long toLong(Object v, String name, long min, long max) {
        if (v == null) throw new RuntimeException("Missing field: " + name);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            long x = (long) d;
            if (x < min || x > max) throw new RuntimeException(name + " out of range");
            return x;
        }
        throw new RuntimeException("Field " + name + " must be number");
    }

    private static double toDouble(Object v, String name) {
        if (v == null) throw new RuntimeException("Missing field: " + name);
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw new RuntimeException("Field " + name + " must be number");
    }

    private static boolean toBool(Object v, String name) {
        if (v == null) throw new RuntimeException("Missing field: " + name);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0.0;
        throw new RuntimeException("Field " + name + " must be bool/number");
    }

    private static String toString(Object v, String name) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    private static byte[] coerceToBytes(Object v, String name) {
        if (v == null) return new byte[0];

        // Native bytes (from ElaraScript.Value.BYTES -> toPlainJava -> byte[])
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        if (v instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) v;
            byte[] out = new byte[bb.remaining()];
            bb.slice().get(out);
            return out;
        }

        // Legacy: base64 string
        if (v instanceof String) {
            try {
                return Base64.getDecoder().decode((String) v);
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("Field " + name + " bytes expects BYTES or base64 string");
            }
        }

        // Legacy: array of 0..255 numbers
        if (v instanceof List) {
            List<?> a = (List<?>) v;
            byte[] out = new byte[a.size()];
            for (int i = 0; i < a.size(); i++) {
                Object it = a.get(i);
                if (!(it instanceof Number)) throw new RuntimeException("Field " + name + " bytes array must be numbers");
                int b = (int) ((Number) it).doubleValue();
                if (b < 0 || b > 255) throw new RuntimeException("Field " + name + " byte out of range");
                out[i] = (byte) (b & 0xFF);
            }
            return out;
        }

        throw new RuntimeException("Field " + name + " bytes expects BYTES/base64/byte-array");
    }

    // ===================== WRITE PRIMITIVES =====================

    private static void writeU8(ByteArrayOutputStream out, int v) { out.write(v & 0xFF); }
    private static void writeI8(ByteArrayOutputStream out, int v) { out.write(v & 0xFF); }

    private static void writeU16(ByteArrayOutputStream out, ByteOrder o, int v) {
        ByteBuffer bb = ByteBuffer.allocate(2).order(o);
        bb.putShort((short) (v & 0xFFFF));
        out.writeBytes(bb.array());
    }

    private static void writeI16(ByteArrayOutputStream out, ByteOrder o, int v) {
        ByteBuffer bb = ByteBuffer.allocate(2).order(o);
        bb.putShort((short) v);
        out.writeBytes(bb.array());
    }

    private static void writeU32(ByteArrayOutputStream out, ByteOrder o, long v) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(o);
        bb.putInt((int) (v & 0xFFFF_FFFFL));
        out.writeBytes(bb.array());
    }

    private static void writeI32(ByteArrayOutputStream out, ByteOrder o, long v) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(o);
        bb.putInt((int) v);
        out.writeBytes(bb.array());
    }

    private static void writeF32(ByteArrayOutputStream out, ByteOrder o, float v) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(o);
        bb.putFloat(v);
        out.writeBytes(bb.array());
    }

    private static void writeF64(ByteArrayOutputStream out, ByteOrder o, double v) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(o);
        bb.putDouble(v);
        out.writeBytes(bb.array());
    }

    private static void writeString(ByteArrayOutputStream out, ByteOrder o, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeU32(out, o, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeFixedBytes(ByteArrayOutputStream out, Field f, Object raw) {
        byte[] b = coerceToBytes(raw, f.name);
        if (b.length != f.bytesLen) {
            throw new RuntimeException("Field " + f.name + " expects bytes:" + f.bytesLen + " but got " + b.length);
        }
        out.writeBytes(b);
    }

    // ===================== READ PRIMITIVES =====================

    private static int readU8(ByteBuffer bb) { need(bb, 1); return bb.get() & 0xFF; }
    private static int readI8(ByteBuffer bb) { need(bb, 1); return bb.get(); }

    private static int readU16(ByteBuffer bb) { need(bb, 2); return bb.getShort() & 0xFFFF; }
    private static int readI16(ByteBuffer bb) { need(bb, 2); return bb.getShort(); }

    private static long readU32(ByteBuffer bb) { need(bb, 4); return bb.getInt() & 0xFFFF_FFFFL; }
    private static int readI32(ByteBuffer bb) { need(bb, 4); return bb.getInt(); }

    private static float readF32(ByteBuffer bb) { need(bb, 4); return bb.getFloat(); }
    private static double readF64(ByteBuffer bb) { need(bb, 8); return bb.getDouble(); }

    private static String readString(ByteBuffer bb) {
        long n = readU32(bb);
        if (n < 0 || n > Integer.MAX_VALUE) throw new RuntimeException("Invalid string length: " + n);
        int len = (int) n;
        need(bb, len);
        byte[] b = new byte[len];
        bb.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readFixedBytes(ByteBuffer bb, int len) {
        need(bb, len);
        byte[] b = new byte[len];
        bb.get(b);
        return b;
    }

    private static void need(ByteBuffer bb, int n) {
        if (bb.remaining() < n) throw new RuntimeException("bin_decode: truncated input");
    }

    // ===================== FIELD + FORMAT =====================

    private enum Kind { U8, I8, U16, I16, U32, I32, F32, F64, BOOL, STRING, BYTES_FIXED, UBITS, IBITS }

    private enum BitOrder { LSB0, MSB0 }

    private static final class Field {
        final String name;
        final Kind kind;
        final int bytesLen;
        final int bitLen;

        Field(String name, Kind kind, int bytesLen, int bitLen) {
            this.name = name;
            this.kind = kind;
            this.bytesLen = bytesLen;
            this.bitLen = bitLen;
        }

        static Field u8(String n) { return new Field(n, Kind.U8, 0, 0); }
        static Field i8(String n) { return new Field(n, Kind.I8, 0, 0); }
        static Field u16(String n) { return new Field(n, Kind.U16, 0, 0); }
        static Field i16(String n) { return new Field(n, Kind.I16, 0, 0); }
        static Field u32(String n) { return new Field(n, Kind.U32, 0, 0); }
        static Field i32(String n) { return new Field(n, Kind.I32, 0, 0); }
        static Field f32(String n) { return new Field(n, Kind.F32, 0, 0); }
        static Field f64(String n) { return new Field(n, Kind.F64, 0, 0); }
        static Field bool(String n) { return new Field(n, Kind.BOOL, 0, 0); }
        static Field string(String n) { return new Field(n, Kind.STRING, 0, 0); }
        static Field bytesFixed(String n, int len) { return new Field(n, Kind.BYTES_FIXED, len, 0); }

        static Field uBits(String n, int bits) { return new Field(n, Kind.UBITS, 0, bits); }
        static Field iBits(String n, int bits) { return new Field(n, Kind.IBITS, 0, bits); }
    }

    private static final class Format {
        final ByteOrder order;
        final BitOrder bitOrder;
        final List<Field> fields;

        Format(ByteOrder order, BitOrder bitOrder, List<Field> fields) {
            this.order = order;
            this.bitOrder = bitOrder;
            this.fields = fields;
        }
    }

    // ===================== TINY JSON =====================

    /**
     * Minimal JSON parser (object/array/string/number/bool/null).
     * Numbers parse to Double. Objects are LinkedHashMap.
     */
    private static final class TinyJson {
        private TinyJson() {}

        static Object parse(String s) {
            if (s == null) throw new RuntimeException("JSON is null");
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWs();
            if (!p.eof()) throw p.err("Trailing data");
            return v;
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) { this.s = s; }

            boolean eof() { return i >= s.length(); }

            void skipWs() {
                while (!eof()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            RuntimeException err(String msg) {
                return new RuntimeException(msg + " at index " + i);
            }

            Object parseValue() {
                skipWs();
                if (eof()) throw err("Unexpected end");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't') return parseTrue();
                if (c == 'f') return parseFalse();
                if (c == 'n') return parseNull();
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw err("Unexpected char '" + c + "'");
            }

            Object parseObject() {
                expect('{');
                skipWs();
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                if (peek('}')) { i++; return m; }

                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    m.put(key, val);
                    skipWs();
                    if (peek('}')) { i++; break; }
                    expect(',');
                }
                return m;
            }

            Object parseArray() {
                expect('[');
                skipWs();
                ArrayList<Object> a = new ArrayList<>();
                if (peek(']')) { i++; return a; }

                while (true) {
                    Object v = parseValue();
                    a.add(v);
                    skipWs();
                    if (peek(']')) { i++; break; }
                    expect(',');
                }
                return a;
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (eof()) throw err("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': {
                                if (i + 4 > s.length()) throw err("Bad unicode escape");
                                String hex = s.substring(i, i + 4);
                                i += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                                break;
                            }
                            default: throw err("Bad escape char '" + e + "'");
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw err("Unterminated string");
            }

            Double parseNumber() {
                int start = i;
                if (peek('-')) i++;
                while (!eof() && Character.isDigit(s.charAt(i))) i++;
                if (!eof() && s.charAt(i) == '.') {
                    i++;
                    while (!eof() && Character.isDigit(s.charAt(i))) i++;
                }
                if (!eof()) {
                    char c = s.charAt(i);
                    if (c == 'e' || c == 'E') {
                        i++;
                        if (!eof() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                        while (!eof() && Character.isDigit(s.charAt(i))) i++;
                    }
                }
                return Double.parseDouble(s.substring(start, i));
            }

            Boolean parseTrue() { expect('t'); expect('r'); expect('u'); expect('e'); return Boolean.TRUE; }
            Boolean parseFalse() { expect('f'); expect('a'); expect('l'); expect('s'); expect('e'); return Boolean.FALSE; }
            Object parseNull() { expect('n'); expect('u'); expect('l'); expect('l'); return null; }

            boolean peek(char c) { return !eof() && s.charAt(i) == c; }

            void expect(char c) {
                if (eof() || s.charAt(i) != c) throw err("Expected '" + c + "'");
                i++;
            }
        }
    }
}
