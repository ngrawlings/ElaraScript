package com.elara.script.parser;

import java.util.LinkedHashMap;
import java.util.Map;

public class Environment {
    public final Map<String, Value> vars = new LinkedHashMap<>();
    public final Environment parent;

    public Environment() { this.parent = null; }
    public Environment(Environment parent) { this.parent = parent; }
    public Environment(Map<String, Value> initial) {
        this.parent = null;
        this.vars.putAll(initial);
    }

    public void define(String name, Value value) {
        if (vars.containsKey(name)) {
            throw new RuntimeException("Variable already defined: " + name);
        }
        vars.put(name, value);
    }

    public void assign(String name, Value value) {
        if (vars.containsKey(name)) {
            vars.put(name, value);
        } else if (parent != null) {
            parent.assign(name, value);
        } else {
            throw new RuntimeException("Undefined variable: " + name);
        }
    }

    public Value get(String name) {
        if (vars.containsKey(name)) {
            return vars.get(name);
        }
        if (parent != null) return parent.get(name);
        throw new RuntimeException("Undefined variable: " + name);
    }

    public boolean exists(String name) {
        if (vars.containsKey(name)) return true;
        return parent != null && parent.exists(name);
    }

    public Environment childScope() {
        return new Environment(this);
    }

    public Map<String, Value> snapshot() {
        Map<String, Value> out = (parent != null) ? parent.snapshot() : new LinkedHashMap<String, Value>();
        out.putAll(vars);
        return out;
    }
    
    public void remove(String name) {
        if (vars.containsKey(name)) {
            vars.remove(name);
            return;
        }
        if (parent != null) {
            parent.remove(name);
            return;
        }
        // if you prefer: ignore missing vs throw
    }
}