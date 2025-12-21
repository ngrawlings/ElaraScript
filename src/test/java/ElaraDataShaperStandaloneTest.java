import com.elara.script.shaping.ElaraDataShaper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone tests for ElaraDataShaper.java (no dependency on ElaraScript).
 *
 * These tests use a tiny local Value type + Adapter to validate:
 * - coercion from raw inputs
 * - validation constraints
 * - output extraction
 * - debugEnv behavior
 */
public class ElaraDataShaperStandaloneTest {

    // -------------------- Minimal runtime Value --------------------

    static final class V {
        final ElaraDataShaper.Type type;
        final Object value;

        V(ElaraDataShaper.Type type, Object value) {
            this.type = type;
            this.value = value;
        }

        @Override public String toString() {
            return "V(" + type + "," + value + ")";
        }
    }

    static final class Adapter implements ElaraDataShaper.ValueAdapter<V> {

        @Override public ElaraDataShaper.Type typeOf(V v) { return (v == null) ? ElaraDataShaper.Type.NULL : v.type; }

        @Override public double asNumber(V v) { return (double) v.value; }
        @Override public boolean asBool(V v) { return (boolean) v.value; }
        @Override public String asString(V v) { return (String) v.value; }
        @Override public byte[] asBytes(V v) { return (byte[]) v.value; }

        @SuppressWarnings("unchecked")
        @Override public List<V> asArray(V v) { return (List<V>) v.value; }

        @SuppressWarnings("unchecked")
        @Override public List<List<V>> asMatrix(V v) { return (List<List<V>>) v.value; }

        @SuppressWarnings("unchecked")
        @Override public Map<String, V> asMap(V v) { return (Map<String, V>) v.value; }

        @Override public V number(double d) { return new V(ElaraDataShaper.Type.NUMBER, d); }
        @Override public V bool(boolean b) { return new V(ElaraDataShaper.Type.BOOL, b); }
        @Override public V string(String s) { return new V(ElaraDataShaper.Type.STRING, s); }
        @Override public V bytes(byte[] b) { return new V(ElaraDataShaper.Type.BYTES, b); }
        @Override public V array(List<V> a) { return new V(ElaraDataShaper.Type.ARRAY, a); }
        @Override public V matrix(List<List<V>> m) { return new V(ElaraDataShaper.Type.MATRIX, m); }
        @Override public V map(Map<String, V> m) { return new V(ElaraDataShaper.Type.MAP, m); }
        @Override public V nil() { return new V(ElaraDataShaper.Type.NULL, null); }
    }

    private ElaraDataShaper<V> shaper() {
        return new ElaraDataShaper<>(new Adapter());
    }

    // -------------------- Helpers --------------------

    private static Map<String, Object> m(Object... kv) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static List<Object> l(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // -------------------- Tests --------------------

    @Test
    void numbers_bools_strings_and_defaults_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();

        shape.input("n", ElaraDataShaper.Type.NUMBER).required(true);
        shape.input("b", ElaraDataShaper.Type.BOOL).required(true);
        shape.input("s", ElaraDataShaper.Type.STRING).required(false).defaultValue(new Adapter().string("dflt"));

        shape.output("out", ElaraDataShaper.Type.NUMBER).required(true);

        ElaraDataShaper.RunResult<V> rr = ds.run(
                shape,
                m("n", 2.5, "b", true), // omit s -> default
                env -> {
                    assertEquals(2.5, env.get("n").value);
                    assertEquals(true, env.get("b").value);
                    assertEquals("dflt", env.get("s").value);
                    env.put("out", new Adapter().number(9));
                    return env;
                },
                true
        );

        assertTrue(rr.ok(), () -> "expected ok, errors=" + rr.errors());
        assertEquals(1, rr.outputs().size());
        assertEquals(9.0, (double) rr.outputs().get("out").value);
        assertNotNull(rr.debugEnv());
        assertTrue(rr.debugEnv().containsKey("out"));
    }

    @Test
    void coerceFromString_parsesNumbersAndBools_andCanBeDisabledPerField() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();

        shape.input("n", ElaraDataShaper.Type.NUMBER).required(true).coerceFromString(true);
        shape.input("b", ElaraDataShaper.Type.BOOL).required(true).coerceFromString(true);
        shape.input("n2", ElaraDataShaper.Type.NUMBER).required(true).coerceFromString(false);

        shape.output("ok", ElaraDataShaper.Type.BOOL).required(true);

        // n,b should parse; n2 should fail (coercion disabled)
        ElaraDataShaper.RunResult<V> rr = ds.run(
                shape,
                m("n", "12.25", "b", "true", "n2", "7"),
                env -> {
                    env.put("ok", new Adapter().bool(true));
                    return env;
                },
                true
        );

