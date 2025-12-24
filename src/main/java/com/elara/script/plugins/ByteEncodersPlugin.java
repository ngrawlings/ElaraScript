package com.elara.script.plugins;

import java.util.Base64;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;


public final class ByteEncodersPlugin {

    private ByteEncodersPlugin() {}

    public static void register(ElaraScript engine) {

        // hex_encode(bytes) -> string
        engine.registerFunction("hex_encode", args -> {
            Value v = args.get(0);
            byte[] b = requireBytes(v, "hex_encode");
            return Value.string(toHex(b));
        });

        // hex_decode(string) -> bytes
        engine.registerFunction("hex_decode", args -> {
            String s = args.get(0).asString();
            return Value.bytes(fromHex(s));
        });

        // b64_encode(bytes) -> string
        engine.registerFunction("b64_encode", args -> {
            Value v = args.get(0);
            byte[] b = requireBytes(v, "b64_encode");
            return Value.string(Base64.getEncoder().encodeToString(b));
        });

        // b64_decode(string) -> bytes
        engine.registerFunction("b64_decode", args -> {
            String s = args.get(0).asString();
            try {
                return Value.bytes(Base64.getDecoder().decode(s));
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("b64_decode: invalid base64");
            }
        });

        // urlsafe variants (handy for tokens)
        engine.registerFunction("b64url_encode", args -> {
            byte[] b = requireBytes(args.get(0), "b64url_encode");
            return Value.string(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        });

        engine.registerFunction("b64url_decode", args -> {
            String s = args.get(0).asString();
            try {
                return Value.bytes(Base64.getUrlDecoder().decode(s));
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("b64url_decode: invalid base64url");
            }
        });
    }

    private static byte[] requireBytes(Value v, String fn) {
        if (v.getType() != Value.Type.BYTES) {
            throw new RuntimeException(fn + ": expected BYTES");
        }
        return v.asBytes();
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] HEX = "0123456789abcdef".toCharArray();
        int j = 0;
        for (byte bb : bytes) {
            int v = bb & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] fromHex(String s) {
        if (s == null) return new byte[0];
        s = s.trim();
        if ((s.length() & 1) != 0) throw new RuntimeException("hex_decode: hex string must have even length");
        int n = s.length() / 2;
        byte[] out = new byte[n];

        for (int i = 0; i < n; i++) {
            int hi = hexNibble(s.charAt(i * 2));
            int lo = hexNibble(s.charAt(i * 2 + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        throw new RuntimeException("hex_decode: invalid hex char: " + c);
    }
}
