import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

public class EnvironmentTraversalEsTest {

    @Test
    public void test_stackFrameIsolation_enterExitLog() {
        ElaraScript es = new ElaraScript();

        // ES program:
        // - global log array collects snapshots
        // - walk(n) recurses down, then logs on unwind
        // - each frame has its own 'local' value that must not change
        // - global 'counter' increments across all frames (shared by design)
        String src =
            "let log = [];\n" +
            "let counter = 0;\n" +
            "\n" +
            "function walk(n) {\n" +
            "  let local = n + 100;\n" +
            "  counter = counter + 1;\n" +
            "\n" +
            "  // enter snapshot\n" +
            "  log[len(log)] = {\n" +
            "    \"phase\": \"enter\",\n" +
            "    \"n\": n,\n" +
            "    \"local\": local,\n" +
            "    \"counter\": counter\n" +
            "  };\n" +
            "\n" +
            "  if (n > 0) {\n" +
            "    // shadow variable name intentionally (must not affect parent frame)\n" +
            "    let local = n * 1000;\n" +
            "    walk(n - 1);\n" +
            "  }\n" +
            "\n" +
            "  // exit snapshot (local must still be n+100)\n" +
            "  log[len(log)] = {\n" +
            "    \"phase\": \"exit\",\n" +
            "    \"n\": n,\n" +
            "    \"local\": (n + 100),\n" +
            "    \"counter\": counter\n" +
            "  };\n" +
            "  return local;\n" +
            "}\n" +
            "\n" +
            "function main(depth) {\n" +
            "  walk(depth);\n" +
            "  return log;\n" +
            "}\n";

        int depth = 6;

        Value out = es.run(src, "main", List.of(Value.number(depth)));
        assertNotNull(out);
        assertEquals(Value.Type.ARRAY, out.getType(), "main() must return an array log");

        List<Value> log = out.asArray();
        assertEquals(2 * (depth + 1), log.size(), "Expected enter+exit per recursion frame");

        // Validate ordering:
        // First (depth+1) entries are enters: n = depth..0
        // Last  (depth+1) entries are exits:  n = 0..depth
        for (int i = 0; i <= depth; i++) {
            int expectedN = depth - i;
            Value rowV = log.get(i);
            assertEquals(Value.Type.MAP, rowV.getType(), "log[i] must be a map");
            Map<String, Value> row = rowV.asMap();

            assertEquals("enter", row.get("phase").asString());
            assertEquals(expectedN, (int) row.get("n").asNumber());
            assertEquals(expectedN + 100, (int) row.get("local").asNumber());

            // counter increments once per frame at entry: 1..depth+1
            assertEquals(i + 1, (int) row.get("counter").asNumber());
        }

        for (int i = 0; i <= depth; i++) {
            int expectedN = i;
            int idx = (depth + 1) + i;

            Value rowV = log.get(idx);
            assertEquals(Value.Type.MAP, rowV.getType(), "log[idx] must be a map");
            Map<String, Value> row = rowV.asMap();

            assertEquals("exit", row.get("phase").asString());
            assertEquals(expectedN, (int) row.get("n").asNumber());

            // this is the *key* check: parent-frame local survived recursion
            assertEquals(expectedN + 100, (int) row.get("local").asNumber());

            // counter is global/shared; by the time we unwind, it should be maxed
            assertEquals(depth + 1, (int) row.get("counter").asNumber());
        }
    }
}
