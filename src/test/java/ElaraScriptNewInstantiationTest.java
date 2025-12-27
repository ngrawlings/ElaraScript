import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptNewInstantiationTest {

    @Test
    public void newCreatesInstanceStateAndTracksLiveInstances() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  let x = 0;\n" +
                "  let y = 0;\n" +
                "  def constructor() { \n" +
                "     this.x = 1;\n" +
                "  }\n" +
                "  def myMethod() { this.y = this.x + 3; }\n" +
                "}\n" +
                "function fn() {\n" +
                "  let a = new MyClass();\n" +
                "  let b = new MyClass();\n" +
                "  a.myMethod();\n" +
                "}\n" +
                "fn();\n";

        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        assertNotNull(snapshot, "snapshot must not be null");

        // After fn() returns, a/b are out of scope. The observable artifact is live instance tracking.
        List<Map<String, Value>> classInstances = extractClassInstances(snapshot);

        List<Map<String, Value>> myClass = classInstances.stream()
                .filter(ci -> "MyClass".equals(ci.get("className").asString()))
                .collect(Collectors.toList());

        assertEquals(2, myClass.size(), "expected exactly 2 MyClass instances in snapshot.class_instances");

        // Each must have uuid and state map
        for (Map<String, Value> ci : myClass) {
            assertNotNull(ci.get("uuid"), "uuid must exist");
            assertEquals(Value.Type.STRING, ci.get("uuid").getType(), "uuid must be STRING");

            assertNotNull(ci.get("state"), "state must exist");
            assertEquals(Value.Type.MAP, ci.get("state").getType(), "state must be MAP");

            Map<String, Value> state = ci.get("state").asMap();
            assertNotNull(state.get("x"), "state.x must exist");
            assertNotNull(state.get("y"), "state.y must exist");
        }

        // Validate effects:
        // constructor sets x=1 for both instances
        // only ONE instance had myMethod called, so exactly ONE should have y=4, the other y=0
        int y4 = 0;
        int y0 = 0;

        for (Map<String, Value> ci : myClass) {
            Map<String, Value> state = ci.get("state").asMap();
            assertEquals(1.0, state.get("x").asNumber(), 0.0);

            double y = state.get("y").asNumber();
            if (y == 4.0) y4++;
            else if (y == 0.0) y0++;
            else fail("Unexpected y value: " + y);
        }

        assertEquals(1, y4, "exactly one instance should have y=4");
        assertEquals(1, y0, "exactly one instance should have y=0");
    }

    // ---------------- helpers ----------------

    private static List<Map<String, Value>> extractClassInstances(Map<String, Value> snapshot) {
        Value cisV = snapshot.get("class_instances");
        assertNotNull(cisV, "snapshot must contain class_instances");
        assertEquals(Value.Type.ARRAY, cisV.getType(), "class_instances must be ARRAY");

        List<Value> cis = cisV.asArray();
        List<Map<String, Value>> out = new ArrayList<>(cis.size());
        for (Value v : cis) {
            assertEquals(Value.Type.MAP, v.getType(), "class_instance entry must be MAP");
            out.add(v.asMap());
        }
        return out;
    }
}
