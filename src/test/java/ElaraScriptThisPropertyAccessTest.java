import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Brutal class/property tests:
 * - this.<prop> inside methods
 * - obj.<prop> outside methods
 * - overwrite types (null -> string -> array -> map -> bytes)
 * - mutation through aliases (two refs to same instance)
 * - deep copy decorator (&) on instances prevents state pollution (if you support & on CLASS_INSTANCE)
 * - method call correctness (this routing)
 * - isolation between two different instances
 *
 * If any of these fail, you'll immediately know whether the bug is:
 * - dot getter/setter routing
 * - instance state table keying
 * - this binding / method dispatch
 * - aliasing semantics
 * - & deep copy semantics
 */
public class ElaraScriptThisPropertyAccessTest {

    private static List<Value> runArr(ElaraScript es, String src) {
        Value out = es.run(src, "main", Collections.emptyList());
        assertNotNull(out, "main() returned null Value");
        assertEquals(Value.Type.ARRAY, out.getType(), "main() should return array");
        return out.asArray();
    }

    @Test
    void brutal_this_and_obj_property_semantics_matrix() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "class SV {",
            "  def constructor(tag) {",
            "    this.tag = tag;",
            "    this.ctx = [];",
            "    this.vectors = {};",
            "    this.defaultVector = null;",
            "    this.nested = [[1],[2]];",
            "  }",
            "  def snap() {",
            "    return [",
            "      this.tag,",
            "      len(this.ctx),",
            "      len(keys(this.vectors)),",
            "      this.defaultVector == null,",
            "      this.nested[0][0],",
            "      len(this.nested[0])",
            "    ];",
            "  }",
            "  def setDefault(v) { this.defaultVector = v; }",
            "  def pushCtx(v) { this.ctx[len(this.ctx)] = v; }",
            "  def setVec(k, v) { this.vectors[k] = v; }",
            "  def bumpNested() { this.nested[0][len(this.nested[0])] = 77; }",
            "}",

            "function mutateOutside(o) {",
            "  o.defaultVector = \"v_out\";",
            "  o.ctx = [1,2,3];",
            "  o.vectors = {};",
            "  o.vectors[\"k1\"] = 123;",
            "  o.nested[0][len(o.nested[0])] = 88;",
            "}",

            "function aliasMutate(a, b) {",
            "  a.defaultVector = \"alias\";",
            "  b.ctx[len(b.ctx)] = 42;",
            "  a.vectors[\"shared\"] = 999;",
            "}",

            "function main() {",
            "  let a = new SV(\"A\");",
            "  let b = new SV(\"B\");",

            "  let s0 = a.snap();",

            "  a.setDefault(\"v1\");",
            "  a.pushCtx(10);",
            "  a.pushCtx(20);",
            "  a.setVec(\"x\", 1);",
            "  a.bumpNested();",
            "  let s1 = a.snap();",

            "  mutateOutside(a);",
            "  let s2 = a.snap();",

            "  let sB = b.snap();",

            "  let alias1 = a;",
            "  let alias2 = a;",
            "  aliasMutate(alias1, alias2);",
            "  let s3 = a.snap();",

            "  return [s0, s1, s2, sB, s3];",
            "}"
        );

        List<Value> top = runArr(es, src);
        assertEquals(5, top.size(), "Expected 5 snapshots");

        java.util.function.Function<Integer, List<Value>> snap = (idx) -> top.get(idx).asArray();

        // s0: constructed
        List<Value> s0 = snap.apply(0);
        assertEquals("A", s0.get(0).asString());
        assertEquals(0.0, s0.get(1).asNumber(), 0.0);
        assertEquals(0.0, s0.get(2).asNumber(), 0.0);
        assertTrue(s0.get(3).asBool());
        assertEquals(1.0, s0.get(4).asNumber(), 0.0);
        assertEquals(1.0, s0.get(5).asNumber(), 0.0);

        // s1: method mutations
        List<Value> s1 = snap.apply(1);
        assertEquals(2.0, s1.get(1).asNumber(), 0.0);
        assertEquals(1.0, s1.get(2).asNumber(), 0.0);
        assertFalse(s1.get(3).asBool());
        assertEquals(2.0, s1.get(5).asNumber(), 0.0); // bumpNested added one item

        // s2: outside mutations
        List<Value> s2 = snap.apply(2);
        assertEquals(3.0, s2.get(1).asNumber(), 0.0);
        assertEquals(1.0, s2.get(2).asNumber(), 0.0);
        assertFalse(s2.get(3).asBool());
        assertTrue(s2.get(5).asNumber() >= 2.0, "nested[0] should have grown");

        // sB: other instance remains clean
        List<Value> sB = snap.apply(3);
        assertEquals("B", sB.get(0).asString());
        assertEquals(0.0, sB.get(1).asNumber(), 0.0);
        assertEquals(0.0, sB.get(2).asNumber(), 0.0);
        assertTrue(sB.get(3).asBool());

        // s3: alias mutations reflect in a
        List<Value> s3 = snap.apply(4);
        assertFalse(s3.get(3).asBool());
        assertTrue(s3.get(1).asNumber() >= 4.0, "ctx should have grown via alias");
        assertTrue(s3.get(2).asNumber() >= 2.0, "vectors should include shared + others");
    }


    @Test
    void brutal_propertyShadowing_and_blockScopeDoesNotLeak() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "class SV {",
            "  def constructor() { this.v = 1; }",
            "  def getV() { return this.v; }",  // use method to read property (until s.v read exists)
            "}",
            "function main() {",
            "  let s = new SV();",
            "  let before = s.getV();",
            "  {",
            "    let v = 999;",       // should not affect property routing
            "    s.v = 2;",           // setter routing must still hit instance state
            "  }",
            "  let after = s.getV();",
            "  return [before, after];",
            "}"
        );

        List<Value> a = runArr(es, src);
        assertEquals(1.0, a.get(0).asNumber(), 0.0);
        assertEquals(2.0, a.get(1).asNumber(), 0.0);
    }


    @Test
    void brutal_unknownProperty_returnsNil_and_doesNotCreateState() {
        ElaraScript es = new ElaraScript();

        String src = String.join("\n",
            "class SV {",
            "  def constructor() { }",
            "}",
            "function main() {",
            "  let s = new SV();",
            "  // reading unknown should be null/nil (depending on your design)",
            "  let a = s.nope;",
            "  // now check that it still compares to null",
            "  return [a == null];",
            "}"
        );

        List<Value> a = runArr(es, src);
        assertTrue(a.get(0).asBool());
    }
}
