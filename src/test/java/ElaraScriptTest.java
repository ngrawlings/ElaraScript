import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the core ElaraScript language.
 *
 * Assumptions:
 * - Uses JUnit 5 (org.junit.jupiter)
 * - ElaraScript is in the default package (same as this test)
 */
public class ElaraScriptTest {

    private static ElaraScript.Value v(Map<String, ElaraScript.Value> env, String name) {
        ElaraScript.Value val = env.get(name);
        assertNotNull(val, "Expected variable in env: " + name);
        return val;
    }

    @Test
    void letAndAssignment_number() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let x = 10;
            x = x + 5;
            let y = x;
        """);

        assertEquals(15.0, v(env, "x").asNumber(), 1e-9);
        assertEquals(15.0, v(env, "y").asNumber(), 1e-9);
    }

    @Test
    void arithmetic_precedence() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let a = 2 + 3 * 4;
            let b = (2 + 3) * 4;
            let c = 10 / 2 + 6;
            let d = 10 / (2 + 3);
            let e = 10 % 4;
        """);

        assertEquals(14.0, v(env, "a").asNumber(), 1e-9);
        assertEquals(20.0, v(env, "b").asNumber(), 1e-9);
        assertEquals(11.0, v(env, "c").asNumber(), 1e-9);
        assertEquals(2.0, v(env, "d").asNumber(), 1e-9);
        assertEquals(2.0, v(env, "e").asNumber(), 1e-9);
    }

    @Test
    void comparisonsAndEquality() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let a = 3;
            let b = 5;
            let lt = a < b;
            let le = a <= 3;
            let gt = b > a;
            let ge = b >= 5;
            let eq = (a + 2) == b;
            let ne = a != b;
        """);

        assertTrue(v(env, "lt").asBool());
        assertTrue(v(env, "le").asBool());
        assertTrue(v(env, "gt").asBool());
        assertTrue(v(env, "ge").asBool());
        assertTrue(v(env, "eq").asBool());
        assertTrue(v(env, "ne").asBool());
    }

    @Test
    void logicalOperators_shortCircuit_or_and() {
        ElaraScript es = new ElaraScript();

        // OR short-circuits: unknown function should not be evaluated.
        Map<String, ElaraScript.Value> env1 = es.run("""
            let ok = true || doesNotExist(1);
        """);
        assertTrue(v(env1, "ok").asBool());

        // AND short-circuits: unknown function should not be evaluated.
        Map<String, ElaraScript.Value> env2 = es.run("""
            let ok = false && doesNotExist(1);
        """);
        assertFalse(v(env2, "ok").asBool());

        // Evaluate right-hand side when needed.
        assertThrows(RuntimeException.class, () -> es.run("""
            let bad = true && doesNotExist(1);
        """));
    }

    @Test
    void unaryOperators_not_and_negation() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let a = !false;
            let b = !(1);
            let c = -5;
            let d = -(2 + 3);
        """);

        assertTrue(v(env, "a").asBool());
        assertFalse(v(env, "b").asBool());
        assertEquals(-5.0, v(env, "c").asNumber(), 1e-9);
        assertEquals(-5.0, v(env, "d").asNumber(), 1e-9);
    }

    @Test
    void ifElse_blocks_and_scoping() {
        ElaraScript es = new ElaraScript();

        // Variables defined inside blocks should not leak outward.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = 2;
            if (x > 1) {
                let inner = 42;
            }
            let y = inner;
        """));
        assertTrue(ex.getMessage().contains("Undefined variable"));

        // But assigning an outer variable inside a block should work.
        Map<String, ElaraScript.Value> env = es.run("""
            let x = 2;
            let y = 0;
            if (x > 1) {
                y = 10;
            } else {
                y = 20;
            }
        """);
        assertEquals(10.0, v(env, "y").asNumber(), 1e-9);
    }

    @Test
    void whileLoop_basic_accumulator() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let i = 0;
            let total = 0;
            while (i < 5) {
                total = total + i;
                i = i + 1;
            }
        """);

        // 0 + 1 + 2 + 3 + 4 = 10
        assertEquals(5.0, v(env, "i").asNumber(), 1e-9);
        assertEquals(10.0, v(env, "total").asNumber(), 1e-9);
    }

    @Test
    void arrays_literal_index_len() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let v = [10, 20, 30];
            let a = v[0];
            let b = v[2];
            let n = len(v);
        """);

        assertEquals(10.0, v(env, "a").asNumber(), 1e-9);
        assertEquals(30.0, v(env, "b").asNumber(), 1e-9);
        assertEquals(3.0, v(env, "n").asNumber(), 1e-9);
    }

    @Test
    void arrayIndex_outOfBounds_throws() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let v = [1, 2];
            let x = v[2];
        """));
        assertTrue(ex.getMessage().toLowerCase().contains("out of bounds"));
    }

    @Test
    void strings_concat_and_len() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let s = "hello";
            let t = "world";
            let u = s + ", " + t;
            let n = len(u);
        """);

        assertEquals("hello, world", v(env, "u").asString());
        assertEquals(12.0, v(env, "n").asNumber(), 1e-9);
    }

    @Test
    void comments_lineComment_ignored() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            // this is a comment
            let x = 1; // trailing comment
            let y = x + 1;
        """);
        assertEquals(2.0, v(env, "y").asNumber(), 1e-9);
    }

    @Test
    void builtins_pow_sqrt_abs_min_max() {
        ElaraScript es = new ElaraScript();
        Map<String, ElaraScript.Value> env = es.run("""
            let a = pow(2, 8);
            let b = sqrt(9);
            let c = abs(-3);
            let d = min(4, 2, 9);
            let e = max(4, 2, 9);
        """);

        assertEquals(256.0, v(env, "a").asNumber(), 1e-9);
        assertEquals(3.0, v(env, "b").asNumber(), 1e-9);
        assertEquals(3.0, v(env, "c").asNumber(), 1e-9);
        assertEquals(2.0, v(env, "d").asNumber(), 1e-9);
        assertEquals(9.0, v(env, "e").asNumber(), 1e-9);
    }

    @Test
    void errors_undefinedVariable() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = y;
        """));
        assertTrue(ex.getMessage().contains("Undefined variable"));
    }

    @Test
    void errors_redefineLetSameScope() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = 1;
            let x = 2;
        """));
        assertTrue(ex.getMessage().contains("already defined"));
    }

    @Test
    void errors_unknownFunction() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = nope(1);
        """));
        assertTrue(ex.getMessage().contains("Unknown function"));
    }

    @Test
    void errors_wrongArgCount() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = pow(2);
        """));
        assertTrue(ex.getMessage().contains("expects 2"));
    }

    @Test
    void errors_invalidAssignmentTarget() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            (1 + 2) = 3;
        """));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid assignment"));
    }

    @Test
    void parsingErrors_missingSemicolon() {
        ElaraScript es = new ElaraScript();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run("""
            let x = 1
            let y = 2;
        """));
        assertTrue(ex.getMessage().contains("Expect ';'"));
    }

    @Test
    void userFunction_basicCallAndReturn() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "function add(a, b) {\n"
                + "  return a + b;\n"
                + "}\n"
                + "let x = add(2, 3);\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(5.0, env.get("x").asNumber(), 1e-9);
    }

    @Test
    void userFunction_returnsNullIfNoReturn() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "function noop() {\n"
                + "  let a = 1;\n"
                + "}\n"
                + "let r = noop();\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(ElaraScript.Value.Type.NULL, env.get("r").getType());
    }

    @Test
    void userFunction_recursion_factorial() {
        ElaraScript es = new ElaraScript();
        es.setMaxCallDepth(128);

        String src = ""
                + "function fact(n) {\n"
                + "  if (n == 0) return 1;\n"
                + "  return n * fact(n - 1);\n"
                + "}\n"
                + "let out = fact(6);\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(720.0, env.get("out").asNumber(), 1e-9);
    }

    @Test
    void userFunction_lexicalClosure_readsOuterVars() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "let base = 10;\n"
                + "function addBase(x) {\n"
                + "  return x + base;\n"
                + "}\n"
                + "let out = addBase(5);\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(15.0, env.get("out").asNumber(), 1e-9);
    }

    @Test
    void break_exitsWhileLoop() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "let i = 0;\n"
                + "while (true) {\n"
                + "  i = i + 1;\n"
                + "  if (i == 3) break;\n"
                + "}\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(3.0, env.get("i").asNumber(), 1e-9);
    }

    @Test
    void break_exitsForLoop() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "let s = 0;\n"
                + "for (let i = 0; i < 10; i = i + 1) {\n"
                + "  s = s + 1;\n"
                + "  if (i == 4) break;\n"
                + "}\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        // i = 0..4 => 5 iterations
        assertEquals(5.0, env.get("s").asNumber(), 1e-9);
    }

    @Test
    void break_onlyExitsInnermostLoop() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "let outer = 0;\n"
                + "let innerCount = 0;\n"
                + "for (let i = 0; i < 3; i = i + 1) {\n"
                + "  outer = outer + 1;\n"
                + "  let j = 0;\n"
                + "  while (true) {\n"
                + "    innerCount = innerCount + 1;\n"
                + "    j = j + 1;\n"
                + "    if (j == 2) break;\n"
                + "  }\n"
                + "}\n";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(3.0, env.get("outer").asNumber(), 1e-9);
        // 3 outer iterations * 2 inner iterations each
        assertEquals(6.0, env.get("innerCount").asNumber(), 1e-9);
    }

    @Test
    void break_outsideLoop_isParseError() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "let a = 1;\n"
                + "break;\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        assertTrue(ex.getMessage().contains("break") && ex.getMessage().contains("outside"), ex.getMessage());
    }

    @Test
    void functionName_conflictsWithBuiltin_isRejected() {
        ElaraScript es = new ElaraScript();

        String src = ""
                + "function pow(a, b) { return 123; }\n"
                + "let x = pow(2, 3);\n";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> es.run(src));
        assertTrue(ex.getMessage().toLowerCase().contains("builtin"), ex.getMessage());
    }

    @Test
    void inferenceMode_allows_callAndStringVariableIndirection() {
        ElaraScript es = new ElaraScript();
        es.setMode(ElaraScript.Mode.INFERENCE);

        String src = ""
                + "function add(a, b) { return a + b; }"
                + "let fn = \"add\";"
                + "let a = call(\"add\", 2, 3);"
                + "let b = fn(4, 5);";

        Map<String, ElaraScript.Value> env = es.run(src);
        assertEquals(5.0, env.get("a").asNumber(), 1e-9);
        assertEquals(9.0, env.get("b").asNumber(), 1e-9);
    }
    
    @Test
    void spreadOperator_expandsArrayIntoCallArguments() {
	    ElaraScript es = new ElaraScript();
	
	
	    String src = ""
	    + "function add3(a, b, c) { return a + b + c; }"
	    + "let args = [2, 3];"
	    + "let out = add3(1, **args);";
	
	
	    Map<String, ElaraScript.Value> env = es.run(src);
	    assertEquals(6.0, env.get("out").asNumber(), 1e-9);
    }
}

