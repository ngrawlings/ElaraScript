import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.shaping.ElaraDataShaper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptMapLiteralTest {

    @Test
    void mapLiteral_supportsNestedMapsAndEmbeddedArrays_andKeepsInsertionOrder() {
        ElaraScript es = new ElaraScript();

        // New shaping system: register a named shape and run via runShaped(...)
        ElaraDataShaper.Shape<Value> shape = new ElaraDataShaper.Shape<>();
        shape.output("out", ElaraDataShaper.Type.MAP).required(true);
        es.dataShaping().register("map_literal_test", shape);

        String src = ""
                + "let out = {\n"
                + "  \"a\": 1,\n"
                + "  b: 2,\n"
                + "  \"arr\": [1, 2, {\"c\": 3}],\n"
                + "  \"nested\": {\"x\": [{\"y\": true}, {\"y\": false}]}\n"
                + "};\n";

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "map_literal_test", Map.of(), false);
        assertTrue(rr.ok(), () -> "Script should run successfully: " + rr.errors());

        Value outV = rr.outputs().get("out");
        assertNotNull(outV);
        assertEquals(Value.Type.MAP, outV.getType());

        Map<String, Value> out = outV.asMap();

        // Insertion order should match literal order (LinkedHashMap)
        List<String> keys = new ArrayList<>(out.keySet());
        assertEquals(List.of("a", "b", "arr", "nested"), keys);

        assertEquals(1.0, out.get("a").asNumber(), 0.0);
        assertEquals(2.0, out.get("b").asNumber(), 0.0);

        // Embedded array with a nested map in position 3
        List<Value> arr = out.get("arr").asArray();
        assertEquals(3, arr.size());
        assertEquals(1.0, arr.get(0).asNumber(), 0.0);
        assertEquals(2.0, arr.get(1).asNumber(), 0.0);

        Map<String, Value> cMap = arr.get(2).asMap();
        assertEquals(3.0, cMap.get("c").asNumber(), 0.0);

        // Nested map -> array -> maps -> bools
        Map<String, Value> nested = out.get("nested").asMap();
        List<Value> x = nested.get("x").asArray();
        assertEquals(2, x.size());

        assertTrue(x.get(0).asMap().get("y").asBool());
        assertFalse(x.get(1).asMap().get("y").asBool());
    }
}
