package com.elara.script.shaping;

import java.util.List;
import java.util.Map;

import com.elara.script.parser.Value;

public class DataShaping {

	/** Bridges ElaraScript.Value <-> ElaraDataShaper without coupling the shaper to interpreter internals. */
    public static final ElaraDataShaper.ValueAdapter<Value> VALUE_ADAPTER = new ElaraDataShaper.ValueAdapter<Value>() {
        @Override
        public ElaraDataShaper.Type typeOf(Value v) {
            if (v == null) return ElaraDataShaper.Type.NULL;
            switch (v.getType()) {
                case NUMBER: return ElaraDataShaper.Type.NUMBER;
                case BOOL:   return ElaraDataShaper.Type.BOOL;
                case STRING: return ElaraDataShaper.Type.STRING;
                case FUNC:   return ElaraDataShaper.Type.STRING;
                case BYTES:  return ElaraDataShaper.Type.BYTES;
                case ARRAY:  return ElaraDataShaper.Type.ARRAY;
                case MATRIX: return ElaraDataShaper.Type.MATRIX;
                case MAP:    return ElaraDataShaper.Type.MAP;
                case NULL:
                default:     return ElaraDataShaper.Type.NULL;
            }
        }

        @Override public double asNumber(Value v) { return v.asNumber(); }
        @Override public boolean asBool(Value v) { return v.asBool(); }
        @Override public String asString(Value v) { return v.asString(); }
        @Override public byte[] asBytes(Value v) { return v.asBytes(); }

        @Override public List<Value> asArray(Value v) { return v.asArray(); }
        @Override public List<List<Value>> asMatrix(Value v) { return v.asMatrix(); }
        @Override public Map<String, Value> asMap(Value v) { return v.asMap(); }

        @Override public Value number(double d) { return Value.number(d); }
        @Override public Value bool(boolean b) { return Value.bool(b); }
        @Override public Value string(String s) { return Value.string(s); }
        @Override public Value bytes(byte[] b) { return Value.bytes(b); }
        @Override public Value array(List<Value> a) { return Value.array(a); }
        @Override public Value matrix(List<List<Value>> m) { return Value.matrix(m); }
        @Override public Value map(Map<String, Value> m) { return Value.map(m); }
        @Override public Value nil() { return Value.nil(); }
    };
    
}
