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
    public static Value bytes(byte[] b) {
        if (b == null) return new Value(Type.BYTES, null);
        return new Value(Type.BYTES, Arrays.copyOf(b, b.length));
    }
    public static Value array(List<Value> a) { return new Value(Type.ARRAY, a); }
    public static Value matrix(List<List<Value>> m) { return new Value(Type.MATRIX, m); }
    public static Value map(Map<String, Value> m) {
        if (m == null) return new Value(Type.MAP, null);
        return new Value(Type.MAP, new LinkedHashMap<>(m));
    }
    public static Value clazz(ClassDescriptor c) { return new Value(Type.CLASS, c); }

    /** Stateless class descriptor (no instance state yet). */
    public static final class ClassDescriptor {
        public final String name;
        public final LinkedHashMap<String, Object> methods; // value: Interpreter.UserFunction later

        public ClassDescriptor(String name, LinkedHashMap<String, Object> methods) {
            this.name = name;
            this.methods = (methods == null) ? new LinkedHashMap<>() : methods;
        }
    }
    
    public static final class ClassInstance {
        public final String className;
        public final String uuid;

        public ClassInstance(String className, String uuid) {
            this.className = className;
            this.uuid = uuid;
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
        if (value == null) return null;
        return Arrays.copyOf((byte[]) value, ((byte[]) value).length);
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
}