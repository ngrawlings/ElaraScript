package com.elara.script.shaping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple in-memory registry for named DataShapes.
 *
 * This is intentionally minimal (first draft):
 * - deterministic iteration order (LinkedHashMap)
 * - no IO; load shapes from wherever you want (files, code, network)
 *
 * Shape names are case-sensitive.
 */
public final class ElaraDataShapeRegistry<V> {

    private final Map<String, ElaraDataShaper.Shape<V>> shapes = new LinkedHashMap<>();

    /** Register (or replace) a shape by name. */
    public synchronized void register(String name, ElaraDataShaper.Shape<V> shape) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shape, "shape");
        String n = name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        shapes.put(n, shape);
    }

    /** Get a shape by name (or null). */
    public synchronized ElaraDataShaper.Shape<V> get(String name) {
        if (name == null) return null;
        return shapes.get(name.trim());
    }

    /** Whether a shape exists. */
    public synchronized boolean has(String name) {
        return get(name) != null;
    }

    /** Snapshot of the registry (read-only). */
    public synchronized Map<String, ElaraDataShaper.Shape<V>> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(shapes));
    }

    /** Clear everything (useful for tests). */
    public synchronized void clear() {
        shapes.clear();
    }
}
