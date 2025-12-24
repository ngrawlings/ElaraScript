package com.elara.script.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.elara.script.shaping.ElaraDataShaper;

/**
 * Registry of named shapes.
 *
 * This replaces the legacy embedded DataShape/FieldSpec/etc inside ElaraScript.
 * Shapes live outside the interpreter and can be shared across apps.
 */
public class DataShapingRegistry {
    private final Map<String, ElaraDataShaper.Shape<Value>> shapes = new ConcurrentHashMap<>();
    private final ElaraDataShaper<Value> shaper = new ElaraDataShaper<>(DataShaping.VALUE_ADAPTER);

    public void register(String name, ElaraDataShaper.Shape<Value> shape) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("shape name required");
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        shapes.put(name.trim(), shape);
    }

    public boolean has(String name) {
        if (name == null) return false;
        return shapes.containsKey(name.trim());
    }

    public ElaraDataShaper.Shape<Value> get(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("shape name required");
        ElaraDataShaper.Shape<Value> s = shapes.get(name.trim());
        if (s == null) throw new IllegalArgumentException("Unknown shape: " + name);
        return s;
    }

    public ElaraDataShaper<Value> shaper() { return shaper; }
    public Map<String, ElaraDataShaper.Shape<Value>> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(shapes)); }
}