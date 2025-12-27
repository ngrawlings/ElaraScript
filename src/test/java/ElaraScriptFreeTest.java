// ElaraScriptFreeTest.java
// JUnit 5 tests for the `free` keyword.
//
// Updated for new snapshot format:
// - ElaraScript.run(String) returns a snapshot map containing:
//     - "environments": ARRAY<MAP{vars:MAP,...}>
//     - "class_instances": ARRAY<...>
// - Globals are now in ExecutionState.global (not returned in snapshot),
//   so tests copy global values into env via `let x = getglobal("x");`.

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptFreeTest {

    /** Merge vars from snapshot["environments"] outer->inner (inner overrides). */
    private static Map<String, Value> mergedVars(Map<String, Value> snapshot) {
        Map<String, Value> merged = new LinkedHashMap<>();
        if (snapshot == null) return merged;

        Value envsV = snapshot.get("environments");
        if (envsV == null || envsV.getType() != Value.Type.ARRAY || envsV.asArray() == null) return merged;

        List<Value> frames = envsV.asArray();
        for (Value frameV : frames) {
            if (frameV == null || frameV.getType() != Value.Type.MAP || frameV.asMap() == null) continue;

            Map<String, Value> frame = frameV.asMap();
            Value varsV = frame.get("vars");
            if (varsV == null || varsV.getType() != Value.Type.MAP || varsV.asMap() == null) continue;

            merged.putAll(varsV.asMap());
        }
        return merged;
    }

    private static boolean hasStateKey(Map<String, Value> flatEnv, String className) {
        String prefix = className + ".";
        for (String k : flatEnv.keySet()) {
            if (k.startsWith(prefix)) return true;
        }
        return false;
    }

    @Test
    public void free_callsOnFree_and_removesInstanceState() {
        ElaraScript es = new ElaraScript();

        String src =
                "setglobal(\"freed\", false);\n" +
                "class Foo {\n" +
                "  def constructor() {\n" +
                "    // nothing\n" +
                "  }\n" +
                "  def on_free() {\n" +
                "    // prove on_free ran\n" +
                "    setglobal(\"freed\", 1);\n" +
                "  }\n" +
                "}\n" +
                "let x = new Foo();\n" +
                "free x;\n" +
                // pull global into env so snapshot can assert it
                "let freed = getglobal(\"freed\");\n";

        Map<String, Value> snap = es.run(src);
        Map<String, Value> env = mergedVars(snap);

        // on_free side-effect happened (via getglobal -> env var)
        assertTrue(env.containsKey("freed"), "Expected env to contain `freed` (copied from globals via getglobal)");
        assertEquals(Value.Type.NUMBER, env.get("freed").getType());
        assertEquals(1.0, env.get("freed").asNumber(), 0.0);

        // instance state removed
        assertFalse(hasStateKey(env, "Foo"), "Expected Foo.<uuid> state key to be removed after free");
    }

    @Test
    public void free_withoutOnFree_still_removesInstanceState() {
        ElaraScript es = new ElaraScript();

        String src =
                "class Bar {\n" +
                "  def constructor() { }\n" +
                "}\n" +
                "let b = new Bar();\n" +
                "free b;\n";

        Map<String, Value> snap = es.run(src);
        Map<String, Value> env = mergedVars(snap);

        // no on_free, but state must still be removed
        assertFalse(hasStateKey(env, "Bar"), "Expected Bar.<uuid> state key to be removed after free");
    }

    @Test
    public void free_onNonInstance_throws() {
        ElaraScript es = new ElaraScript();

        String src =
                "let x = 123;\n" +
                "free x;\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));

        assertTrue(
                ex.getMessage() != null && ex.getMessage().toLowerCase().contains("free"),
                "Expected error mentioning free, got: " + ex.getMessage()
        );
    }
}
