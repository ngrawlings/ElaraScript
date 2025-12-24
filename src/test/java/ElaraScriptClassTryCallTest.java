import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptClassTryCallTest {

    private static Value run(ElaraScript es, String src) {
        return es.run(src, "main", Collections.emptyList());
    }

    @Test
    public void class_trycall_success() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class A {",
                "  def add(a, b) {",
                "    return a + b;",
                "  }",
                "}",
                "function main(){",
                "  let a = new A();",
                "  let r = a.trycall(\"add\", 2, 3);",
                "  return [r.result(), r.value(), len(r.error())];",
                "}"
        );

        Value out = es.run(src, "main", Collections.emptyList());
        List<Value> a = out.asArray();
        assertTrue(a.get(0).asBool());
        assertEquals(5.0, a.get(1).asNumber(), 0.0);
        assertEquals(0.0, a.get(2).asNumber(), 0.0);
    }

    @Test
    public void class_trycall_unknown_method_sets_error_array() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class A {",
                "  def add(a, b) { return a + b; }",
                "}",
                "function main(){",
                "  let a = new A();",
                "  let r = a.trycall(\"nope\", 1, 3);",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> arr = out.asArray();
        assertFalse(arr.get(0).asBool());
        assertTrue(arr.get(1).asNumber() >= 1.0);
    }

    @Test
    public void class_trycall_catches_runtime_error_inside_method() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class A {",
                "  def boom() {",
                "    return missingVar;",
                "  }",
                "}",
                "function main(){",
                "  let a = new A();",
                "  let r = a.trycall(\"boom\");",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> arr = out.asArray();
        assertFalse(arr.get(0).asBool());
        assertTrue(arr.get(1).asNumber() >= 1.0);
    }

    @Test
    public void class_trycall_catches_type_validator_failure_in_method() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "function type_foo(x){",
                "  return false;",
                "}",
                "class A {",
                "  def needs(foo_payload??) {",
                "    return 123;",
                "  }",
                "}",
                "function main(){",
                "  let a = new A();",
                "  let r = a.trycall(\"needs\", 999);",
                "  return [r.result(), len(r.error())];",
                "}"
        );

        Value out = run(es, src);
        List<Value> arr = out.asArray();
        assertFalse(arr.get(0).asBool());
        assertTrue(arr.get(1).asNumber() >= 1.0);
    }
}
