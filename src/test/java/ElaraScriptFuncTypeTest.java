import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.parser.utils.SnapshotUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the FUNC Value type.
 *
 * FUNC contract:
 * - Backed by String (function name)
 * - typeof(FUNC) == "function"
 * - Identifier reference resolves to FUNC if function exists
 * - Variables shadow functions
 * - Equality compares underlying names
 * - NOT a string:
 *     - '+' is NOT allowed
 *     - len() is NOT allowed
 * - STRICT mode: FUNC variables are NOT callable
 */
public class ElaraScriptFuncTypeTest {

	private static Value v(Map<String, Value> snapshot, String name) {
	    Map<String, Value> env = SnapshotUtils.mergedVars(snapshot);
	    Value val = env.get(name);
	    assertNotNull(val, "Expected variable in env: " + name);
	    return val;
	}

    private static boolean hasVar(Map<String, Value> snapshot, String name) {
        return lookupVar(snapshot, name) != null;
    }

    /**
     * Resolve a variable from the snapshot by searching frames from inner -> outer.
     * Snapshot shape (current):
     *   environments: [ { vars: {...}, this: {...}, ... }, ... ]
     */
    private static Value lookupVar(Map<String, Value> snapshot, String name) {
        if (snapshot == null) return null;

        // Backward-compat: if it's already a flat env, allow direct access
        if (snapshot.containsKey(name)) return snapshot.get(name);

        Value envsV = snapshot.get("environments");
        if (envsV == null || envsV.getType() != Value.Type.ARRAY) return null;

        List<Value> frames = envsV.asArray();
        if (frames == null) return null;

        for (int i = frames.size() - 1; i >= 0; i--) {
            Value frameV = frames.get(i);
            if (frameV == null || frameV.getType() != Value.Type.MAP) continue;
            Map<String, Value> frame = frameV.asMap();
            if (frame == null) continue;

            Value varsV = frame.get("vars");
            if (varsV == null || varsV.getType() != Value.Type.MAP) continue;
            Map<String, Value> vars = varsV.asMap();
            if (vars == null) continue;

            if (vars.containsKey(name)) return vars.get(name);
        }
        return null;
    }

    @Test
    public void funcIdentifier_AssignsFuncType_WhenFunctionExists() {
        ElaraScript es = new ElaraScript();

        String src =
                "function my_function(a) { return a; }\n" +
                "let x = my_function;\n";

        Map<String, Value> snapshot = es.run(src);
        Value x = v(snapshot, "x");

        assertEquals(Value.Type.FUNC, x.getType());
        assertEquals("my_function", x.asString());
    }

    @Test
    public void funcIdentifier_UnknownFunction_ThrowsUndefinedVariable() {
        ElaraScript es = new ElaraScript();

        String src = "let x = does_not_exist;\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        assertTrue(ex.getMessage().toLowerCase().contains("undefined variable"));
    }

    @Test
    public void variableShadowsFunction_NameResolvesToVariable() {
        ElaraScript es = new ElaraScript();

        String src =
                "function my_function(a) { return a; }\n" +
                "let my_function = \"hello\";\n" +
                "let x = my_function;\n";

        Map<String, Value> snapshot = es.run(src);
        Value x = v(snapshot, "x");

        assertEquals(Value.Type.STRING, x.getType());
        assertEquals("hello", x.asString());
    }

    @Test
    public void typeof_OnFunc_ReturnsFunction() {
        ElaraScript es = new ElaraScript();

        String src =
                "function foo(a) { return a; }\n" +
                "let f = foo;\n" +
                "let t = typeof(f);\n";

        Map<String, Value> snapshot = es.run(src);
        Value t = v(snapshot, "t");

        assertEquals("function", t.asString());
    }

    @Test
    public void len_OnFunc_IsRejected() {
        ElaraScript es = new ElaraScript();

        String src =
                "function abc(a) { return a; }\n" +
                "let f = abc;\n" +
                "let n = len(f);\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        assertTrue(
                ex.getMessage().toLowerCase().contains("len")
                        || ex.getMessage().toLowerCase().contains("not supported"),
                "Expected len(FUNC) to be rejected, got: " + ex.getMessage()
        );
    }

    @Test
    public void funcEquality_ComparesUnderlyingNames() {
        ElaraScript es = new ElaraScript();

        String src =
                "function foo(a) { return a; }\n" +
                "function bar(a) { return a; }\n" +
                "let a = foo;\n" +
                "let b = bar;\n" +
                "let c = foo;\n" +
                "let eq_ab = (a == b);\n" +
                "let eq_ac = (a == c);\n";

        Map<String, Value> snapshot = es.run(src);

        assertFalse(v(snapshot, "eq_ab").asBool());
        assertTrue(v(snapshot, "eq_ac").asBool());
    }

    @Test
    public void plusOperator_DoesNotAllowFuncConcatenation() {
        ElaraScript es = new ElaraScript();

        final StringBuilder lastMsg = new StringBuilder();

        es.registerFunction("event_system_error", args -> {
            lastMsg.setLength(0);
            if (args != null && args.size() >= 5 && args.get(4) != null) {
                lastMsg.append(args.get(4).asString());
            }
            return Value.nil();
        });

        es.setErrorCallback("event_system_error");

        String src =
                "function foo(a) { return a; }\n" +
                "let f = foo;\n" +
                "let s = \"x\" + f;\n";

        boolean threw = false;
        try {
            Map<String, Value> snapshot = es.run(src);

            // Suppressed mode: should not bind 's' successfully.
            assertFalse(hasVar(snapshot, "s"), "s should not be defined when '+' fails");
            assertTrue(lastMsg.length() > 0, "Expected system error callback to be invoked");
        } catch (RuntimeException ex) {
            threw = true;
            String msg = (ex.getMessage() == null) ? "" : ex.getMessage().toLowerCase();
            assertTrue(msg.contains("unsupported") || msg.contains("operand") || msg.contains("+"),
                    "Expected '+' to reject FUNC concatenation, got: " + ex.getMessage());
        }

        assertTrue(threw || lastMsg.length() > 0);
    }

    @Test
    public void strictMode_FuncVariable_IsNotCallable() {
        ElaraScript es = new ElaraScript(); // STRICT by default

        String src =
                "function foo(a) { return a; }\n" +
                "let f = foo;\n" +
                "let r = f(7);\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        String msg = (ex.getMessage() == null) ? "" : ex.getMessage().toLowerCase();
        assertTrue(
                msg.contains("unknown function") || msg.contains("fn_not_found") || msg.contains("not callable"),
                "Expected STRICT mode to reject calling FUNC variable, got: " + ex.getMessage()
        );
    }
}
