// ElaraScriptFreeTest.java
// JUnit 5 tests for the `free` keyword.
//
// These tests assume:
// - `free <expr>;` exists as a statement.
// - Instances store state in env under key: "<ClassName>.<uuid>" (via ClassInstance.stateKey()).
// - `free` calls optional `on_free()` method on the class (with `this` injected), then removes that state key.
// - ElaraScript.run(String) returns the final env snapshot (Map<String, ElaraScript.Value>).

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptFreeTest {

    private static boolean hasStateKey(Map<String, Value> env, String className) {
        String prefix = className + ".";
        for (String k : env.keySet()) {
            if (k.startsWith(prefix)) return true;
        }
        return false;
    }

    @Test
    public void free_callsOnFree_and_removesInstanceState() {
        ElaraScript es = new ElaraScript();

        String src =
                "setglobal(\"freed\", false);" +
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
                "free x;\n";

        Map<String, Value> env = es.run(src);

        // on_free side-effect happened
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

        Map<String, Value> env = es.run(src);

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
        // message may vary slightly; keep it permissive but meaningful
        assertTrue(
                ex.getMessage().toLowerCase().contains("free"),
                "Expected error mentioning free(), got: " + ex.getMessage()
        );
    }
}
