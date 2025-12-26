package com.elara.script.parser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Value {
    public enum Type { NUMBER, BOOL, STRING, FUNC, BYTES, ARRAY, MATRIX, MAP, CLASS, CLASS_INSTANCE, NULL }

    public final Type type;
    public final Object value;

    public Value(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static Value func(String s) { return new Value(Type.FUNC, s); }
    public static Value number(double d) { return new Value(Type.NUMBER, d); }
    public static Value bool(boolean b) { return new Value(Type.BOOL, b); }
    public static Value string(String s) { return new Value(Type.STRING, s); }
    public static Value bytes(byte[] b) { return new Value(Type.BYTES, b); }
    public static Value array(List<Value> a) { return new Value(Type.ARRAY, a); }
    public static Value matrix(List<List<Value>> m) { return new Value(Type.MATRIX, m); }
    public static Value map(Map<String, Value> m) { return new Value(Type.MAP, m); }
    public static Value clazz(ClassDescriptor c) { return new Value(Type.CLASS, c); }

    /** Stateless class descriptor (no instance state yet). */
    public static final class ClassDescriptor {
        public final String name;
        public final LinkedHashMap<String, Object> methods; // value: Interpreter.UserFunction later
        public final LinkedHashMap<String, Object> vars;
        
        public ClassDescriptor(String name, LinkedHashMap<String, Object> methods, LinkedHashMap<String, Object> vars) {
            this.name = name;
            this.methods = (methods == null) ? new LinkedHashMap<>() : methods;
            this.vars = (vars == null) ? new LinkedHashMap<>() : vars;
        }
    }
    
    public static final class ClassInstance {
        public final String className;
        public final String uuid;
        
        // This is the master reference, environments may hold references for a time, originating from here
        public final Map<String, Value> _this;

        public ClassInstance(String className, String uuid) {
            this.className = className;
            this.uuid = uuid;
            this._this = new LinkedHashMap<>();
        }

        public String stateKey() {
            return className + "." + uuid;
        }
    }

    public static Value nil() { return new Value(Type.NULL, null); }

    public Type getType() { return type; }
    
    public String asFunc() {
        if (type != Type.FUNC) throw new RuntimeException("Expected function, got " + type);
        return (String) value;
    }

    public double asNumber() {
        if (type != Type.NUMBER) throw new RuntimeException("Expected number, got " + type);
        return (double) value;
    }

    public boolean asBool() {
        if (type != Type.BOOL) throw new RuntimeException("Expected bool, got " + type);
        return (boolean) value;
    }

    public String asString() {
        if (type != Type.STRING && type != Type.FUNC) {
            throw new RuntimeException("Expected string, got " + type);
        }
        return (String) value;
    }

    public byte[] asBytes() {
        if (type != Type.BYTES) throw new RuntimeException("Expected bytes, got " + type);
        return (byte[]) value; // NO defensive copy
    }

    @SuppressWarnings("unchecked")
    public List<Value> asArray() {
        if (type != Type.ARRAY) throw new RuntimeException("Expected array, got " + type);
        return (List<Value>) value;
    }

    @SuppressWarnings("unchecked")
    public List<List<Value>> asMatrix() {
        if (type != Type.MATRIX) throw new RuntimeException("Expected matrix, got " + type);
        return (List<List<Value>>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Value> asMap() {
        if (type != Type.MAP) throw new RuntimeException("Expected map, got " + type);
        return (Map<String, Value>) value;
    }
    
    public ClassDescriptor asClass() {
        if (type != Type.CLASS) {
            throw new RuntimeException("Expected class instance, got " + type);
        }
        return (ClassDescriptor) value;
    }
    
    public ClassInstance asClassInstance() {
        if (type != Type.CLASS_INSTANCE) {
            throw new RuntimeException("Expected class instance, got " + type);
        }
        return (ClassInstance) value;
    }

    @Override
    public String toString() {
        switch (type) {
            case NUMBER:
                return Double.toString(asNumber());
            case BOOL:
                return Boolean.toString(asBool());
            case STRING:
                return '"' + asString() + '"';
            case FUNC:
        		return '"' + asString() + '"';
            case BYTES:
                byte[] bb = (byte[]) value;
                return (bb == null) ? "bytes(null)" : ("bytes(" + bb.length + ")");
            case ARRAY:
                return asArray().toString();
            case MATRIX:
                return asMatrix().toString();
            case MAP: {
                Map<String, Value> m = (Map<String, Value>) value;
                return (m == null) ? "map(null)" : m.toString();
            }
            default:
                return "null";
        }
    }
    
    /*
     * Central point: how values traverse stack frames.
     * as_copy is only true when caller used '&'
     */
    public Value getForChildStackFrame(boolean as_copy) {
        switch (type) {
            // Scalars: always isolate wrapper (keeps “stack feels like pass-by-value”)
            case NUMBER: return Value.number(asNumber());
            case BOOL:   return Value.bool(asBool());
            case FUNC:   return Value.func(asFunc());
            case NULL:   return Value.nil();

            case STRING:
                // Strings are immutable anyway; but keep your behavior:
                return as_copy ? Value.string(new String(asString())) : Value.string(asString());

            case BYTES:
                if (!as_copy) {
                    return Value.bytes((byte[]) value); // no copy
                }
                byte[] src = (byte[]) value;
                return Value.bytes(src == null ? null : Arrays.copyOf(src, src.length));

            case ARRAY:
                if (!as_copy) return Value.array(asArray());
                List<Value> a = asArray();
                if (a == null) return Value.array(null);
                List<Value> ao = new java.util.ArrayList<>(a.size());
                for (Value item : a) ao.add(item == null ? null : item.getForChildStackFrame(true));
                return Value.array(ao);

            case MATRIX:
                if (!as_copy) return Value.matrix(asMatrix());
                List<List<Value>> m = asMatrix();
                if (m == null) return Value.matrix(null);
                List<List<Value>> mo = new java.util.ArrayList<>(m.size());
                for (List<Value> row : m) {
                    if (row == null) { mo.add(null); continue; }
                    List<Value> ro = new java.util.ArrayList<>(row.size());
                    for (Value cell : row) ro.add(cell == null ? null : cell.getForChildStackFrame(true));
                    mo.add(ro);
                }
                return Value.matrix(mo);

            case MAP:
                if (!as_copy) return Value.map(asMap());
                Map<String, Value> mm = asMap();
                if (mm == null) return Value.map(null);
                Map<String, Value> mout = new LinkedHashMap<>();
                for (Map.Entry<String, Value> e : mm.entrySet()) {
                    Value vv = e.getValue();
                    mout.put(e.getKey(), vv == null ? null : vv.getForChildStackFrame(true));
                }
                return Value.map(mout);

            case CLASS:
                return new Value(Type.CLASS, asClass());

            case CLASS_INSTANCE:
                return new Value(Type.CLASS_INSTANCE, asClassInstance());

            default:
                throw new RuntimeException("Unsupported Value type for child stack frame: " + type);
        }
    }

}