        assertFalse(rr.ok());
        assertEquals(1, rr.errors().size());
        assertEquals("n2", rr.errors().get(0).field);
        assertTrue(rr.errors().get(0).message.toLowerCase().contains("expected number"));
        assertNotNull(rr.debugEnv()); // should be initial env snapshot since input validation failed
        assertTrue(rr.debugEnv().containsKey("n"));
        assertTrue(rr.debugEnv().containsKey("b"));
        assertFalse(rr.debugEnv().containsKey("n2"));
    }

    @Test
    void number_constraints_min_max_integerOnly_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();

        shape.input("a", ElaraDataShaper.Type.NUMBER).required(true).min(0).max(10).integerOnly(true);

        ElaraDataShaper.RunResult<V> rr1 = ds.run(
                shape,
                m("a", 3.0),
                env -> env,
                false
        );
        assertTrue(rr1.ok());

        ElaraDataShaper.RunResult<V> rr2 = ds.run(
                shape,
                m("a", -1),
                env -> env,
                false
        );
        assertFalse(rr2.ok());
        assertTrue(rr2.errors().get(0).message.contains(">="));

        ElaraDataShaper.RunResult<V> rr3 = ds.run(
                shape,
                m("a", 2.2),
                env -> env,
                false
        );
        assertFalse(rr3.ok());
        assertTrue(rr3.errors().get(0).message.toLowerCase().contains("integer"));
    }

    @Test
    void string_constraints_len_and_regex_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("email", ElaraDataShaper.Type.STRING).required(true).minLen(3).maxLen(32).regex("^[^@]+@[^@]+\\.[^@]+$");

        assertTrue(ds.run(shape, m("email", "a@b.com"), env -> env, false).ok());

        ElaraDataShaper.RunResult<V> rr1 = ds.run(shape, m("email", "x"), env -> env, false);
        assertFalse(rr1.ok());
        assertTrue(rr1.errors().get(0).message.contains(">="));

        ElaraDataShaper.RunResult<V> rr2 = ds.run(shape, m("email", "not-an-email"), env -> env, false);
        assertFalse(rr2.ok());
        assertTrue(rr2.errors().get(0).message.toLowerCase().contains("pattern"));
    }

    @Test
    void bytes_coercion_from_byteArray_byteBuffer_and_list_of_numbers() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("b1", ElaraDataShaper.Type.BYTES).required(true);
        shape.input("b2", ElaraDataShaper.Type.BYTES).required(true);
        shape.input("b3", ElaraDataShaper.Type.BYTES).required(true);

        byte[] a = new byte[]{1,2,3};
        ByteBuffer bb = ByteBuffer.wrap(new byte[]{4,5});
        List<Object> list = l(6,7,255);

        ElaraDataShaper.RunResult<V> rr = ds.run(
                shape,
                m("b1", a, "b2", bb, "b3", list),
                env -> env,
                false
        );
        assertTrue(rr.ok(), () -> "errors=" + rr.errors());

        assertNull(rr.debugEnv(), "debugEnv should be null when includeDebugEnv=false");
    }

    @Test
    void bytes_validation_len_constraints_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("b", ElaraDataShaper.Type.BYTES).required(true).minLen(2).maxLen(4);

        assertTrue(ds.run(shape, m("b", new byte[]{1,2}), env -> env, false).ok());
        assertFalse(ds.run(shape, m("b", new byte[]{1}), env -> env, false).ok());
        assertFalse(ds.run(shape, m("b", new byte[]{1,2,3,4,5}), env -> env, false).ok());
    }

    @Test
    void array_coercion_and_elementType_constraints_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("arr", ElaraDataShaper.Type.ARRAY).required(true).minItems(2).maxItems(4).elementType(ElaraDataShaper.Type.NUMBER);

        assertTrue(ds.run(shape, m("arr", l(1,2)), env -> env, false).ok());

        ElaraDataShaper.RunResult<V> rr1 = ds.run(shape, m("arr", l(1)), env -> env, false);
        assertFalse(rr1.ok());
        assertTrue(rr1.errors().get(0).message.contains("at least"));

        ElaraDataShaper.RunResult<V> rr2 = ds.run(shape, m("arr", l(1, "x")), env -> env, false);
        assertFalse(rr2.ok());
        // type mismatch should be on arr[1]
        assertTrue(rr2.errors().get(0).field.contains("arr") || rr2.errors().get(0).field.contains("["));
    }

    @Test
    void array_elementMinMax_for_numbers_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("arr", ElaraDataShaper.Type.ARRAY).required(true)
                .elementType(ElaraDataShaper.Type.NUMBER).elementMin(0).elementMax(10);

        assertTrue(ds.run(shape, m("arr", l(0, 10, 5)), env -> env, false).ok());
        assertFalse(ds.run(shape, m("arr", l(-1, 2)), env -> env, false).ok());
        assertFalse(ds.run(shape, m("arr", l(11)), env -> env, false).ok());
    }

    @Test
    void matrix_rectangular_and_cell_count_and_elementType_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("mx", ElaraDataShaper.Type.MATRIX).required(true).elementType(ElaraDataShaper.Type.NUMBER).maxCells(6);

        // ok: 2x3 = 6 cells
        assertTrue(ds.run(shape, m("mx", l(l(1,2,3), l(4,5,6))), env -> env, false).ok());

        // not rectangular
        assertFalse(ds.run(shape, m("mx", l(l(1,2), l(3,4,5))), env -> env, false).ok());

        // too many cells: 3x3 = 9
        assertFalse(ds.run(shape, m("mx", l(l(1,2,3), l(4,5,6), l(7,8,9))), env -> env, false).ok());

        // wrong element type
        assertFalse(ds.run(shape, m("mx", l(l(1,2), l(3,"x"))), env -> env, false).ok());
    }

    @Test
    void map_coercion_and_homogeneous_value_typing_work() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("m", ElaraDataShaper.Type.MAP).required(true).minItems(1).maxItems(3).elementType(ElaraDataShaper.Type.NUMBER);

        Map<String,Object> ok = m("a", 1, "b", 2);
        assertTrue(ds.run(shape, m("m", ok), env -> env, false).ok());

        Map<String,Object> badType = m("a", 1, "b", "x");
        ElaraDataShaper.RunResult<V> rr = ds.run(shape, m("m", badType), env -> env, false);
        assertFalse(rr.ok());
        assertTrue(rr.errors().get(0).field.contains("m.") || rr.errors().get(0).field.contains("m"));
    }

    @Test
    void nested_maps_and_arrays_are_coerced_via_coerceAny() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("doc", ElaraDataShaper.Type.MAP).required(true);

        Map<String,Object> nested = m(
                "node1", m(
                        "child4", m(
                                "nums", l(1,2,3),
                                "flag", true
                        )
                )
        );

        ElaraDataShaper.RunResult<V> rr = ds.run(shape, m("doc", nested), env -> env, true);
        assertTrue(rr.ok(), () -> "errors=" + rr.errors());
        V doc = rr.debugEnv().get("doc");
        assertNotNull(doc);
        Map<String,V> docMap = new Adapter().asMap(doc);
        V node1 = docMap.get("node1");
        Map<String,V> node1Map = new Adapter().asMap(node1);
        V child4 = node1Map.get("child4");
        Map<String,V> child4Map = new Adapter().asMap(child4);
        V nums = child4Map.get("nums");
        assertEquals(ElaraDataShaper.Type.ARRAY, nums.type);
        assertEquals(3, new Adapter().asArray(nums).size());
    }

    @Test
    void output_validation_and_missing_required_output_fail() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("x", ElaraDataShaper.Type.NUMBER).required(true);
        shape.output("y", ElaraDataShaper.Type.NUMBER).required(true);

        ElaraDataShaper.RunResult<V> rr = ds.run(
                shape,
                m("x", 1),
                env -> env, // does not set y
                true
        );

        assertFalse(rr.ok());
        assertEquals("y", rr.errors().get(0).field);
        assertTrue(rr.errors().get(0).message.toLowerCase().contains("missing"));
        assertNotNull(rr.debugEnv()); // full env snapshot (after run) in this case
        assertTrue(rr.debugEnv().containsKey("x"));
    }

    @Test
    void runtime_error_is_reported_as_runtime_validation_error() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.input("x", ElaraDataShaper.Type.NUMBER).required(true);

        ElaraDataShaper.RunResult<V> rr = ds.run(
                shape,
                m("x", 1),
                env -> { throw new RuntimeException("boom"); },
                true
        );

        assertFalse(rr.ok());
        assertEquals("$runtime", rr.errors().get(0).field);
        assertTrue(rr.errors().get(0).message.contains("boom"));
        assertNotNull(rr.debugEnv()); // initial env included
        assertTrue(rr.debugEnv().containsKey("x"));
    }

    @Test
    void global_limits_are_enforced_string_and_map_and_array() {
        ElaraDataShaper<V> ds = shaper();
        ElaraDataShaper.Shape<V> shape = new ElaraDataShaper.Shape<>();
        shape.maxStringLength = 3;
        shape.maxMapItems = 2;
        shape.maxArrayLength = 2;

        shape.input("s", ElaraDataShaper.Type.STRING).required(true);
        shape.input("m", ElaraDataShaper.Type.MAP).required(true);
        shape.input("a", ElaraDataShaper.Type.ARRAY).required(true);

        // string too long
        assertFalse(ds.run(shape, m("s", "abcd", "m", m("k","v"), "a", l(1)), env -> env, false).ok());

        // map too large
        assertFalse(ds.run(shape, m("s", "abc", "m", m("k1",1,"k2",2,"k3",3), "a", l(1)), env -> env, false).ok());

        // array too large
        assertFalse(ds.run(shape, m("s", "abc", "m", m("k1",1), "a", l(1,2,3)), env -> env, false).ok());
    }
}