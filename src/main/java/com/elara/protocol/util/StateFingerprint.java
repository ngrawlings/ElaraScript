package com.elara.protocol.util;

import com.elara.script.ElaraScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class StateFingerprint {

    private StateFingerprint() {}

    // ---------- public API (no trace) ----------

    public static String fingerprintRawState(Map<String, Object> state) {
        return fingerprintRawState(state, null);
    }

    public static String fingerprintEnv(Map<String, ElaraScript.Value> env) {
        return fingerprintEnv(env, null);
    }

    // ---------- public API (with trace) ----------

    public static String fingerprintRawState(Map<String, Object> state, FingerprintTrace trace) {
        if (state == null) state = Collections.emptyMap();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            feedMapRaw(md, state, trace);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String fingerprintEnv(Map<String, ElaraScript.Value> env, FingerprintTrace trace) {
        if (env == null) env = Collections.emptyMap();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            feedMapEnv(md, env, trace);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- normalized traversal (RAW) ----------------

    private static void feedMapRaw(MessageDigest md, Map<String, Object> map, FingerprintTrace trace) {
        put(md, trace, "{");

        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.naturalOrder());

        for (String k : keys) {
            put(md, trace, "k:");
            put(md, trace, k);
            put(md, trace, "=");
            feedValueRaw(md, map.get(k), trace);
            put(md, trace, ";");
        }

        put(md, trace, "}");
    }

    private static void feedListRaw(MessageDigest md, List<?> list, FingerprintTrace trace) {
        put(md, trace, "[");

        for (int i = 0; i < list.size(); i++) {
            put(md, trace, "i:");
            put(md, trace, Integer.toString(i));
            put(md, trace, "=");
            feedValueRaw(md, list.get(i), trace);
            put(md, trace, ";");
        }

        put(md, trace, "]");
    }

    @SuppressWarnings("unchecked")
    private static void feedValueRaw(MessageDigest md, Object v, FingerprintTrace trace) {
        if (v == null) { put(md, trace, "N"); return; }

        if (v instanceof Boolean) { put(md, trace, ((Boolean) v) ? "T" : "F"); return; }

        if (v instanceof Number) {
            put(md, trace, "D:");
            put(md, trace, normalizeNumber((Number) v));
            return;
        }

        if (v instanceof String) {
            put(md, trace, "S:");
            put(md, trace, (String) v);
            return;
        }

        if (v instanceof List) {
            put(md, trace, "L:");
            feedListRaw(md, (List<?>) v, trace);
            return;
        }

        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) enforceStringKeys((Map<?, ?>) v);
            put(md, trace, "M:");
            feedMapRaw(md, m, trace);
            return;
        }

        put(md, trace, "X:");
        put(md, trace, String.valueOf(v));
    }

    private static Map<String, Object> enforceStringKeys(Map<?, ?> map) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey();
            if (!(k instanceof String)) {
                throw new IllegalArgumentException("StateFingerprint: map key must be String, got: " +
                        (k == null ? "null" : k.getClass().getName()));
            }
            out.put((String) k, e.getValue());
        }
        return out;
    }

    // ---------------- normalized traversal (ENV) ----------------

    private static void feedMapEnv(MessageDigest md, Map<String, ElaraScript.Value> map, FingerprintTrace trace) {
        put(md, trace, "{");

        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.naturalOrder());

        for (String k : keys) {
            put(md, trace, "k:");
            put(md, trace, k);
            put(md, trace, "=");
            feedValueEnv(md, map.get(k), trace);
            put(md, trace, ";");
        }

        put(md, trace, "}");
    }

    private static void feedValueEnv(MessageDigest md, ElaraScript.Value v, FingerprintTrace trace) {
        if (v == null) { put(md, trace, "N"); return; }

        switch (v.getType()) {
            case NULL:   put(md, trace, "N"); return;
            case BOOL:   put(md, trace, v.asBool() ? "T" : "F"); return;
            case NUMBER: put(md, trace, "D:"); put(md, trace, normalizeNumber(v.asNumber())); return;
            case STRING: put(md, trace, "S:"); put(md, trace, v.asString()); return;
            case ARRAY:  put(md, trace, "L:"); feedEnvList(md, v.asArray(), trace); return;
            case MATRIX: put(md, trace, "R:"); feedEnvMatrix(md, v.asMatrix(), trace); return;
            default:     put(md, trace, "N");
        }
    }

    private static void feedEnvList(MessageDigest md, List<ElaraScript.Value> list, FingerprintTrace trace) {
        put(md, trace, "[");

        for (int i = 0; i < list.size(); i++) {
            put(md, trace, "i:");
            put(md, trace, Integer.toString(i));
            put(md, trace, "=");
            feedValueEnv(md, list.get(i), trace);
            put(md, trace, ";");
        }

        put(md, trace, "]");
    }

    private static void feedEnvMatrix(MessageDigest md, List<List<ElaraScript.Value>> rows, FingerprintTrace trace) {
        put(md, trace, "[");

        for (int r = 0; r < rows.size(); r++) {
            put(md, trace, "r:");
            put(md, trace, Integer.toString(r));
            put(md, trace, "=");
            feedEnvList(md, rows.get(r), trace);
            put(md, trace, ";");
        }

        put(md, trace, "]");
    }

    // ---------------- helpers ----------------

    private static void put(MessageDigest md, FingerprintTrace trace, String token) {
        if (trace != null) trace.step(token);
        md.update(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeNumber(Number n) {
        return Double.toString(n.doubleValue());
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
