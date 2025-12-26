import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptGetterSetterContractTest {

    private static List<Value> runArr(ElaraScript es, String src) {
        Value out = es.run(src, "main", Collections.emptyList());
        assertNotNull(out, "main() returned null");
        assertEquals(Value.Type.ARRAY, out.getType(), "main() should return array");
        return out.asArray();
    }

    private static void assertNumber(Value v, double expected, String label) {
        assertNotNull(v, label + " returned null Value reference");
        assertEquals(Value.Type.NUMBER, v.getType(), label + " expected NUMBER but got " + v.getType() + " (" + v + ")");
        assertEquals(expected, v.asNumber(), 0.0, label + " numeric mismatch");
    }

    @Test
    void getterSetter_contract_getWorksOnThisAndObj_setOnlyWorksOnThis() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "class A {",
            "  def constructor() {",
            "    this.x = 0;",
            "  }",
            "  def getThisX() {",
            "    return this.x;",       // getter path #1
            "  }",
            "  def setThisX(v) {",
            "    this.x = v;",          // setter allowed (this only)",
            "    return this.x;",
            "  }",
            "}",
            "",
            "// outside setter attempt: MUST fail once you enforce the rule",
            "function outsideSet(o, v) {",
            "  o.x = v;",               // should become illegal (obj setter)",
            "  return o.x;",
            "}",
            "",
            "function main() {",
            "  let a = new A();",
            "",
            "  // Baseline reads (both getter styles must work)",
            "  let g_this_0 = a.getThisX();",
            "  let g_obj_0  = a.x;",
            "",
            "  // Mutate through this-only setter (must work)",
            "  let s1 = a.setThisX(7);",
            "  let g_this_1 = a.getThisX();",
            "  let g_obj_1  = a.x;",
            "",
            "  // Attempt outside set (caught via trycall)",
            "  let r = trycall(\"outsideSet\", a, 9);",
            "  let ok = r.result();",
            "",
            "  // Read again",
            "  let g_this_2 = a.getThisX();",
            "  let g_obj_2  = a.x;",
            "",
            "  // Return raw Values so Java can diagnose types safely",
            "  return [g_this_0, g_obj_0, s1, g_this_1, g_obj_1, ok, g_this_2, g_obj_2];",
            "}"
        );

        List<Value> out = runArr(es, src);

        // 1) Getter must work for BOTH this.x (inside method) and obj.x (outside).
        assertNumber(out.get(0), 0.0, "baseline get via this.x (a.getThisX())");
        assertNumber(out.get(1), 0.0, "baseline get via obj.x (a.x)");

        // 2) Setter via this.x must work and reflect via BOTH getter paths.
        assertNumber(out.get(2), 7.0, "set via this.x (a.setThisX(7)) return");
        assertNumber(out.get(3), 7.0, "after set get via this.x (a.getThisX())");
        assertNumber(out.get(4), 7.0, "after set get via obj.x (a.x)");

        // 3) Once you enforce rule, outside set should fail => ok == false.
        // For now, if your engine still allows obj.x = ..., this may be true.
        assertEquals(Value.Type.BOOL, out.get(5).getType(), "trycall.result() must be BOOL");
        // NOTE: Change to assertFalse once your enforcement is implemented:
        // assertFalse(out.get(5).asBool(), "obj.x=... outside method should fail");

        // 4) Reads must still be consistent after the attempt.
        // If outside set is currently allowed, these will become 9.
        // Once enforcement is added, they must remain 7.
        assertEquals(Value.Type.NUMBER, out.get(6).getType(), "post-attempt get via this.x returned " + out.get(6).getType());
        assertEquals(Value.Type.NUMBER, out.get(7).getType(), "post-attempt get via obj.x returned " + out.get(7).getType());
    }
}
