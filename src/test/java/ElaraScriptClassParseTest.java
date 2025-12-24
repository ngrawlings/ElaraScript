import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptClassParseTest {

    @Test
    public void parsesClassButDoesNotSnapshotItIntoEnv() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def myMethod(a, b) {\n" +
                "    return a + b;\n" +
                "  }\n" +
                "}\n" +
                "let x = 42;\n";

        Map<String, ElaraScript.Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env, "env snapshot must not be null");

        // Normal vars still snapshot
        assertTrue(env.containsKey("x"), "x must be present in env snapshot");
        assertNotNull(env.get("x"), "x value must not be null");

        // Classes must NOT be in the snapshot (by design)
        assertFalse(env.containsKey("MyClass"),
                "MyClass must NOT be stored in env snapshot (classes are non-flat / too expensive)");
    }

    @Test
    public void parsesMultipleMethodsInClassBody() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def a() { return 1; }\n" +
                "  def b() { return 2; }\n" +
                "  def c() { return 3; }\n" +
                "}\n" +
                "let ok = true;\n";

        Map<String, ElaraScript.Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env);
        assertTrue(env.containsKey("ok"), "ok must be present in env snapshot");
        assertFalse(env.containsKey("MyClass"), "MyClass must NOT be snapshot into env");
    }
}
