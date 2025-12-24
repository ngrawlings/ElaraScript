import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptMethodThisTest {

    @Test
    public void methodHasThisInScopeAndCanReturnIt() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def self() { return this; }\n" +
                "}\n" +
                "let a = new MyClass();\n" +
                "let b = a.self();\n";

        Map<String, ElaraScript.Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertNotNull(env);
        assertTrue(env.containsKey("a"));
        assertTrue(env.containsKey("b"));

        ElaraScript.Value aVal = env.get("a");
        ElaraScript.Value bVal = env.get("b");

        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, aVal.getType());
        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, bVal.getType());

        // Compare instance identity by extracting (className, uuid)
        InstanceRef ar = extractInstanceRef(aVal);
        InstanceRef br = extractInstanceRef(bVal);

        assertEquals(ar.className, br.className);
        assertEquals(ar.uuid, br.uuid);
    }

    private static final class InstanceRef {
        final String className;
        final String uuid;
        InstanceRef(String className, String uuid) { this.className = className; this.uuid = uuid; }
    }

    private static InstanceRef extractInstanceRef(ElaraScript.Value v) {
        Object payload = extractPayloadObject(v);
        assertNotNull(payload);

        try {
            Field classNameF = payload.getClass().getDeclaredField("className");
            Field uuidF = payload.getClass().getDeclaredField("uuid");
            classNameF.setAccessible(true);
            uuidF.setAccessible(true);
            return new InstanceRef((String) classNameF.get(payload), (String) uuidF.get(payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object extractPayloadObject(ElaraScript.Value v) {
        String[] candidates = {"value", "raw", "data"};
        for (String fName : candidates) {
            try {
                Field f = ElaraScript.Value.class.getDeclaredField(fName);
                f.setAccessible(true);
                return f.get(v);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        fail("Could not find payload field on Value (tried value/raw/data).");
        return null;
    }
    
    @Test
    public void thisIsPerInstanceNotGlobal() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def self() { return this; }\n" +
                "}\n" +
                "let a = new MyClass();\n" +
                "let b = new MyClass();\n" +
                "let ra = a.self();\n" +
                "let rb = b.self();\n";

        Map<String, ElaraScript.Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        ElaraScript.Value aVal = env.get("a");
        ElaraScript.Value bVal = env.get("b");
        ElaraScript.Value raVal = env.get("ra");
        ElaraScript.Value rbVal = env.get("rb");

        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, aVal.getType());
        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, bVal.getType());
        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, raVal.getType());
        assertEquals(ElaraScript.Value.Type.CLASS_INSTANCE, rbVal.getType());

        InstanceRef a = extractInstanceRef(aVal);
        InstanceRef b = extractInstanceRef(bVal);
        InstanceRef ra = extractInstanceRef(raVal);
        InstanceRef rb = extractInstanceRef(rbVal);

        // ra must be same instance as a
        assertEquals(a.className, ra.className);
        assertEquals(a.uuid, ra.uuid);

        // rb must be same instance as b
        assertEquals(b.className, rb.className);
        assertEquals(b.uuid, rb.uuid);

        // and a/b must be different instances
        assertNotEquals(a.uuid, b.uuid);
    }

    @Test
    public void thisIsNotDefinedAtTopLevel() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass { def self() { return this; } }\n" +
                "let x = this;\n";

        assertThrows(RuntimeException.class, () -> es.run(src, new HashMap<>()));
    }
    
    @Test
    public void parametersBindNormallyWithThisInjected() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass {\n" +
                "  def pick(x) { return x; }\n" +
                "}\n" +
                "let a = new MyClass();\n" +
                "let v = a.pick(123);\n";

        Map<String, ElaraScript.Value> env = assertDoesNotThrow(() ->
                es.run(src, new HashMap<>())
        );

        assertTrue(env.containsKey("v"));
        ElaraScript.Value vVal = env.get("v");

        // adjust if your numbers are doubles; this is a safe check:
        assertEquals(ElaraScript.Value.Type.NUMBER, vVal.getType());
        assertEquals(123.0, vVal.asNumber(), 0.0);
    }
    
    @Test
    public void dotMethodCallOnNonInstanceThrows() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass { def self(){ return this; } }\n" +
                "let x = 5;\n" +
                "let y = x.self();\n";

        assertThrows(RuntimeException.class, () -> es.run(src, new HashMap<>()));
    }

    @Test
    public void unknownMethodThrows() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass { def self(){ return this; } }\n" +
                "let a = new MyClass();\n" +
                "let y = a.nope();\n";

        assertThrows(RuntimeException.class, () -> es.run(src, new HashMap<>()));
    }

}
