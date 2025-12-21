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

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(missing_x??) { return 1; }
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

        ElaraDataShaper.Shape<Value> num = new ElaraDataShaper.Shape<>();
        num.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("num", num);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(num_a??) { return 1; }
            let out = f("not-a-number");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertFalse(rr.ok(), "Expected validation failure: num_a?? requires NUMBER");
    }

    @Test
    void userFuncParam_withoutRequiredSuffix_skipsIfValidatorNotRegistered() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(unknown_a) { return 7; }
            let out = f("anything");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success. Errors: " + rr.errors());
        assertEquals(7.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void userFuncParam_namedNoValidation_alwaysSkipsEvenIfValidatorExists() {
        ElaraScript es = new ElaraScript();

        ElaraDataShaper.Shape<Value> no = new ElaraDataShaper.Shape<>();
        no.input("v", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("no", no);

        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(no_validation) { return 3; }
            let out = f("definitely-not-a-number");
        """;

        ElaraDataShaper.RunResult<Value> rr = es.runShaped(src, "top", Map.of(), false);
        assertTrue(rr.ok(), () -> "Expected success because 'no_validation' disables validation. Errors: " + rr.errors());
        assertEquals(3.0, rr.outputs().get("out").asNumber(), 0.0);
    }

    @Test
    void userFuncParam_withRequiredValidatorSuffix_validatesComplexMultiLevelMap_andRunsUserFunction() {
        ElaraScript es = new ElaraScript();

        es.dataShaping().shaper().registerUserFunction("user_contract", (v, path, errors) -> {
            if (v == null || v.getType() != Value.Type.MAP) return;

            Map<String, Value> m = v.asMap();

            Value profileV = m.get("profile");
            if (profileV == null || profileV.getType() != Value.Type.MAP) {
                errors.add(new ElaraDataShaper.ValidationError(path + ".profile", "required map"));
                return;
            }

            Map<String, Value> profile = profileV.asMap();
            Value idV = profile.get("id");
            if (idV == null || idV.getType() != Value.Type.NUMBER || idV.asNumber() < 1) {
                errors.add(new ElaraDataShaper.ValidationError(path + ".profile.id", "must be >= 1"));
            }

            Value rolesV = m.get("roles");
            if (rolesV != null && rolesV.getType() == Value.Type.ARRAY) {
                var roles = rolesV.asArray();
                if (!roles.isEmpty() && roles.get(0).getType() == Value.Type.MAP) {
                    var r0 = roles.get(0).asMap();
                    Value roleName = r0.get("role");
                    Value active = r0.get("active");

                    if (roleName != null && roleName.getType() == Value.Type.STRING
                            && "admin".equals(roleName.asString())) {
                        if (active == null || active.getType() != Value.Type.BOOL || !active.asBool()) {
                            errors.add(new ElaraDataShaper.ValidationError(path + ".roles[0].active",
                                    "must be true when role is admin"));
                        }
                    }
                }
            }
        });

        // Param validator "user" (matched from "user_payload??")
        ElaraDataShaper.Shape<Value> user = new ElaraDataShaper.Shape<>();
        var root = user.input("u", ElaraDataShaper.Type.MAP).required(true);
        root.userFunction("user_contract");

        var profile = root.child("profile", ElaraDataShaper.Type.MAP).required(true);
        profile.child("id", ElaraDataShaper.Type.NUMBER).required(true);
        profile.child("name", ElaraDataShaper.Type.STRING).required(true);

        var roles = root.child("roles", ElaraDataShaper.Type.ARRAY).required(true);
        var roleItem = roles.item(ElaraDataShaper.Type.MAP).required(true);
        roleItem.child("role", ElaraDataShaper.Type.STRING).required(true);
        roleItem.child("active", ElaraDataShaper.Type.BOOL).required(true);

        es.dataShaping().register("user", user);

        // ✅ Top-level runner shape MUST declare input "u" so the script can reference it.
        ElaraDataShaper.Shape<Value> top = new ElaraDataShaper.Shape<>();
        top.input("u", ElaraDataShaper.Type.MAP).required(true);     // <-- FIX
        top.output("out", ElaraDataShaper.Type.NUMBER).required(true);
        es.dataShaping().register("top", top);

        String src = """
            function f(user_payload??) {
                return user_payload["profile"]["id"];
            }
            let out = f(u);
        """;

        Map<String, Object> okRaw = Map.of(
                "u", Map.of(
                        "profile", Map.of("id", 10, "name", "alice"),
                        "roles", java.util.List.of(
                                Map.of("role", "admin", "active", true),
                                Map.of("role", "user", "active", false)
                        )
                )
        );

        var ok = es.runShaped(src, "top", okRaw, false);
        assertTrue(ok.ok(), () -> "Expected success for valid complex multi-level map. Errors: " + ok.errors());
        assertEquals(10.0, ok.outputs().get("out").asNumber(), 0.0);

        Map<String, Object> badRaw = Map.of(
                "u", Map.of(
                        "profile", Map.of("id", 10, "name", "alice"),
                        "roles", java.util.List.of(
                                Map.of("role", "admin", "active", false)
                        )
                )
        );

        var bad = es.runShaped(src, "top", badRaw, false);
        assertFalse(bad.ok(), "Expected failure due to user_function invariant");
        assertTrue(
                bad.errors().toString().toLowerCase().contains("admin")
                        || bad.errors().toString().toLowerCase().contains("active"),
                () -> "Expected error mentioning admin/active invariant. Errors: " + bad.errors()
        );
    }

}
