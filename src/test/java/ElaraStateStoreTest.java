import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraStateStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ElaraStateStore.
 */
public class ElaraStateStoreTest {

    private static void assertNumberEquals(double expected, Object actual) {
        assertNotNull(actual, "Expected number but was null");
        assertTrue(actual instanceof Number, "Expected Number but got: " + actual.getClass().getName());
        assertEquals(expected, ((Number) actual).doubleValue(), 1e-12);
    }

    @Test
    void json_roundtrip_primitives() {
        ElaraStateStore s = new ElaraStateStore();
        s.put("a", null);
        s.put("b", true);
        s.put("c", 123);
        s.put("d", 45.67);
        s.put("e", "hello");

        String json = s.toJson();
        ElaraStateStore r = ElaraStateStore.fromJson(json);
        Map<String, Object> m = r.toRawInputs();

        assertTrue(m.containsKey("a"));
        assertNull(m.get("a"));
        assertEquals(true, m.get("b"));

        // Numbers are normalized to Double
        assertEquals(123.0, (Double) m.get("c"), 0.0);
        assertEquals(45.67, (Double) m.get("d"), 1e-12);

        assertEquals("hello", m.get("e"));
    }

    @Test
    void json_roundtrip_arrays_and_matrices() {
        ElaraStateStore s = new ElaraStateStore();

        List<Object> arr = new ArrayList<>();
        arr.add(1);
        arr.add(2.5);
        arr.add(null);
        arr.add(true);
        arr.add("x");

        List<Object> row1 = List.of(1, 2, 3);
                // List.of(...) forbids null elements; use Arrays.asList for a row containing null
        List<Object> row2 = java.util.Arrays.asList(4.0, null, 6);
        List<Object> matrix = List.of(row1, row2);

        s.put("arr", arr);
        s.put("matrix", matrix);

        String json = s.toJson();
        ElaraStateStore r = ElaraStateStore.fromJson(json);
        Map<String, Object> m = r.toRawInputs();

        assertTrue(m.get("arr") instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> arr2 = (List<Object>) m.get("arr");

        assertEquals(5, arr2.size());
        assertNumberEquals(1.0, arr2.get(0));
        assertNumberEquals(2.5, arr2.get(1));
        assertNull(arr2.get(2));
        assertEquals(true, arr2.get(3));
        assertEquals("x", arr2.get(4));

        assertTrue(m.get("matrix") instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> mat2 = (List<Object>) m.get("matrix");
        assertEquals(2, mat2.size());

        assertTrue(mat2.get(0) instanceof List);
        assertTrue(mat2.get(1) instanceof List);

        @SuppressWarnings("unchecked")
        List<Object> r1 = (List<Object>) mat2.get(0);
        @SuppressWarnings("unchecked")
        List<Object> r2 = (List<Object>) mat2.get(1);

        assertNumberEquals(1.0, r1.get(0));
        assertNumberEquals(2.0, r1.get(1));
        assertNumberEquals(3.0, r1.get(2));

        assertNumberEquals(4.0, r2.get(0));
        assertNull(r2.get(1));
        assertNumberEquals(6.0, r2.get(2));
    }

    @Test
    void json_roundtrip_nested_object_state() {
        ElaraStateStore s = new ElaraStateStore();

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", 1);
        inner.put("y", "ok");

        s.put("obj", inner);

        String json = s.toJson();
        ElaraStateStore r = ElaraStateStore.fromJson(json);
        Map<String, Object> m = r.toRawInputs();

        assertTrue(m.get("obj") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) m.get("obj");

        assertEquals(1.0, (Double) obj.get("x"), 0.0);
        assertEquals("ok", obj.get("y"));
    }

    @Test
    void captureOutputs_converts_values_to_json_safe() {
        ElaraStateStore store = new ElaraStateStore();

        Map<String, ElaraScript.Value> outputs = new LinkedHashMap<>();
        outputs.put("n", ElaraScript.Value.number(3.14));
        outputs.put("b", ElaraScript.Value.bool(true));
        outputs.put("s", ElaraScript.Value.string("hi"));
        outputs.put("z", ElaraScript.Value.nil());
        outputs.put("a", ElaraScript.Value.array(List.of(
                ElaraScript.Value.number(1),
                ElaraScript.Value.nil(),
                ElaraScript.Value.string("x")
        )));

        store.captureOutputs(outputs);
        Map<String, Object> m = store.toRawInputs();

        assertNumberEquals(3.14, m.get("n"));
        assertEquals(true, m.get("b"));
        assertEquals("hi", m.get("s"));
        assertNull(m.get("z"));

        assertTrue(m.get("a") instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(3, a.size());
        assertNumberEquals(1.0, a.get(0));
        assertNull(a.get(1));
        assertEquals("x", a.get(2));
    }

    @Test
    void captureEnv_and_restore_as_inputs() {
        ElaraStateStore store = new ElaraStateStore();

        Map<String, ElaraScript.Value> env = new LinkedHashMap<>();
        env.put("weightKg", ElaraScript.Value.number(80));
        env.put("durationMin", ElaraScript.Value.number(40));
        env.put("intensity", ElaraScript.Value.number(5));
        env.put("calories", ElaraScript.Value.number(280));

        store.captureEnv(env);
        String json = store.toJson();

        ElaraStateStore restored = ElaraStateStore.fromJson(json);
        Map<String, Object> inputs = restored.toRawInputs();

        assertEquals(80.0, (Double) inputs.get("weightKg"), 0.0);
        assertEquals(40.0, (Double) inputs.get("durationMin"), 0.0);
        assertEquals(5.0, (Double) inputs.get("intensity"), 0.0);
        assertEquals(280.0, (Double) inputs.get("calories"), 0.0);
    }

    @Test
    void invalid_json_throws() {
        assertThrows(IllegalArgumentException.class, () -> ElaraStateStore.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> ElaraStateStore.fromJson("[1,2,3]"));
    }

    @Test
    void rejects_non_string_map_keys_on_put() {
        // Construct a map with a non-string key and try to store it.
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(1, "x");

        ElaraStateStore s = new ElaraStateStore();
        assertThrows(IllegalArgumentException.class, () -> s.put("bad", bad));
    }

    @Test
    void numbers_nan_and_infinity_serialize_as_null() {
        ElaraStateStore s = new ElaraStateStore();
        s.put("nan", Double.NaN);
        s.put("inf", Double.POSITIVE_INFINITY);
        s.put("ninf", Double.NEGATIVE_INFINITY);

        String json = s.toJson();
        ElaraStateStore r = ElaraStateStore.fromJson(json);
        Map<String, Object> m = r.toRawInputs();

        assertNull(m.get("nan"));
        assertNull(m.get("inf"));
        assertNull(m.get("ninf"));
    }
}
