import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptClassBodyParseTest {

    @Test
    public void classBodyCollectsDefMethodsIntoDescriptor() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def a() { return 1; }\n" +
                "  def b(x) { return x; }\n" +
                "  def self() { return this; }\n" +
                "}\n" +
                "let ok = true;\n";

        // NEW: run signature is (source, liveInstances, initialEnv)
        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        assertNotNull(snapshot);

        // Snapshot shape: { environments: [...], class_instances: [...] }
        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("ok"), "ok should exist (sanity check)");

        // Since classes are now stored in env vars:
        assertTrue(vars.containsKey("MyClass"), "MyClass descriptor should be stored in env vars");

        Value clsVal = vars.get("MyClass");
        assertNotNull(clsVal, "MyClass value must not be null");
        assertEquals(Value.Type.CLASS, clsVal.getType(), "MyClass must be CLASS type");

        Value.ClassDescriptor desc = clsVal.asClass();
        assertNotNull(desc, "CLASS descriptor payload must not be null");

        // core assertion: class parser must have collected all defs
        LinkedHashMap<String, Object> methods = desc.methods;

        assertTrue(methods.containsKey("a"), "methods must contain 'a'");
        assertTrue(methods.containsKey("b"), "methods must contain 'b'");
        assertTrue(methods.containsKey("self"), "methods must contain 'self'");

        assertEquals(3, methods.size(), "expected exactly 3 methods in descriptor");
    }

    /**
     * Snapshot format:
     *  snapshot["environments"] = ARRAY of MAP frames
     *  each frame["vars"] = MAP of variables
     *
     * Environment.snapshotFrames() returns frames outer->inner, and it also
     * prepends a synthetic global frame at index 0.
     *
     * The program variables we care about should be in the innermost frame.
     */
    private static Map<String, Value> extractInnermostVars(Map<String, Value> snapshot) {
        Value envsV = snapshot.get("environments");
        assertNotNull(envsV, "snapshot must contain environments");
        assertEquals(Value.Type.ARRAY, envsV.getType(), "environments must be ARRAY");

        List<Value> frames = envsV.asArray();
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
