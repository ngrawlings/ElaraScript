import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.shaping.ElaraDataShaper;
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

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("gotA", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("missing", ElaraDataShaper.Type.NULL).required(true);
        shape.output("afterSet", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("mapLen", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_get_set_len", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("a", 123.0);
        raw.put("m", m);

        String src =
                "let gotA = m[\"a\"];\n" +
                "let missing = m[\"does_not_exist\"];   // should become null/nil\n" +
                "m[\"b\"] = 456;\n" +
                "let afterSet = m[\"b\"];\n" +
                "let mapLen = len(m);\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_get_set_len", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(123.0, rr.outputs().get("gotA").asNumber(), 0.0);
        assertEquals(Value.Type.NULL, rr.outputs().get("missing").getType());
        assertEquals(456.0, rr.outputs().get("afterSet").asNumber(), 0.0);
        assertEquals(2.0, rr.outputs().get("mapLen").asNumber(), 0.0);
    }

    @Test
    void script_mapAssignment_overwritesExistingKey() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("v1", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("v2", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_overwrite", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("k", 1);
        raw.put("m", m);

        String src =
                "let v1 = m[\"k\"];\n" +
                "m[\"k\"] = 777;\n" +
                "let v2 = m[\"k\"];\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_overwrite", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(1.0, rr.outputs().get("v1").asNumber(), 0.0);
        assertEquals(777.0, rr.outputs().get("v2").asNumber(), 0.0);
    }

    @Test
    void script_nestedMap_getWorks() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("x", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_nested_get", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("x", 42);

        Map<String, Object> outer = new HashMap<>();
        outer.put("inner", inner);

        raw.put("m", outer);

        String src =
                "let x = m[\"inner\"][\"x\"];\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_nested_get", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());
        assertEquals(42.0, rr.outputs().get("x").asNumber(), 0.0);
    }

    @Test
    void duplication_semantics_assignmentSharesSameMap_inScript() {
        // This test documents expected semantics:
        // `let b = a;` points to same map unless you implement an explicit clone/copy builtin.

        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("a", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("b", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_assign_share", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("z", 1);
        raw.put("m", m);

        String src =
                "let m2 = m;\n" +
                "m2[\"z\"] = 999;\n" +
                "let a = m[\"z\"];\n" +
                "let b = m2[\"z\"];\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_assign_share", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(999.0, rr.outputs().get("a").asNumber(), 0.0);
        assertEquals(999.0, rr.outputs().get("b").asNumber(), 0.0);
    }

    @Test
    void script_canIterateMapKeys_withKeysBuiltin_andSumValues() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("sum", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("keyCount", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_keys_sum", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("a", 10);
        m.put("b", 20);
        m.put("c", 30);
        raw.put("m", m);

        String src =
                "let ks = keys(m);\n" +
                "let sum = 0;\n" +
                "for (let i = 0; i < len(ks); i = i + 1) {\n" +
                "    let k = ks[i];\n" +
                "    sum = sum + m[k];\n" +
                "}\n" +
                "let keyCount = len(ks);\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_keys_sum", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(60.0, rr.outputs().get("sum").asNumber(), 0.0);
        assertEquals(3.0, rr.outputs().get("keyCount").asNumber(), 0.0);
    }

    @Test
    void builtin_mapNew_createsEmptyIndependentMap() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.output("len1", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("len2", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_new", shape);

        String src =
                "let m1 = map_new();\n" +
                "let m2 = map_new();\n" +
                "\n" +
                "m1[\"a\"] = 1;\n" +
                "\n" +
                "let len1 = len(m1);\n" +
                "let len2 = len(m2);\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_new", Map.of(), false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(1.0, rr.outputs().get("len1").asNumber(), 0.0);
        assertEquals(0.0, rr.outputs().get("len2").asNumber(), 0.0);
    }

    @Test
    void builtin_mapClone_createsShallowIndependentMap() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.output("orig", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("clone", ElaraDataShaper.Type.NUMBER).required(true);

        es.dataShaping().register("map_clone", shape);

        Map<String, Object> raw = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        m.put("x", 1);
        raw.put("m", m);

        String src =
                "let c = map_clone(m);\n" +
                "c[\"x\"] = 999;\n" +
                "\n" +
                "let orig = m[\"x\"];\n" +
                "let clone = c[\"x\"];\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_clone", raw, false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(1.0, rr.outputs().get("orig").asNumber(), 0.0);
        assertEquals(999.0, rr.outputs().get("clone").asNumber(), 0.0);
    }

    @Test
    void builtin_mapRemoveKey_removesKey_andShrinksLen() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.output("before", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("removed", ElaraDataShaper.Type.BOOL).required(true);
        shape.output("after", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("missing", ElaraDataShaper.Type.NULL).required(true);

        es.dataShaping().register("map_remove_key", shape);

        String src =
                "let m = map_new();\n" +
                "m[\"a\"] = 1;\n" +
                "m[\"b\"] = 2;\n" +
                "\n" +
                "let before = len(m);\n" +
                "let removed = map_remove_key(m, \"a\");\n" +
                "let after = len(m);\n" +
                "let missing = m[\"a\"];\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_remove_key", Map.of(), false);
        assertTrue(rr.ok(), () -> "Errors: " + rr.errors());

        assertEquals(2.0, rr.outputs().get("before").asNumber(), 0.0);
        assertTrue(rr.outputs().get("removed").asBool());
        assertEquals(1.0, rr.outputs().get("after").asNumber(), 0.0);
        assertEquals(Value.Type.NULL, rr.outputs().get("missing").getType());
    }
}
