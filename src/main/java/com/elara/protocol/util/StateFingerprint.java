package com.elara.protocol.util;

import com.elara.script.ElaraScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class StateFingerprint {

    private StateFingerprint() {}

    /** Fingerprint for JSON-safe state: Map<String,Object> where Object is null/bool/num/string/List/Map */
    public static String fingerprintRawState(Map<String, Object> state) {
        if (state == null) state = Collections.emptyMap();

        List<String> keys = new ArrayList<>(state.keySet());
        Collections.sort(keys);

        StringBuilder concat = new StringBuilder();
        for (String key : keys) {
            Object v = state.get(key);
            String perKey = md5Hex(key + ":" + valueToken(v));
            concat.append(perKey);
        }
        return md5Hex(concat.toString());
    }

    /** Fingerprint for ElaraScript env: Map<String, ElaraScript.Value> */
    public static String fingerprintEnv(Map<String, ElaraScript.Value> env) {
        if (env == null) env = Collections.emptyMap();

        List<String> keys = new ArrayList<>(env.keySet());
        Collections.sort(keys);

        StringBuilder concat = new StringBuilder();
        for (String key : keys) {
            ElaraScript.Value v = env.get(key);
            String perKey = md5Hex(key + ":" + valueToken(v));
            concat.append(perKey);
        }
        return md5Hex(concat.toString());
    }

    // ---------------- internals ----------------

    private static String valueToken(Object v) {
        // primitives
        if (v == null) return "null";
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Number) return normalizeNumber((Number) v);
        if (v instanceof String) return (String) v;

        // composites -> md5(layer)
        if (v instanceof List) return md5Hex(listLayerToken((List<?>) v));
        if (v instanceof Map) return md5Hex(mapLayerToken((Map<?, ?>) v));

        // fallback (should not happen if state is JSON-safe)
        return String.valueOf(v);
    }

    private static String valueToken(ElaraScript.Value v) {
        if (v == null) return "null";

        switch (v.getType()) {
            case NULL:   return "null";
            case BOOL:   return v.asBool() ? "true" : "false";
            case NUMBER: return normalizeNumber(v.asNumber());
            case STRING: return v.asString();
            case ARRAY:  return md5Hex(listLayerTokenValue(v.asArray()));
            case MATRIX: return md5Hex(matrixLayerTokenValue(v.asMatrix()));
            default:     return "null";
        }
    }

    private static String listLayerToken(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String elemTok = valueToken(list.get(i));
            sb.append(md5Hex(i + ":" + elemTok));
        }
        return sb.toString();
    }

    private static String mapLayerToken(Map<?, ?> map) {
        // if you ever allow objects, this keeps it deterministic
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) keys.add(String.valueOf(k));
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Object val = map.get(k);
            String tok = valueToken(val);
            sb.append(md5Hex(k + ":" + tok));
        }
        return sb.toString();
    }

    private static String listLayerTokenValue(List<ElaraScript.Value> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String elemTok = valueToken(list.get(i));
            sb.append(md5Hex(i + ":" + elemTok));
        }
        return sb.toString();
    }

    private static String matrixLayerTokenValue(List<List<ElaraScript.Value>> rows) {
        // Treat matrix as list-of-lists
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String rowTok = md5Hex(listLayerTokenValue(rows.get(r)));
            sb.append(md5Hex(r + ":" + rowTok));
        }
        return sb.toString();
    }

    private static String normalizeNumber(Number n) {
        return normalizeNumber(n.doubleValue());
    }

    private static String normalizeNumber(double d) {
        // Keep it stable; Double.toString is fine cross-platform if you match Dart.
        // If you prefer removing trailing ".0", do it on both sides.
        return Double.toString(d);
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(dig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }
}