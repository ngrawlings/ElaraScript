package com.elara.protocol.util;

import java.util.*;

public final class StateDiff {

    private StateDiff() {}

    /** Result container */
    public static final class DiffResult {
        public final List<List<Object>> set;
        public final List<String> remove;

        public DiffResult(List<List<Object>> set, List<String> remove) {
            this.set = set;
            this.remove = remove;
        }

        /** Shape exactly matches transport protocol */
        public Map<String, Object> toPatchObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("set", set);
            m.put("remove", remove);
            return m;
        }
    }

    /**
     * Compute diff between two JSON-safe state maps.
     *
     * Rules:
     * - Keys starting with "__" are ignored completely
     * - Missing OR null in `after` => removal
     * - Non-null changes => set
     */
    public static DiffResult diff(
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        if (before == null) before = Collections.emptyMap();
        if (after == null) after = Collections.emptyMap();

        TreeSet<String> keys = new TreeSet<>();

        for (String k : before.keySet()) {
            if (!isEphemeral(k)) keys.add(k);
        }
        for (String k : after.keySet()) {
            if (!isEphemeral(k)) keys.add(k);
        }

        List<List<Object>> set = new ArrayList<>();
        List<String> remove = new ArrayList<>();

        for (String k : keys) {
            boolean had = before.containsKey(k);
            boolean has = after.containsKey(k);

            Object av = has ? after.get(k) : null;

            // Missing OR null => removal
            if (had && (!has || av == null)) {
                remove.add(k);
                continue;
            }

            // Non-null set/update
            if (has && av != null) {
                Object bv = before.get(k);
                if (!deepEqualsJsonSafe(bv, av)) {
                    set.add(List.of(k, deepCopyJsonSafe(av)));
                }
            }
        }

        return new DiffResult(set, remove);
    }

    // ---------------- internals ----------------

    private static boolean isEphemeral(String key) {
        return key.startsWith("__");
    }

    private static boolean deepEqualsJsonSafe(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a instanceof Number && b instanceof Number) {
            return Double.compare(
                    ((Number) a).doubleValue(),
                    ((Number) b).doubleValue()
            ) == 0;
        }

        if (a instanceof String || a instanceof Boolean) {
            return a.equals(b);
        }

        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!deepEqualsJsonSafe(la.get(i), lb.get(i))) return false;
            }
            return true;
        }

        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) return false;
            for (Object k : ma.keySet()) {
                if (!mb.containsKey(k)) return false;
                if (!deepEqualsJsonSafe(ma.get(k), mb.get(k))) return false;
            }
            return true;
        }

        return a.equals(b);
    }

    /** Ensure values sent in `set` are JSON-safe and non-null */
    private static Object deepCopyJsonSafe(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("patch set value must not be null");
        }

        if (v instanceof Boolean) return v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return v;

        if (v instanceof List) {
            List<?> src = (List<?>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object item : src) {
                out.add(item == null ? null : deepCopyJsonSafe(item));
            }
            return out;
        }

        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> src = (Map<Object, Object>) v;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : src.entrySet()) {
                out.put(
                        String.valueOf(e.getKey()),
                        e.getValue() == null ? null : deepCopyJsonSafe(e.getValue())
                );
            }
            return out;
        }

        return String.valueOf(v);
    }
}