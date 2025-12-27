import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptConstructorArgsTest {

    @Test
    public void newPassesArgsToConstructor() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def constructor(x, y) {\n" +
                "    if (x != 7) { return fail(); }\n" +
                "    if (y != 9) { return fail(); }\n" +
                "    return this;\n" +
                "  }\n" +
                "  def fail() { return unknownVar; }\n" + // forces runtime error if called
                "}\n" +
                "let a = new MyClass(7, 9);\n";

        // If args are not passed correctly, constructor calls fail() and crashes.
        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        assertNotNull(snapshot);

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("a"), "a should exist");
        assertEquals(Value.Type.CLASS_INSTANCE, vars.get("a").getType(), "a must be CLASS_INSTANCE");
    }

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
