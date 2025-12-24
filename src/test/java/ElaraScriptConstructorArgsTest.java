import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.HashMap;
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
        Map<String, Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env);
        assertTrue(env.containsKey("a"));
        assertEquals(Value.Type.CLASS_INSTANCE, env.get("a").getType());
    }
}
