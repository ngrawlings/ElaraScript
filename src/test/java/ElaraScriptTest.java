import org.junit.jupiter.api.Test;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptTest {

    private static Value v(Map<String, Value> env, String name) {
        Value val = env.get(name);
        assertNotNull(val, "Expected variable in env: " + name);
        return val;
    }

    @Test
    void letAndAssignment_number() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let x = 10;\n" +
                "x = x + 5;\n" +
                "let y = x;\n"
        );

        assertEquals(15.0, v(env, "x").asNumber(), 1e-9);
        assertEquals(15.0, v(env, "y").asNumber(), 1e-9);
    }

    @Test
    void arithmetic_precedence() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let a = 2 + 3 * 4;\n" +
                "let b = (2 + 3) * 4;\n" +
                "let c = 10 / 2 + 6;\n" +
                "let d = 10 / (2 + 3);\n" +
                "let e = 10 % 4;\n"
        );

        assertEquals(14.0, v(env, "a").asNumber(), 1e-9);
        assertEquals(20.0, v(env, "b").asNumber(), 1e-9);
        assertEquals(11.0, v(env, "c").asNumber(), 1e-9);
        assertEquals(2.0, v(env, "d").asNumber(), 1e-9);
        assertEquals(2.0, v(env, "e").asNumber(), 1e-9);
    }

    @Test
    void comparisonsAndEquality() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let a = 3;\n" +
                "let b = 5;\n" +
                "let lt = a < b;\n" +
                "let le = a <= 3;\n" +
                "let gt = b > a;\n" +
                "let ge = b >= 5;\n" +
                "let eq = (a + 2) == b;\n" +
                "let ne = a != b;\n"
        );

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

        Map<String, Value> env1 =
                es.run("let ok = true || doesNotExist(1);\n");
        assertTrue(v(env1, "ok").asBool());

        Map<String, Value> env2 =
                es.run("let ok = false && doesNotExist(1);\n");
        assertFalse(v(env2, "ok").asBool());

        assertThrows(RuntimeException.class,
                () -> es.run("let bad = true && doesNotExist(1);\n"));
    }

    @Test
    void unaryOperators_not_and_negation() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let a = !false;\n" +
                "let b = !(1);\n" +
                "let c = -5;\n" +
                "let d = -(2 + 3);\n"
        );

        assertTrue(v(env, "a").asBool());
        assertFalse(v(env, "b").asBool());
        assertEquals(-5.0, v(env, "c").asNumber(), 1e-9);
        assertEquals(-5.0, v(env, "d").asNumber(), 1e-9);
    }

    @Test
    void ifElse_blocks_and_scoping() {
        ElaraScript es = new ElaraScript();

        assertThrows(RuntimeException.class, () -> es.run(
                "let x = 2;\n" +
                "if (x > 1) {\n" +
                "  let inner = 42;\n" +
                "}\n" +
                "let y = inner;\n"
        ));

        Map<String, Value> env = es.run(
                "let x = 2;\n" +
                "let y = 0;\n" +
                "if (x > 1) {\n" +
                "  y = 10;\n" +
                "} else {\n" +
                "  y = 20;\n" +
                "}\n"
        );

        assertEquals(10.0, v(env, "y").asNumber(), 1e-9);
    }

    @Test
    void whileLoop_basic_accumulator() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let i = 0;\n" +
                "let total = 0;\n" +
                "while (i < 5) {\n" +
                "  total = total + i;\n" +
                "  i = i + 1;\n" +
                "}\n"
        );

        assertEquals(5.0, v(env, "i").asNumber(), 1e-9);
        assertEquals(10.0, v(env, "total").asNumber(), 1e-9);
    }

    @Test
    void arrays_literal_index_len() {
        ElaraScript es = new ElaraScript();
        Map<String, Value> env = es.run(
                "let v = [10, 20, 30];\n" +
                "let a = v[0];\n" +
                "let b = v[2];\n" +
                "let n = len(v);\n"
        );

        assertEquals(10.0, v(env, "a").asNumber(), 1e-9);
        assertEquals(30.0, v(env, "b").asNumber(), 1e-9);
        assertEquals(3.0, v(env, "n").asNumber(), 1e-9);
    }

    @Test
    void spreadOperator_expandsArrayIntoCallArguments() {
        ElaraScript es = new ElaraScript();

        Map<String, Value> env = es.run(
                "function add3(a, b, c) { return a + b + c; }\n" +
                "let args = [2, 3];\n" +
                "let out = add3(1, **args);\n"
        );

        assertEquals(6.0, env.get("out").asNumber(), 1e-9);
    }
}
