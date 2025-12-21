import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.DataShape;
import com.elara.script.ElaraScript.FieldSpec;
import com.elara.script.ElaraScript.RunResult;
import com.elara.script.ElaraScript.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptDataShapeValidationTest {

    @Test
    void missingRequiredInput_failsValidation_andDoesNotRunScript() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("x", Value.Type.NUMBER).required(true);
        shape.output("out", Value.Type.NUMBER).required(true);

        // script would set out, but validation should fail before execution
        String src = "let out = 123;";

        RunResult rr = es.run(src, shape, Map.of());
        assertFalse(rr.ok());
        assertTrue(rr.outputs().isEmpty());
        assertFalse(rr.errors().isEmpty());
        assertTrue(rr.errors().stream().anyMatch(e -> e.field.equals("x") && e.message.contains("Missing required input")));
    }

    @Test
    void typeMismatchInput_failsValidation() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("x", Value.Type.NUMBER).required(true);
        shape.output("out", Value.Type.NUMBER).required(true);

        String src = "let out = x;";

        // x is string but expected number
        RunResult rr = es.run(src, shape, Map.of("x", "not-a-number"));
        assertFalse(rr.ok());
        assertTrue(rr.errors().stream().anyMatch(e -> e.field.equals("x")));
    }

    @Test
    void numericMinMaxAndIntegerOnly_areEnforced() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("n", Value.Type.NUMBER).required(true).min(0).max(10).integerOnly(true);
        shape.output("out", Value.Type.NUMBER).required(true);

        String src = "let out = n;";

        // non-integer
        RunResult rr1 = es.run(src, shape, Map.of("n", 1.5));
        assertFalse(rr1.ok());
        assertTrue(rr1.errors().stream().anyMatch(e -> e.field.equals("n") && e.message.toLowerCase().contains("integer")));

        // out of range
        RunResult rr2 = es.run(src, shape, Map.of("n", 99));
        assertFalse(rr2.ok());
        assertTrue(rr2.errors().stream().anyMatch(e -> e.field.equals("n") && e.message.contains(">=") || e.message.contains("<=")));

        // ok
        RunResult rr3 = es.run(src, shape, Map.of("n", 7));
        assertTrue(rr3.ok());
        assertEquals(7.0, rr3.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void stringConstraints_lengthAndRegex_areEnforced() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("s", Value.Type.STRING).required(true).minLen(2).maxLen(5).regex("^[a-z]+$");
        shape.output("out", Value.Type.STRING).required(true);

        String src = "let out = s;";

        // too short
        RunResult rr1 = es.run(src, shape, Map.of("s", "a"));
        assertFalse(rr1.ok());

        // invalid regex (contains digits)
        RunResult rr2 = es.run(src, shape, Map.of("s", "ab12"));
        assertFalse(rr2.ok());

        // ok
        RunResult rr3 = es.run(src, shape, Map.of("s", "abcd"));
        assertTrue(rr3.ok());
        assertEquals("abcd", rr3.outputs().get("out").asString());
    }

    @Test
    void arrayConstraints_elementTypeAndElementMinMax_areEnforced() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("a", Value.Type.ARRAY)
                .required(true)
                .elementType(Value.Type.NUMBER)
                .elementMin(0)
                .elementMax(10);
        shape.output("out", Value.Type.NUMBER).required(true);

        // return len(a) just to ensure script runs when valid
        String src = "let out = len(a);";

        // element violates min/max
        RunResult rr1 = es.run(src, shape, Map.of("a", List.of(-1, 2, 3)));
        assertFalse(rr1.ok());

        // element type mismatch
        RunResult rr2 = es.run(src, shape, Map.of("a", List.of(1, "x", 3)));
        assertFalse(rr2.ok());

        // ok
        RunResult rr3 = es.run(src, shape, Map.of("a", List.of(1, 2, 3)));
        assertTrue(rr3.ok());
        assertEquals(3.0, rr3.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void mapConstraints_valueTypeEnforced_whenElementTypeSet() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("m", Value.Type.MAP)
                .required(true)
                .elementType(Value.Type.STRING);
        shape.output("out", Value.Type.NUMBER).required(true);

        String src = "let out = len(m);";

        // map value type mismatch (number instead of string)
        RunResult rr1 = es.run(src, shape, Map.of("m", Map.of("a", 1, "b", "ok")));
        assertFalse(rr1.ok());
        assertTrue(rr1.errors().stream().anyMatch(e -> e.field.equals("m") && e.message.toLowerCase().contains("expected")));

        // ok
        RunResult rr2 = es.run(src, shape, Map.of("m", Map.of("a", "x", "b", "y")));
        assertTrue(rr2.ok());
        assertEquals(2.0, rr2.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void requiredOutputMissing_failsAfterRun() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.output("out", Value.Type.NUMBER).required(true);

        // does not define out
        String src = "let x = 1;";

        RunResult rr = es.run(src, shape, Map.of());
        assertFalse(rr.ok());
        assertTrue(rr.errors().stream().anyMatch(e -> e.field.equals("out") && e.message.contains("Missing required output")));
    }

    @Test
    void runtimeError_isReportedAsRuntimeValidationError() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.output("out", Value.Type.NUMBER).required(true);

        // undefined var -> runtime exception
        String src = "let out = does_not_exist + 1;";

        RunResult rr = es.run(src, shape, Map.of());
        assertFalse(rr.ok());
        assertTrue(rr.errors().stream().anyMatch(e -> e.field.equals("$runtime")));
    }
    
    @Test
    void defaultValue_isAppliedWhenInputMissing_andScriptRuns() {
        ElaraScript es = new ElaraScript();

        DataShape shape = new DataShape();
        shape.input("x", Value.Type.NUMBER)
                .required(false)
                .defaultValue(Value.number(41));
        shape.output("out", Value.Type.NUMBER).required(true);

        String src = "let out = x + 1;";

        // x omitted -> should use default 41
        RunResult rr = es.run(src, shape, Map.of());
        assertTrue(rr.ok(), "Expected script to run with default value applied: " + rr.errors());

        assertEquals(42.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void coerceFromString_parsesNumbersAndBools_andCanBeDisabledPerField() {
        ElaraScript es = new ElaraScript();

        // --- coercion enabled (default) ---
        DataShape shape1 = new DataShape();
        shape1.input("n", Value.Type.NUMBER).required(true); // coerceFromString defaults true
        shape1.input("b", Value.Type.BOOL).required(true);   // coerceFromString defaults true
        shape1.output("out", Value.Type.NUMBER).required(true);

        // No ternary operator in ElaraScript; use if/else.
        String src = ""
                + "let out = n;\n"
                + "if (b) { out = out + 1; }\n";

        RunResult rr1 = es.run(src, shape1, Map.of(
                "n", "41",
                "b", "true"
        ));
        assertTrue(rr1.ok(), "Expected coercion from string to succeed: " + rr1.errors());
        assertEquals(42.0, rr1.outputs().get("out").asNumber(), 0.0);

        // --- coercion disabled for one field ---
        DataShape shape2 = new DataShape();
        shape2.input("n", Value.Type.NUMBER).required(true).coerceFromString(false);
        shape2.output("out", Value.Type.NUMBER).required(true);

        RunResult rr2 = es.run("let out = n;", shape2, Map.of("n", "41"));
        assertFalse(rr2.ok(), "Expected failure when coerceFromString is disabled");
        assertTrue(rr2.errors().stream().anyMatch(e -> e.field.equals("n")));
    }


}
