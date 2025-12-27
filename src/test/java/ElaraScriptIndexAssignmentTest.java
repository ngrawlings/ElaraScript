import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptIndexAssignmentTest {

    @Test
    void array_index_assignment_sets_value() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "let a = [1, 2, 3];",
            "a[1] = 9;",
            ""
        );

        Map<String, Value> snapshot = es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>());
        Map<String, Value> vars = extractInnermostVars(snapshot);

        Value a = vars.get("a");
        assertNotNull(a);
        assertEquals(Value.Type.ARRAY, a.getType());

        List<Value> arr = a.asArray();
        assertEquals(3, arr.size());
        assertEquals(1.0, arr.get(0).asNumber());
        assertEquals(9.0, arr.get(1).asNumber());
        assertEquals(3.0, arr.get(2).asNumber());
    }

    @Test
    void array_index_assignment_allows_append_at_len() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "let a = [1];",
            "a[len(a)] = 2;",
            ""
        );

        Map<String, Value> snapshot = es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>());
        Map<String, Value> vars = extractInnermostVars(snapshot);

        List<Value> arr = vars.get("a").asArray();
        assertEquals(2, arr.size());
        assertEquals(1.0, arr.get(0).asNumber());
        assertEquals(2.0, arr.get(1).asNumber());
    }

    @Test
    void nested_array_assignment_supports_pairs_style_update() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "let kv = [\"ts\", 1];",
            "kv[1] = 2;",
            ""
        );

        Map<String, Value> snapshot = es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>());
        Map<String, Value> vars = extractInnermostVars(snapshot);

        List<Value> kv = vars.get("kv").asArray();
        assertEquals("ts", kv.get(0).asString());
        assertEquals(2.0, kv.get(1).asNumber());
    }

    @Test
    void bytes_index_assignment_sets_byte_value() {
        ElaraScript es = new ElaraScript();

        Map<String, Value> initial = new LinkedHashMap<>();
        initial.put("b", Value.bytes(new byte[] { 1, 2, 3 }));

        String src = String.join("\n",
            "b[1] = 255;",
            ""
        );

        Map<String, Value> snapshot = es.run(src, new LinkedHashMap<>(), initial);
        Map<String, Value> vars = extractInnermostVars(snapshot);

        Value b = vars.get("b");
        assertNotNull(b);
        assertEquals(Value.Type.BYTES, b.getType());

        byte[] bb = b.asBytes();
        assertArrayEquals(new byte[] { 1, (byte) 255, 3 }, bb);
    }

    @Test
    void bytes_assignment_rejects_out_of_range() {
        ElaraScript es = new ElaraScript();

        Map<String, Value> initial = new LinkedHashMap<>();
        initial.put("b", Value.bytes(new byte[] { 0 }));

        String src = String.join("\n",
            "b[0] = 256;",
            ""
        );

        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), initial)
        );
    }

    @Test
    void array_assignment_rejects_out_of_bounds_greater_than_len() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "let a = [1];",
            "a[2] = 9;",
            ""
        );

        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );
    }

    // ---------------- helpers ----------------

    private static Map<String, Value> extractInnermostVars(Map<String, Value> snapshot) {
        Value envsV = snapshot.get("environments");
        assertNotNull(envsV, "snapshot must contain environments");
        assertEquals(Value.Type.ARRAY, envsV.getType(), "environments must be ARRAY");

        List<Value> frames = envsV.asArray();
        assertNotNull(frames, "environments array must not be null");
        assertFalse(frames.isEmpty(), "environments must not be empty");

        Value lastFrameV = frames.get(frames.size() - 1);
        assertNotNull(lastFrameV, "last frame must not be null");
        assertEquals(Value.Type.MAP, lastFrameV.getType(), "frame must be MAP");

        Map<String, Value> frame = lastFrameV.asMap();
        Value varsV = frame.get("vars");
        assertNotNull(varsV, "frame must contain vars");
        assertEquals(Value.Type.MAP, varsV.getType(), "vars must be MAP");

        return varsV.asMap();
    }
}
