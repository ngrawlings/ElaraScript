package com.elara.script.plugins;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class ByteHashPlugin {

    private ByteHashPlugin() {}

    public static void register(ElaraScript engine) {

        // hash_hex("sha256", data) -> hex string
        engine.registerFunction("hash_hex", args -> {
            String alg = args.get(0).asString();
            byte[] data = coerceToBytesOrUtf8(args.get(1), "hash_hex.data");
            byte[] dig = digest(alg, data);
            return Value.string(toHex(dig));
        });

        // hash_bytes("sha256", data) -> BYTES
        engine.registerFunction("hash_bytes", args -> {
            String alg = args.get(0).asString();
            byte[] data = coerceToBytesOrUtf8(args.get(1), "hash_bytes.data");
            return Value.bytes(digest(alg, data));
        });

        // Convenience wrappers (hex)
        engine.registerFunction("md5_hex", args -> Value.string(toHex(digest("md5", coerceToBytesOrUtf8(args.get(0), "md5_hex.data")))));
        engine.registerFunction("sha1_hex", args -> Value.string(toHex(digest("sha1", coerceToBytesOrUtf8(args.get(0), "sha1_hex.data")))));
        engine.registerFunction("sha256_hex", args -> Value.string(toHex(digest("sha256", coerceToBytesOrUtf8(args.get(0), "sha256_hex.data")))));
        engine.registerFunction("sha512_hex", args -> Value.string(toHex(digest("sha512", coerceToBytesOrUtf8(args.get(0), "sha512_hex.data")))));

        // Convenience wrappers (bytes)
        engine.registerFunction("md5_bytes", args -> Value.bytes(digest("md5", coerceToBytesOrUtf8(args.get(0), "md5_bytes.data"))));
        engine.registerFunction("sha1_bytes", args -> Value.bytes(digest("sha1", coerceToBytesOrUtf8(args.get(0), "sha1_bytes.data"))));
        engine.registerFunction("sha256_bytes", args -> Value.bytes(digest("sha256", coerceToBytesOrUtf8(args.get(0), "sha256_bytes.data"))));
        engine.registerFunction("sha512_bytes", args -> Value.bytes(digest("sha512", coerceToBytesOrUtf8(args.get(0), "sha512_bytes.data"))));
    }

    // ----------------- internals -----------------

    private static byte[] coerceToBytesOrUtf8(Value v, String label) {
    	switch (v.getType()) {
    	  case BYTES:
    	    return v.asBytes();
    	  default:
    	    throw new RuntimeException("Expected bytes");
    	}
    }

    private static byte[] digest(String alg, byte[] data) {
        String jca = normalizeAlg(alg);
        try {
            MessageDigest md = MessageDigest.getInstance(jca);
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("hash: unsupported algorithm: " + alg);
        }
    }

    private static String normalizeAlg(String alg) {
        if (alg == null) throw new RuntimeException("hash: algorithm is null");
        String a = alg.trim().toUpperCase(Locale.ROOT).replace("_", "-");

        // allow both forms: SHA256 / SHA-256, etc.
        if (a.equals("MD5")) return "MD5";
        if (a.equals("SHA1") || a.equals("SHA-1")) return "SHA-1";
        if (a.equals("SHA256") || a.equals("SHA-256")) return "SHA-256";
        if (a.equals("SHA512") || a.equals("SHA-512")) return "SHA-512";

        // Let JCA try if user passes a valid name.
        return a;
    }

    private static String toHex(byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        int j = 0;
        for (byte bb : bytes) {
            int v = bb & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
