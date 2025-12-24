import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

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

    private static Value v(Map<String, Value> env, String name) {
        Value out = env.get(name);
        assertNotNull(out, "Missing env var: " + name);
        return out;
    }

    @Test
    public void funcIdentifier_AssignsFuncType_WhenFunctionExists() {
        ElaraScript es = new ElaraScript();

        String src =
                "function my_function(a) { return a; }\n" +
                "let x = my_function;\n";

        Map<String, Value> env = es.run(src);
        Value x = v(env, "x");

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

        Map<String, Value> env = es.run(src);
        Value x = v(env, "x");

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

        Map<String, Value> env = es.run(src);
        Value t = v(env, "t");

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

        Map<String, Value> env = es.run(src);

        assertFalse(v(env, "eq_ab").asBool());
        assertTrue(v(env, "eq_ac").asBool());
    }

    @Test
    public void plusOperator_DoesNotAllowFuncConcatenation() {
        ElaraScript es = new ElaraScript();

        // Capture errors if callback mode is active (or becomes active).
        final StringBuilder lastMsg = new StringBuilder();

        es.registerFunction("event_system_error", args -> {
            // args: kind, nameOrNull, functionOrNull, lineOrNull, message
            lastMsg.setLength(0);
            lastMsg.append(args.get(4).asString());
            return Value.nil();
        });

        // If your environment later enables callback suppression, this will be used.
        // If it doesn't, the exception will throw and we still pass.
        es.setErrorCallback("event_system_error");

        String src =
                "function foo(a) { return a; }\n" +
                "let f = foo;\n" +
                "let s = \"x\" + f;\n";

        boolean threw = false;
        try {
            Map<String, Value> env = es.run(src);

            // Suppressed mode: must NOT define 's'
            assertFalse(env.containsKey("s"), "s should not be defined when '+' fails");

            // And we should have received an error message
            assertTrue(lastMsg.length() > 0, "Expected system error callback to be invoked");
        } catch (RuntimeException ex) {
            threw = true;
            // Throwing mode: ensure it’s the expected failure type (be tolerant about message)
            String msg = (ex.getMessage() == null) ? "" : ex.getMessage().toLowerCase();
            assertTrue(msg.contains("unsupported") || msg.contains("operand") || msg.contains("+"),
                    "Expected '+' to reject FUNC concatenation, got: " + ex.getMessage());
        }

        // Sanity: at least one of the paths happened (always true, but keeps intent obvious)
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
        assertTrue(
                ex.getMessage().toLowerCase().contains("unknown function")
                        || ex.getMessage().toLowerCase().contains("fn_not_found"),
                "Expected STRICT mode to reject calling FUNC variable, got: " + ex.getMessage()
        );
    }
}
