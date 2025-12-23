package com.elara.protocol.util;

import java.util.*;

public final class JsonDeepCopy {

    private JsonDeepCopy() {}

    /** Deep copy for JSON-safe values: null/bool/num/string/List/Map<String,?> */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v == null) return null;

        // primitives (immutable enough for our purposes)
        if (v instanceof String) return v;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) return v;

        // list
        if (v instanceof List<?>) {
            List<?> src = (List<?>) v;
            ArrayList<Object> out = new ArrayList<>(src.size());
            for (Object e : src) out.add(deepCopy(e));
            return out;
        }

        // map (string keys only)
        if (v instanceof Map<?, ?>) {
            Map<?, ?> src = (Map<?, ?>) v;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>(src.size());
            for (Map.Entry<?, ?> e : src.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) {
                    throw new IllegalArgumentException(
                            "JsonDeepCopy: map key must be String, got: " +
                                    (k == null ? "null" : k.getClass().getName())
                    );
                }
                out.put((String) k, deepCopy(e.getValue()));
            }
            return out;
        }

        throw new IllegalArgumentException("JsonDeepCopy: unsupported type " + v.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        if (src == null) return null;
        return (Map<String, Object>) deepCopy(src);
    }
}
