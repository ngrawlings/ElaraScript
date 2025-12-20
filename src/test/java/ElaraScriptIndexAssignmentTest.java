import com.elara.script.ElaraScript;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptIndexAssignmentTest {

    @Test
    void array_index_assignment_sets_value() {
        ElaraScript es = new ElaraScript();

        String src = """
            let a = [1, 2, 3];
            a[1] = 9;
        """;

        Map<String, ElaraScript.Value> env = es.run(src);

        ElaraScript.Value a = env.get("a");
        assertNotNull(a);
        assertEquals(ElaraScript.Value.Type.ARRAY, a.getType());

        List<ElaraScript.Value> arr = a.asArray();
        assertEquals(3, arr.size());
        assertEquals(1.0, arr.get(0).asNumber());
        assertEquals(9.0, arr.get(1).asNumber());
        assertEquals(3.0, arr.get(2).asNumber());
    }

    @Test
    void array_index_assignment_allows_append_at_len() {
        ElaraScript es = new ElaraScript();

        String src = """
            let a = [1];
            a[len(a)] = 2;
        """;

        Map<String, ElaraScript.Value> env = es.run(src);

        List<ElaraScript.Value> arr = env.get("a").asArray();
        assertEquals(2, arr.size());
        assertEquals(1.0, arr.get(0).asNumber());
        assertEquals(2.0, arr.get(1).asNumber());
    }

    @Test
    void nested_array_assignment_supports_pairs_style_update() {
        ElaraScript es = new ElaraScript();

        String src = """
            let kv = ["ts", 1];
            kv[1] = 2;
        """;

        Map<String, ElaraScript.Value> env = es.run(src);

        List<ElaraScript.Value> kv = env.get("kv").asArray();
        assertEquals("ts", kv.get(0).asString());
        assertEquals(2.0, kv.get(1).asNumber());
    }

    @Test
    void bytes_index_assignment_sets_byte_value() {
        ElaraScript es = new ElaraScript();

        Map<String, ElaraScript.Value> initial = new LinkedHashMap<>();
        initial.put("b", ElaraScript.Value.bytes(new byte[] { 1, 2, 3 }));

        String src = """
            b[1] = 255;
        """;

        Map<String, ElaraScript.Value> env = es.run(src, initial);

        ElaraScript.Value b = env.get("b");
        assertNotNull(b);
        assertEquals(ElaraScript.Value.Type.BYTES, b.getType());

        byte[] bb = b.asBytes();
        assertArrayEquals(new byte[] { 1, (byte) 255, 3 }, bb);
    }

    @Test
    void bytes_assignment_rejects_out_of_range() {
        ElaraScript es = new ElaraScript();

        Map<String, ElaraScript.Value> initial = new LinkedHashMap<>();
        initial.put("b", ElaraScript.Value.bytes(new byte[] { 0 }));

        String src = """
            b[0] = 256;
        """;

        assertThrows(RuntimeException.class, () -> es.run(src, initial));
    }

    @Test
    void array_assignment_rejects_out_of_bounds_greater_than_len() {
        ElaraScript es = new ElaraScript();

        String src = """
            let a = [1];
            a[2] = 9;
        """;

        assertThrows(RuntimeException.class, () -> es.run(src));
    }
}
