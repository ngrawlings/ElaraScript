import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptNewInstantiationTest {

    @Test
    public void newCreatesInstanceHandleAndStateMapInEnv() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def myMethod() { return 1; }\n" +
                "}\n" +
                "let a = new MyClass();\n" +
                "let b = new MyClass();\n";

        Map<String, Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env, "env snapshot must not be null");

        // 1) a and b exist
        assertTrue(env.containsKey("a"), "a must exist in env snapshot");
        assertTrue(env.containsKey("b"), "b must exist in env snapshot");

        Value aVal = env.get("a");
        Value bVal = env.get("b");
        assertNotNull(aVal, "a must not be null");
        assertNotNull(bVal, "b must not be null");

        // 2) a and b must be CLASS_INSTANCE
        assertEquals(Value.Type.CLASS_INSTANCE, aVal.getType(), "a must be CLASS_INSTANCE");
        assertEquals(Value.Type.CLASS_INSTANCE, bVal.getType(), "b must be CLASS_INSTANCE");

        // 3) Extract (className, uuid) payload from both instances
        InstanceRef aRef = extractInstanceRef(aVal);
        InstanceRef bRef = extractInstanceRef(bVal);

        assertEquals("MyClass", aRef.className, "a.className must be MyClass");
        assertEquals("MyClass", bRef.className, "b.className must be MyClass");

        assertNotNull(aRef.uuid, "a.uuid must not be null");
        assertNotNull(bRef.uuid, "b.uuid must not be null");
        assertFalse(aRef.uuid.isEmpty(), "a.uuid must not be empty");
        assertFalse(bRef.uuid.isEmpty(), "b.uuid must not be empty");
        assertNotEquals(aRef.uuid, bRef.uuid, "a and b must have different UUIDs");

        // 4) Verify env contains state maps under MyClass.<uuid>
        String aKey = aRef.className + "." + aRef.uuid;
        String bKey = bRef.className + "." + bRef.uuid;

        assertTrue(env.containsKey(aKey), "env must contain state map for a: " + aKey);
        assertTrue(env.containsKey(bKey), "env must contain state map for b: " + bKey);

        Value aState = env.get(aKey);
        Value bState = env.get(bKey);
        assertNotNull(aState, "a state map value must not be null");
        assertNotNull(bState, "b state map value must not be null");

        // If your MAP type exists, assert it here:
        // (If your engine uses a different enum name, update accordingly.)
        assertEquals(Value.Type.MAP, aState.getType(), "a state must be MAP");
        assertEquals(Value.Type.MAP, bState.getType(), "b state must be MAP");

        // 5) Sanity: there should be exactly 2 MyClass.<uuid> state keys
        List<String> stateKeys = env.keySet().stream()
                .filter(k -> k.startsWith("MyClass."))
                .collect(Collectors.toList());

        assertEquals(2, stateKeys.size(),
                "env must contain exactly 2 MyClass.<uuid> state entries, found: " + stateKeys);
    }

    /**
     * Lightweight extracted view of Value.ClassInstance without relying on public accessors.
     */
    private static final class InstanceRef {
        final String className;
        final String uuid;

        InstanceRef(String className, String uuid) {
            this.className = className;
            this.uuid = uuid;
        }
    }

    /**
     * Extracts Value.ClassInstance payload via reflection so the test survives refactors.
     *
     * Assumptions:
     * - Value holds payload in a field named "value" OR similar.
     * - Payload type is Value.ClassInstance with fields "className" and "uuid".
     */
    private static InstanceRef extractInstanceRef(Value v) {
        Object payload = extractPayloadObject(v);
        assertNotNull(payload, "CLASS_INSTANCE payload must not be null");

        try {
            Field classNameF = payload.getClass().getDeclaredField("className");
            Field uuidF = payload.getClass().getDeclaredField("uuid");
            classNameF.setAccessible(true);
            uuidF.setAccessible(true);

            Object cn = classNameF.get(payload);
            Object id = uuidF.get(payload);

            assertTrue(cn instanceof String, "payload.className must be a String");
            assertTrue(id instanceof String, "payload.uuid must be a String");

            return new InstanceRef((String) cn, (String) id);
        } catch (NoSuchFieldException e) {
            fail("Value.ClassInstance must have fields 'className' and 'uuid'. Found different structure: " + payload.getClass(), e);
        } catch (IllegalAccessException e) {
            fail("Could not access Value.ClassInstance fields via reflection.", e);
        }
        return null; // unreachable
    }

    /**
     * Tries to pull the internal payload object out of Value.
     * Update the field name here if your Value uses something other than "value".
     */
    private static Object extractPayloadObject(Value v) {
        // Common patterns: "value", "raw", "data"
        String[] candidates = new String[] {"value", "raw", "data"};

        for (String fieldName : candidates) {
            try {
                Field f = Value.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(v);
            } catch (NoSuchFieldException ignored) {
                // try next
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Unable to access Value payload field: " + fieldName, e);
            }
        }

        fail("Could not find a payload field on Value. Tried: " + Arrays.toString(candidates));
        return null; // unreachable
    }
}
