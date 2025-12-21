import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.Value;
import com.elara.script.shaping.ElaraDataShaper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptUserFunctionParamValidationTest {

    @Test
    void userFuncParam_withRequiredValidatorSuffix_throwsIfValidatorMissing() {
        ElaraScript es = new ElaraScript();

        // Top-level run shape: we just want a single output so we can run deterministically.
        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        // Function param "missing_x??" => match name "missing" (before first '_')
        // and "??" => validator is REQUIRED, so missing registry entry must error.
        String src = """
            function f(missing_x??) {
                return 1;
            }
            let out = f(123);
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertFalse(rr.ok(), "Expected failure because required validator 'missing' is not registered");
        assertTrue(
                rr.errors().toString().toLowerCase().contains("missing"),
                () -> "Expected error mentioning missing validator. Errors: " + rr.errors()
        );
    }

    @Test
    void userFuncParam_withRequiredValidatorSuffix_validatesTypeAndFailsOnMismatch() {
        ElaraScript es = new ElaraScript();

        // Register a validator named "num" that requires a NUMBER input.
        // NOTE: Per your implementation, the function-param validator uses the FIRST input spec as the contract.
        ElaraDataShaper.Shape<Value> num = new ElaraDataShaper.Shape<>();
        num.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("num", num);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        // Param "num_a??" => must validate using "num" validator.
        // Passing a string should fail.
        String src = """
            function f(num_a??) {
                return 1;
            }
            let out = f("not-a-number");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertFalse(rr.ok(), "Expected validation failure: num_a?? requires NUMBER");
        String errs = rr.errors().toString().toLowerCase();
        assertTrue(
                errs.contains("num") || errs.contains("number") || errs.contains("type"),
                () -> "Expected error to mention type/number/num. Errors: " + rr.errors()
        );
    }

    @Test
    void userFuncParam_withoutRequiredSuffix_skipsIfValidatorNotRegistered() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        // Param "unknown_a" => match "unknown" but NO "??" => validation is optional.
        // Since "unknown" validator isn't registered, it should NOT error.
        String src = """
            function f(unknown_a) {
                return 7;
            }
            let out = f("anything");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success because validator 'unknown' is optional and not registered. Errors: " + rr.errors());
        assertEquals(7.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void userFuncParam_namedNoValidation_alwaysSkipsEvenIfValidatorExists() {
        ElaraScript es = new ElaraScript();

        // Even if a validator exists with prefix "no" or anything else,
        // the param name "no_validation" must bypass validation entirely.
        ElaraDataShaper.Shape<Value> no = new ElaraDataShaper.Shape<>();
        no.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("no", no);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(no_validation) {
                return 3;
            }
            // This would fail if validation ran and expected NUMBER,
            // but "no_validation" must skip validation.
            let out = f("definitely-not-a-number");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success because 'no_validation' disables validation. Errors: " + rr.errors());
        assertEquals(3.0, rr.outputs().get("out").asNumber(), 0.0);
    }
}
