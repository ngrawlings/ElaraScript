import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptTryCallTest {

    private static Value run(ElaraScript es, String src) {
        return es.run(src, "main", Collections.emptyList());
    }

    @Test
    public void trycall_success_builtin_abs() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "function main(){",
                "  let r = trycall(\"abs\", -5);",
                "  return [r.result(), r.value(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        assertEquals(Value.Type.ARRAY, out.getType());

        List<Value> a = out.asArray();
        assertTrue(a.get(0).asBool());
        assertEquals(5.0, a.get(1).asNumber(), 0.0);
        assertEquals(0.0, a.get(2).asNumber(), 0.0);
    }

    @Test
    public void trycall_unknown_function_sets_error_array() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "function main(){",
                "  let r = trycall(\"no_such_fn\", 1);",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> a = out.asArray();

        assertFalse(a.get(0).asBool());
        assertTrue(a.get(1).asNumber() >= 1.0);
    }

    @Test
    public void trycall_catches_runtime_error_inside_user_function() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "function boom(){",
                "  return missingVar;",
                "}",
                "function main(){",
                "  let r = trycall(\"boom\");",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> a = out.asArray();

        assertFalse(a.get(0).asBool());
        assertTrue(a.get(1).asNumber() >= 1.0);
    }

    @Test
    public void trycall_catches_type_validator_failure() {
        ElaraScript es = new ElaraScript();

        // Force validator failure with a user-defined type_foo that returns false.
        // Then call a function whose param name triggers required validation: foo_payload??
        String src = String.join("\n",
                "function type_foo(x){",
                "  return false;",
                "}",
                "function needs(foo_payload??){",
                "  return 123;",
                "}",
                "function main(){",
                "  let r = trycall(\"needs\", 999);",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> a = out.asArray();

        assertFalse(a.get(0).asBool());
        assertTrue(a.get(1).asNumber() >= 1.0);
    }
}
