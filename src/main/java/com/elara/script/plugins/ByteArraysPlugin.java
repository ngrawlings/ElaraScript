package com.elara.script.plugins;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.List;

public final class ByteArraysPlugin {

    private ByteArraysPlugin() {}

    public static void register(ElaraScript engine) {
    	
    	// bytes_alloc(len) -> BYTES (zero-filled)
        engine.registerFunction("bytes_alloc", args -> {
            int len = requireNonNegInt(args.get(0), "bytes_alloc.len");
            return Value.bytes(new byte[len]);
        });

        // bytes_alloc_fill(len, fillByte) -> BYTES
        engine.registerFunction("bytes_alloc_fill", args -> {
            int len = requireNonNegInt(args.get(0), "bytes_alloc_fill.len");
            int fill = requireByte(args.get(1), "bytes_alloc_fill.fillByte");
            byte[] out = new byte[len];
            if (fill != 0) {
                byte f = (byte) fill;
                for (int i = 0; i < len; i++) out[i] = f;
            }
            return Value.bytes(out);
        });

        engine.registerFunction("bytes_concat", args -> {
            byte[] a = requireBytes(args.get(0), "bytes_concat");
            byte[] b = requireBytes(args.get(1), "bytes_concat");
            return Value.bytes(concat2(a, b));
        });

        engine.registerFunction("bytes_concat_many", args -> {
            Value arr = args.get(0);
            if (arr.getType() != Value.Type.ARRAY) {
                throw new RuntimeException("bytes_concat_many: expected ARRAY of BYTES");
            }
            List<Value> items = arr.asArray();

            int total = 0;
            byte[][] parts = new byte[items.size()][];
            for (int i = 0; i < items.size(); i++) {
                Value v = items.get(i);
                if (v.getType() != Value.Type.BYTES) {
                    throw new RuntimeException("bytes_concat_many: element " + i + " is not BYTES");
                }
                byte[] p = v.asBytes();
                parts[i] = p;
                total += p.length;
            }

            byte[] out = new byte[total];
            int off = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, off, p.length);
                off += p.length;
            }
            return Value.bytes(out);
        });

        // bytes_slice(b, start, len) (clamped, never throws)
        engine.registerFunction("bytes_slice", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_slice");
            int start = requireInt(args.get(1), "bytes_slice.start");
            int len = requireInt(args.get(2), "bytes_slice.len");
            return Value.bytes(slice(b, start, len));
        });

        engine.registerFunction("bytes_take", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_take");
            int n = requireInt(args.get(1), "bytes_take.n");
            return Value.bytes(slice(b, 0, n));
        });

        engine.registerFunction("bytes_drop", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_drop");
            int n = requireInt(args.get(1), "bytes_drop.n");
            return Value.bytes(slice(b, n, Math.max(0, b.length - n)));
        });

        engine.registerFunction("bytes_repeat", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_repeat");
            int count = requireNonNegInt(args.get(1), "bytes_repeat.count");
            if (count == 0 || b.length == 0) return Value.bytes(new byte[0]);
            long totalL = (long) b.length * (long) count;
            if (totalL > Integer.MAX_VALUE) throw new RuntimeException("bytes_repeat: result too large");
            int total = (int) totalL;
            byte[] out = new byte[total];
            int off = 0;
            for (int i = 0; i < count; i++) {
                System.arraycopy(b, 0, out, off, b.length);
                off += b.length;
            }
            return Value.bytes(out);
        });

        engine.registerFunction("bytes_pad_right", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_pad_right");
            int totalLen = requireNonNegInt(args.get(1), "bytes_pad_right.totalLen");
            int fill = requireByte(args.get(2), "bytes_pad_right.fillByte");
            return Value.bytes(padRight(b, totalLen, (byte) fill));
        });

        engine.registerFunction("bytes_pad_left", args -> {
            byte[] b = requireBytes(args.get(0), "bytes_pad_left");
            int totalLen = requireNonNegInt(args.get(1), "bytes_pad_left.totalLen");
            int fill = requireByte(args.get(2), "bytes_pad_left.fillByte");
            return Value.bytes(padLeft(b, totalLen, (byte) fill));
        });
    }

    // ===================== CORE OPS =====================

    private static byte[] concat2(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // start can be negative (counts from end), len can be <=0 => empty
    private static byte[] slice(byte[] b, int start, int len) {
        if (len <= 0 || b.length == 0) return new byte[0];

        int s = start;
        if (s < 0) s = b.length + s; // -1 means last byte
        if (s < 0) s = 0;
        if (s > b.length) s = b.length;

        int e = s + len;
        if (e < s) e = s;
        if (e > b.length) e = b.length;

        int n = e - s;
        if (n <= 0) return new byte[0];

        byte[] out = new byte[n];
        System.arraycopy(b, s, out, 0, n);
        return out;
    }

    private static byte[] padRight(byte[] b, int totalLen, byte fill) {
        if (b.length >= totalLen) return b.clone();
        byte[] out = new byte[totalLen];
        System.arraycopy(b, 0, out, 0, b.length);
        for (int i = b.length; i < totalLen; i++) out[i] = fill;
        return out;
    }

    private static byte[] padLeft(byte[] b, int totalLen, byte fill) {
        if (b.length >= totalLen) return b.clone();
        byte[] out = new byte[totalLen];
        int pad = totalLen - b.length;
        for (int i = 0; i < pad; i++) out[i] = fill;
        System.arraycopy(b, 0, out, pad, b.length);
        return out;
    }

    // ===================== ARG HELPERS =====================

    private static byte[] requireBytes(Value v, String fn) {
        if (v.getType() != Value.Type.BYTES) throw new RuntimeException(fn + ": expected BYTES");
        return v.asBytes();
    }

    private static int requireInt(Value v, String label) {
        if (v.getType() != Value.Type.NUMBER) throw new RuntimeException(label + ": expected NUMBER");
        return (int) v.asNumber();
    }

    private static int requireNonNegInt(Value v, String label) {
        int n = requireInt(v, label);
        if (n < 0) throw new RuntimeException(label + ": must be >= 0");
        return n;
    }

    private static int requireByte(Value v, String label) {
        if (v.getType() != Value.Type.NUMBER) throw new RuntimeException(label + ": expected NUMBER 0..255");
        int n = (int) v.asNumber();
        if (n < 0 || n > 255) throw new RuntimeException(label + ": expected 0..255");
        return n;
    }
}
