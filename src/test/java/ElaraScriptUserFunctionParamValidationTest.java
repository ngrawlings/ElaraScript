import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import com.elara.script.shaping.ElaraDataShaper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptUserFunctionParamValidationTest {

    // ... keep your existing tests ...

    @Test
    void classMethodParam_withRequiredValidatorSuffix_validates_andRunsMethod() {
        ElaraScript es = new ElaraScript();

        // Register validator "num"
        ElaraDataShaper.Shape<Value> num = new ElaraDataShaper.Shape<>();
        num.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("num", num);

        // Runner shape: no inputs needed, just output
        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def add(num_a??, num_b??) {",
                "    return num_a + num_b;",
                "  }",
                "}",
                "let a = new A();",
                "let out = a.add(2, 3);"
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success. Errors: " + rr.errors());
        assertEquals(5.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void classMethodParam_withRequiredValidatorSuffix_failsOnMismatch() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> num = new ElaraDataShaper.Shape<>();
        num.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("num", num);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def add(num_a??, num_b??) {",
                "    return num_a + num_b;",
                "  }",
                "}",
                "let a = new A();",
                "let out = a.add(\"nope\", 3);" // mismatch: first arg must be number
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertFalse(rr.ok(), "Expected validation failure: num_a?? requires NUMBER");
    }

    @Test
    void classMethodParam_withRequiredValidatorSuffix_throwsIfValidatorMissing() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def add(missing_a??, missing_b??) {",
                "    return 1;",
                "  }",
                "}",
                "let a = new A();",
                "let out = a.add(2, 3);"
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertFalse(rr.ok(), "Expected failure because required validator 'missing' is not registered");
        assertTrue(
                rr.errors().toString().toLowerCase().contains("missing"),
                () -> "Expected error mentioning missing validator. Errors: " + rr.errors()
        );
    }

    @Test
    void classMethodParam_withoutRequiredSuffix_skipsIfValidatorNotRegistered() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def f(unknown_a) {",
                "    return 7;",
                "  }",
                "}",
                "let a = new A();",
                "let out = a.f(\"anything\");"
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success. Errors: " + rr.errors());
        assertEquals(7.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void classMethodParam_namedNoValidation_alwaysSkipsEvenIfValidatorExists() {
        ElaraScript es = new ElaraScript();

        // Register validator "no" (but method param uses no_validation)
        ElaraDataShaper.Shape<Value> no = new ElaraDataShaper.Shape<>();
        no.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("no", no);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def f(no_validation) {",
                "    return 3;",
                "  }",
                "}",
                "let a = new A();",
                "let out = a.f(\"definitely-not-a-number\");"
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success because 'no_validation' disables validation. Errors: " + rr.errors());
        assertEquals(3.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void classMethodParam_withRequiredSuffix_routesToTypeFunction_whenNoShapeRegistered() {
        ElaraScript es = new ElaraScript();

        // Runner shape
        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        // No DataShape registered for "user" on purpose, define ES fallback type_user()
        String src = String.join("\n",
                "function type_user(v) {",
                "  if (v == null) return false;",
                "  let p = v[\"profile\"];",
                "  if (p == null) return false;",
                "  return p[\"id\"] == 42;",
                "}",
                "",
                "class A {",
                "  def getId(user_payload??) {",
                "    return user_payload[\"profile\"][\"id\"];",
                "  }",
                "}",
                "",
                "let a = new A();",
                "let out = a.getId({profile:{id:42}});" // map literal ok in your engine
        );

        ElaraDataShaper.RunResult<Value> ok = es.runShaped(src, "top", Map.of(), false);
        assertTrue(ok.ok(), () -> "Expected success via type_user() fallback. Errors: " + ok.errors());
        assertEquals(42.0, ok.outputs().get("out").asNumber(), 0.0);

        String srcBad = String.join("\n",
                "function type_user(v) {",
                "  if (v == null) return false;",
                "  let p = v[\"profile\"];",
                "  if (p == null) return false;",
                "  return p[\"id\"] == 42;",
                "}",
                "",
                "class A {",
                "  def getId(user_payload??) {",
                "    return user_payload[\"profile\"][\"id\"];",
                "  }",
                "}",
                "",
                "let a = new A();",
                "let out = a.getId({profile:{id:7}});" // should fail
        );

        ElaraDataShaper.RunResult<Value> bad = es.runShaped(srcBad, "top", Map.of(), false);
        assertFalse(bad.ok(), "Expected failure because type_user() returns false");
        String errs = bad.errors().toString().toLowerCase();
        assertTrue(
                errs.contains("type_") || errs.contains("validation") || errs.contains("user"),
                () -> "Expected error mentioning type_user/validation/user. Errors: " + bad.errors()
        );
    }
    
    @Test
    void trycall_inClassMethod_intentionallyFailsValidation_andReturnsTryCallResultError() {
        ElaraScript es = new ElaraScript();

        // Register validator "num"
        ElaraDataShaper.Shape<Value> num = new ElaraDataShaper.Shape<>();
        num.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("num", num);

        // Runner shape
        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.ARRAY).required(true);
        es.dataShaping().register("top", top);

        String src = String.join("\n",
                "class A {",
                "  def add(num_a??, num_b??) {",
                "    return num_a + num_b;",
                "  }",
                "}",
                "function main() {",
                "  let a = new A();",
                "  // Intentionally fail validation: pass string where NUMBER required",
                "  let r = a.trycall(\"add\", \"nope\", 3);",
                "  return [r.result(), r.value(), len(r.error())];",
                "}",
                "let out = main();"
        );

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);

        // The script should run fine; trycall should swallow the validation failure.
        assertTrue(rr.ok(), () -> "Top-level run should succeed; trycall should capture error. Errors: " + rr.errors());

        var out = rr.outputs().get("out").asArray();

        // result() should be false
        assertFalse(out.get(0).asBool(), "Expected r.result() == false due to validation failure");

        // error() should have at least one message
        assertTrue(out.get(2).asNumber() >= 1.0, "Expected at least one error message from validation failure");
    }

    
}
