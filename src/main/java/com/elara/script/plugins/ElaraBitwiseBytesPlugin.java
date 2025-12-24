package com.elara.script.plugins;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

public final class ElaraBitwiseBytesPlugin {

    private ElaraBitwiseBytesPlugin() {}

    public static void register(ElaraScript engine) {

        engine.registerFunction("bw_and", args -> {
            byte[] a = requireBytes(args.get(0), "bw_and");
            byte[] b = requireBytes(args.get(1), "bw_and");
            requireSameLen(a, b, "bw_and");
            byte[] out = new byte[a.length];
            for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] & b[i]);
            return Value.bytes(out);
        });

        engine.registerFunction("bw_or", args -> {
            byte[] a = requireBytes(args.get(0), "bw_or");
            byte[] b = requireBytes(args.get(1), "bw_or");
            requireSameLen(a, b, "bw_or");
            byte[] out = new byte[a.length];
            for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] | b[i]);
            return Value.bytes(out);
        });

        engine.registerFunction("bw_xor", args -> {
            byte[] a = requireBytes(args.get(0), "bw_xor");
            byte[] b = requireBytes(args.get(1), "bw_xor");
            requireSameLen(a, b, "bw_xor");
            byte[] out = new byte[a.length];
            for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] ^ b[i]);
            return Value.bytes(out);
        });

        engine.registerFunction("bw_not", args -> {
            byte[] a = requireBytes(args.get(0), "bw_not");
            byte[] out = new byte[a.length];
            for (int i = 0; i < out.length; i++) out[i] = (byte) ~a[i];
            return Value.bytes(out);
        });

        engine.registerFunction("bw_shl", args -> {
            byte[] a = requireBytes(args.get(0), "bw_shl");
            int nBits = requireNonNegInt(args.get(1), "bw_shl");
            String endian = (args.size() >= 3) ? args.get(2).asString() : "BE";
            return Value.bytes(shiftLeft(a, nBits, endian));
        });

        engine.registerFunction("bw_shr", args -> {
            byte[] a = requireBytes(args.get(0), "bw_shr");
            int nBits = requireNonNegInt(args.get(1), "bw_shr");
            String endian = (args.size() >= 3) ? args.get(2).asString() : "BE";
            return Value.bytes(shiftRightLogical(a, nBits, endian));
        });

        engine.registerFunction("bw_rol", args -> {
            byte[] a = requireBytes(args.get(0), "bw_rol");
            int nBits = requireNonNegInt(args.get(1), "bw_rol");
            String endian = (args.size() >= 3) ? args.get(2).asString() : "BE";
            return Value.bytes(rotateLeft(a, nBits, endian));
        });

        engine.registerFunction("bw_ror", args -> {
            byte[] a = requireBytes(args.get(0), "bw_ror");
            int nBits = requireNonNegInt(args.get(1), "bw_ror");
            String endian = (args.size() >= 3) ? args.get(2).asString() : "BE";
            return Value.bytes(rotateRight(a, nBits, endian));
        });
    }

    // ===================== PUBLIC OPS =====================

    private static byte[] shiftLeft(byte[] a, int nBits, String endian) {
        if (a.length == 0) return new byte[0];
        if (nBits == 0) return a.clone();
        if (isLE(endian)) {
            byte[] rev = reverseCopy(a);
            byte[] out = shiftLeftBE(rev, nBits);
            return reverseCopy(out);
        }
        return shiftLeftBE(a, nBits);
    }

    private static byte[] shiftRightLogical(byte[] a, int nBits, String endian) {
        if (a.length == 0) return new byte[0];
        if (nBits == 0) return a.clone();
        if (isLE(endian)) {
            byte[] rev = reverseCopy(a);
            byte[] out = shiftRightLogicalBE(rev, nBits);
            return reverseCopy(out);
        }
        return shiftRightLogicalBE(a, nBits);
    }

    private static byte[] rotateLeft(byte[] a, int nBits, String endian) {
        if (a.length == 0) return new byte[0];
        int total = a.length * 8;
        int k = mod(nBits, total);
        if (k == 0) return a.clone();

        byte[] left = shiftLeft(a, k, endian);
        byte[] right = shiftRightLogical(a, total - k, endian);
        return orBytesSameLen(left, right);
    }

    private static byte[] rotateRight(byte[] a, int nBits, String endian) {
        if (a.length == 0) return new byte[0];
        int total = a.length * 8;
        int k = mod(nBits, total);
        if (k == 0) return a.clone();

        byte[] right = shiftRightLogical(a, k, endian);
        byte[] left = shiftLeft(a, total - k, endian);
        return orBytesSameLen(left, right);
    }

    // ===================== BIG-ENDIAN SHIFT CORE =====================

    // Treat byte[0] as MSB, byte[len-1] as LSB.
    private static byte[] shiftLeftBE(byte[] a, int nBits) {
        int len = a.length;
        int total = len * 8;
        if (nBits >= total) return new byte[len];

        int byteShift = nBits / 8;
        int bitShift = nBits % 8;

        byte[] out = new byte[len];

        for (int i = 0; i < len; i++) {
            int src = i + byteShift;
            if (src >= len) {
                out[i] = 0;
                continue;
            }

            int v = (a[src] & 0xFF) << bitShift;
            if (bitShift != 0 && (src + 1) < len) {
                v |= (a[src + 1] & 0xFF) >>> (8 - bitShift);
            }

            out[i] = (byte) (v & 0xFF);
        }
        return out;
    }

    private static byte[] shiftRightLogicalBE(byte[] a, int nBits) {
        int len = a.length;
        int total = len * 8;
        if (nBits >= total) return new byte[len];

        int byteShift = nBits / 8;
        int bitShift = nBits % 8;

        byte[] out = new byte[len];

        for (int i = 0; i < len; i++) {
            int src = i - byteShift;
            if (src < 0) {
                out[i] = 0;
                continue;
            }

            int v = (a[src] & 0xFF) >>> bitShift;
            if (bitShift != 0 && (src - 1) >= 0) {
                v |= (a[src - 1] & 0xFF) << (8 - bitShift);
            }

            out[i] = (byte) (v & 0xFF);
        }
        return out;
    }

    // ===================== HELPERS =====================

    private static byte[] requireBytes(Value v, String fn) {
        if (v.getType() != Value.Type.BYTES) {
            throw new RuntimeException(fn + ": expected BYTES");
        }
        return v.asBytes();
    }

    private static int requireNonNegInt(Value v, String fn) {
        if (v.getType() != Value.Type.NUMBER) {
            throw new RuntimeException(fn + ": expected NUMBER for bit count");
        }
        int n = (int) v.asNumber();
        if (n < 0) throw new RuntimeException(fn + ": bit count must be >= 0");
        return n;
    }

    private static void requireSameLen(byte[] a, byte[] b, String fn) {
        if (a.length != b.length) {
            throw new RuntimeException(fn + ": byte arrays must have same length");
        }
    }

    private static boolean isLE(String endian) {
        if (endian == null) return false;
        String e = endian.trim().toUpperCase(java.util.Locale.ROOT);
        return "LE".equals(e) || "LITTLE".equals(e) || "LITTLE_ENDIAN".equals(e);
    }

    private static byte[] reverseCopy(byte[] a) {
        byte[] out = new byte[a.length];
        for (int i = 0, j = a.length - 1; i < a.length; i++, j--) {
            out[i] = a[j];
        }
        return out;
    }

    private static int mod(int x, int m) {
        int r = x % m;
        return (r < 0) ? (r + m) : r;
    }

    private static byte[] orBytesSameLen(byte[] a, byte[] b) {
        if (a.length != b.length) throw new RuntimeException("internal: length mismatch");
        byte[] out = new byte[a.length];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] | b[i]);
        return out;
    }
}
