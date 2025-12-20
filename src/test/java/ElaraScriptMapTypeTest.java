import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.DataShape;
import com.elara.script.ElaraScript.RunResult;
import com.elara.script.ElaraScript.Value;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptMapTypeTest {

    @Test
    void value_map_factory_isBackedByHashMap_andDefensiveCopiesInput() {
        Map<String, Value> src = new HashMap<>();
        src.put("a", Value.number(1));

        Value v = Value.map(src);
        assertEquals(Value.Type.MAP, v.getType());

        // mutate original after creating Value.map()
        src.put("b", Value.number(2));

        Map<String, Value> inside = v.asMap();
        assertTrue(inside.containsKey("a"));
        assertFalse(inside.containsKey("b"), "Value.map(...) should defensively copy the input map");
    }

    @Test
    void script_canGetSetAndLen_onMapInput() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("gotA", Value.Type.NUMBER).required(true);
        shape.output("missing", Value.Type.NULL).required(true);
        shape.output("afterSet", Value.Type.NUMBER).required(true);
        shape.output("mapLen", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("a", 123.0);
        raw.put("m", m);

        String src = """
            let gotA = m["a"];
            let missing = m["does_not_exist"];   // should become null/nil
            m["b"] = 456;
            let afterSet = m["b"];
            let mapLen = len(m);
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(123.0, rr.outputs().get("gotA").asNumber(), 0.0);
        assertEquals(Value.Type.NULL, rr.outputs().get("missing").getType());
        assertEquals(456.0, rr.outputs().get("afterSet").asNumber(), 0.0);
        assertEquals(2.0, rr.outputs().get("mapLen").asNumber(), 0.0);
    }

    @Test
    void script_mapAssignment_overwritesExistingKey() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("v1", Value.Type.NUMBER).required(true);
        shape.output("v2", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("k", 1);
        raw.put("m", m);

        String src = """
            let v1 = m["k"];
            m["k"] = 777;
            let v2 = m["k"];
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(1.0, rr.outputs().get("v1").asNumber(), 0.0);
        assertEquals(777.0, rr.outputs().get("v2").asNumber(), 0.0);
    }

    @Test
    void script_nestedMap_getWorks() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("x", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("x", 42);

        Map<String, Object> outer = new HashMap<>();
        outer.put("inner", inner);

        raw.put("m", outer);

        String src = """
            let x = m["inner"]["x"];
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());
        assertEquals(42.0, rr.outputs().get("x").asNumber(), 0.0);
    }

    @Test
    void duplication_semantics_assignmentSharesSameMap_inScript() {
        // This test documents expected semantics:
        // `let b = a;` points to same map unless you implement an explicit clone/copy builtin.
        // If you WANT copy-on-assign instead, tell me and I’ll adjust engine + tests.

        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("a", Value.Type.NUMBER).required(true);
        shape.output("b", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("z", 1);
        raw.put("m", m);

        String src = """
            let m2 = m;
            m2["z"] = 999;
            let a = m["z"];
            let b = m2["z"];
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        // If assignment shares reference, both become 999.
        assertEquals(999.0, rr.outputs().get("a").asNumber(), 0.0);
        assertEquals(999.0, rr.outputs().get("b").asNumber(), 0.0);
    }
    
    @Test
    void script_canIterateMapKeys_withKeysBuiltin_andSumValues() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("sum", Value.Type.NUMBER).required(true);
        shape.output("keyCount", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("a", 10);
        m.put("b", 20);
        m.put("c", 30);
        raw.put("m", m);

        String src = """
            let ks = keys(m);
            let sum = 0;
            for (let i = 0; i < len(ks); i = i + 1) {
                let k = ks[i];
                sum = sum + m[k];
            }
            let keyCount = len(ks);
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(60.0, rr.outputs().get("sum").asNumber(), 0.0);
        assertEquals(3.0, rr.outputs().get("keyCount").asNumber(), 0.0);
    }
    
    @Test
    void builtin_mapNew_createsEmptyIndependentMap() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.output("len1", Value.Type.NUMBER).required(true);
        shape.output("len2", Value.Type.NUMBER).required(true);

        String src = """
            let m1 = map_new();
            let m2 = map_new();

            m1["a"] = 1;

            let len1 = len(m1);
            let len2 = len(m2);
        """;

        RunResult rr = es.run(src, shape, Map.of());
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(1.0, rr.outputs().get("len1").asNumber(), 0.0);
        assertEquals(0.0, rr.outputs().get("len2").asNumber(), 0.0);
    }

    @Test
    void builtin_mapClone_createsShallowIndependentMap() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP).required(true);
        shape.output("orig", Value.Type.NUMBER).required(true);
        shape.output("clone", Value.Type.NUMBER).required(true);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("x", 1);
        raw.put("m", m);

        String src = """
            let c = map_clone(m);
            c["x"] = 999;

            let orig = m["x"];
            let clone = c["x"];
        """;

        RunResult rr = es.run(src, shape, raw);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        // Original must remain unchanged
        assertEquals(1.0, rr.outputs().get("orig").asNumber(), 0.0);
        assertEquals(999.0, rr.outputs().get("clone").asNumber(), 0.0);
    }
    
    @Test
    void builtin_mapRemoveKey_removesKey_andShrinksLen() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.output("before", Value.Type.NUMBER).required(true);
        shape.output("removed", Value.Type.BOOL).required(true);
        shape.output("after", Value.Type.NUMBER).required(true);
        shape.output("missing", Value.Type.NULL).required(true);

        String src = """
            let m = map_new();
            m["a"] = 1;
            m["b"] = 2;

            let before = len(m);
            let removed = map_remove_key(m, "a");
            let after = len(m);
            let missing = m["a"];
        """;

        RunResult rr = es.run(src, shape, Map.of());
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(2.0, rr.outputs().get("before").asNumber(), 0.0);
        assertTrue(rr.outputs().get("removed").asBool());
        assertEquals(1.0, rr.outputs().get("after").asNumber(), 0.0);
        assertEquals(Value.Type.NULL, rr.outputs().get("missing").getType());
    }


}
