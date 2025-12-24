import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.lang.reflect.Field;
import java.util.*;

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

        Map<String, Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env);
        assertTrue(env.containsKey("ok"), "ok should exist (sanity check)");

        // Since classes are now stored in env:
        assertTrue(env.containsKey("MyClass"), "MyClass descriptor should be stored in env");

        Value clsVal = env.get("MyClass");
        assertNotNull(clsVal, "MyClass value must not be null");
        assertEquals(Value.Type.CLASS, clsVal.getType(), "MyClass must be CLASS type");

        Object desc = extractPayloadObject(clsVal);
        assertNotNull(desc, "CLASS descriptor payload must not be null");

        // reflect into Value.ClassDescriptor.methods
        LinkedHashMap<String, Object> methods = extractMethodsMap(desc);

        // core assertion: class parser must have collected all defs
        assertTrue(methods.containsKey("a"), "methods must contain 'a'");
        assertTrue(methods.containsKey("b"), "methods must contain 'b'");
        assertTrue(methods.containsKey("self"), "methods must contain 'self'");

        // optional: count sanity
        assertEquals(3, methods.size(), "expected exactly 3 methods in descriptor");
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> extractMethodsMap(Object desc) {
        try {
            Field f = desc.getClass().getDeclaredField("methods");
            f.setAccessible(true);
            Object v = f.get(desc);
            assertNotNull(v, "descriptor.methods must not be null");
            assertTrue(v instanceof LinkedHashMap, "descriptor.methods must be a LinkedHashMap");
            return (LinkedHashMap<String, Object>) v;
        } catch (NoSuchFieldException e) {
            fail("ClassDescriptor must have a field named 'methods'", e);
        } catch (IllegalAccessException e) {
            fail("Unable to access ClassDescriptor.methods via reflection", e);
        }
        return null; // unreachable
    }

    /**
     * Extract internal payload from Value without depending on a specific accessor name.
     * Add more field candidates if your Value uses another name.
     */
    private static Object extractPayloadObject(Value v) {
        String[] candidates = {"value", "raw", "data"};
        for (String fName : candidates) {
            try {
                Field f = Value.class.getDeclaredField(fName);
                f.setAccessible(true);
                return f.get(v);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Unable to access Value payload field: " + fName, e);
            }
        }
        fail("Could not find payload field on Value. Tried: " + Arrays.toString(candidates));
        return null; // unreachable
    }
}
