import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.Value;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptThisPropertyAccessTest {

    private static List<Value> runArr(ElaraScript es, String src) {
        Value out = es.run(src, "main", Collections.emptyList());
        assertEquals(Value.Type.ARRAY, out.getType(), "main() should return array");
        return out.asArray();
    }

    @Test
    void this_propertySetAndGet_insideMethods_works() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class SV {",
                "  def constructor() {",
                "    this.ctx = [];",
                "    this.vectors = {};",
                "    this.defaultVector = null;",
                "  }",
                "  def snap() {",
                "    return [len(this.ctx), len(keys(this.vectors)), this.defaultVector == null];",
                "  }",
                "}",
                "function main() {",
                "  let s = new SV();",
                "  return s.snap();",
                "}"
        );

        List<Value> a = runArr(es, src);
        assertEquals(0.0, a.get(0).asNumber(), 0.0);
        assertEquals(0.0, a.get(1).asNumber(), 0.0);
        assertTrue(a.get(2).asBool());
    }

    @Test
    void obj_propertySetAndGet_outsideMethods_works() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class SV {",
                "  def constructor() {",
                "    this.ctx = [];",
                "  }",
                "}",
                "function main() {",
                "  let s = new SV();",
                "  s.defaultVector = \"v1\";",
                "  return [s.defaultVector, s.defaultVector == \"v1\"];",
                "}"
        );

        List<Value> a = runArr(es, src);
        assertEquals("v1", a.get(0).asString());
        assertTrue(a.get(1).asBool());
    }

    @Test
    void obj_propertyAssignment_canReplaceArray_andReadLen() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
                "class SV {",
                "  def constructor() { this.ctx = []; }",
                "}",
                "function main() {",
                "  let s = new SV();",
                "  s.ctx = [1,2,3];",
                "  return [len(s.ctx), s.ctx[0]];",
                "}"
        );

        List<Value> a = runArr(es, src);
        assertEquals(3.0, a.get(0).asNumber(), 0.0);
        assertEquals(1.0, a.get(1).asNumber(), 0.0);
    }
}
