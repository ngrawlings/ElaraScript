import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.LinkedHashMap;
import java.util.List;
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

        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("a"));
        assertTrue(vars.containsKey("b"));

        Value aVal = vars.get("a");
        Value bVal = vars.get("b");

        assertEquals(Value.Type.CLASS_INSTANCE, aVal.getType());
        assertEquals(Value.Type.CLASS_INSTANCE, bVal.getType());

        // Compare instance identity by (className, uuid)
        Value.ClassInstance ar = aVal.asClassInstance();
        Value.ClassInstance br = bVal.asClassInstance();

        assertEquals(ar.className, br.className);
        assertEquals(ar.uuid, br.uuid);
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

        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        Map<String, Value> vars = extractInnermostVars(snapshot);

        Value aVal = vars.get("a");
        Value bVal = vars.get("b");
        Value raVal = vars.get("ra");
        Value rbVal = vars.get("rb");

        assertEquals(Value.Type.CLASS_INSTANCE, aVal.getType());
        assertEquals(Value.Type.CLASS_INSTANCE, bVal.getType());
        assertEquals(Value.Type.CLASS_INSTANCE, raVal.getType());
        assertEquals(Value.Type.CLASS_INSTANCE, rbVal.getType());

        Value.ClassInstance a = aVal.asClassInstance();
        Value.ClassInstance b = bVal.asClassInstance();
        Value.ClassInstance ra = raVal.asClassInstance();
        Value.ClassInstance rb = rbVal.asClassInstance();

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

        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );
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

        Map<String, Value> snapshot = assertDoesNotThrow(() ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );

        Map<String, Value> vars = extractInnermostVars(snapshot);

        assertTrue(vars.containsKey("v"));
        Value vVal = vars.get("v");

        assertEquals(Value.Type.NUMBER, vVal.getType());
        assertEquals(123.0, vVal.asNumber(), 0.0);
    }

    @Test
    public void dotMethodCallOnNonInstanceThrows() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass { def self(){ return this; } }\n" +
                "let x = 5;\n" +
                "let y = x.self();\n";

        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );
    }

    @Test
    public void unknownMethodThrows() {
        ElaraScript es = new ElaraScript();

        String src =
                "class MyClass { def self(){ return this; } }\n" +
                "let a = new MyClass();\n" +
                "let y = a.nope();\n";

        assertThrows(RuntimeException.class, () ->
                es.run(src, new LinkedHashMap<>(), new LinkedHashMap<>())
        );
    }

    // ---------------- helpers ----------------

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
