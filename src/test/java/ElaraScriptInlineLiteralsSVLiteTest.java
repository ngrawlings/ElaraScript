import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ElaraScriptInlineLiteralsSVLiteTest {

    // svlite.es (embedded)
    // NOTE: This is intentionally verbatim-ish to match your real file patterns.
    private static final String SVLITE = ""
            + "function sv_ctx() { return []; }\n"
            + "function sv_id(vector, instance) {\n"
            + "  if (vector == null) vector = \"\";\n"
            + "  if (instance == null) instance = \"\";\n"
            + "  return vector + \".\" + instance;\n"
            + "}\n"
            + "function sv_push(ctx, op, payload) {\n"
            + "  if (ctx == null) ctx = [];\n"
            + "  ctx.push([op, payload]);\n"   // <-- suspect
            + "  return ctx;\n"
            + "}\n"
            + "function sv_obj() { return {}; }\n"
            + "function sv_default_num(v, d) { if (v == null) return d; return v; }\n"
            + "function sv_merge(target, spec) {\n"
            + "  if (spec == null) return target;\n"
            + "  for (let k in spec) { target[k] = spec[k]; }\n"
            + "  return target;\n"
            + "}\n"
            + "function sv_create(ctx, vector, instance, spec, x, y, z, sx, sy, r, a) {\n"
            + "  let id = sv_id(vector, instance);\n"
            + "  let p = sv_obj();\n"
            + "  p.id = id; p.vector = vector; p.instance = instance;\n"
            + "  sv_merge(p, spec);\n"
            + "  p.x = sv_default_num(x, 0);\n"
            + "  p.y = sv_default_num(y, 0);\n"
            + "  p.z = sv_default_num(z, 0);\n"
            + "  p.sx = sv_default_num(sx, 1);\n"
            + "  p.sy = sv_default_num(sy, 1);\n"
            + "  p.r = sv_default_num(r, 0);\n"
            + "  p.a = sv_default_num(a, 1);\n"
            + "  return sv_push(ctx, \"create\", p);\n"
            + "}\n";

    private static Value runMain(String programBody) {
        ElaraScript es = new ElaraScript();
        String src = SVLITE
                + "function main() {\n"
                + programBody + "\n"
                + "}\n";
        return es.run(src, "main", List.of());
    }

    // ------------------------------------------------------------
    // The exact failing pattern from svlite.es
    // ------------------------------------------------------------

    @Test
    public void sv_push_memberCall_withInlineArrayLiteralArg() {
        // This is basically: ctx.push([op, payload])
        Value out = runMain(
                "let ctx = [];\n"
                        + "let payload = {a: 1};\n"
                        + "sv_push(ctx, \"op\", payload);\n"
                        + "return ctx;"
        );

        assertEquals(Value.Type.ARRAY, out.getType());
        List<Value> ctx = out.asArray();
        assertEquals(1, ctx.size());

        // ctx[0] should be [ "op", {a:1} ]
        List<Value> entry = ctx.get(0).asArray();
        assertEquals(2, entry.size());
        assertEquals("op", entry.get(0).asString());
        assertEquals(1.0, entry.get(1).asMap().get("a").asNumber(), 0.0);
    }

    // ------------------------------------------------------------
    // Minimal reproductions (no svlite helpers) to isolate parser
    // ------------------------------------------------------------

    @Test
    public void memberCall_push_withInlineArrayLiteral_shouldParse() {
        Value out = runMain(
                "let ctx = [];\n"
                        + "ctx.push([1,2]);\n"
                        + "return ctx;"
        );

        List<Value> ctx = out.asArray();
        assertEquals(1, ctx.size());
        List<Value> pushed = ctx.get(0).asArray();
        assertEquals(1.0, pushed.get(0).asNumber(), 0.0);
        assertEquals(2.0, pushed.get(1).asNumber(), 0.0);
    }

    @Test
    public void memberCall_push_withInlineMapLiteral_shouldParse() {
        Value out = runMain(
                "let ctx = [];\n"
                        + "ctx.push({a: 1, b: 2});\n"
                        + "return ctx;"
        );

        List<Value> ctx = out.asArray();
        assertEquals(1, ctx.size());
        Map<String, Value> pushed = ctx.get(0).asMap();
        assertEquals(1.0, pushed.get("a").asNumber(), 0.0);
        assertEquals(2.0, pushed.get("b").asNumber(), 0.0);
    }

    @Test
    public void memberCall_push_withNestedInlineLiterals_shouldParse() {
        Value out = runMain(
                "let ctx = [];\n"
                        + "ctx.push([\"op\", {a: 1}, [2,3]]);\n"
                        + "return ctx;"
        );

        List<Value> ctx = out.asArray();
        assertEquals(1, ctx.size());

        List<Value> pushed = ctx.get(0).asArray();
        assertEquals("op", pushed.get(0).asString());
        assertEquals(1.0, pushed.get(1).asMap().get("a").asNumber(), 0.0);

        List<Value> inner = pushed.get(2).asArray();
        assertEquals(2.0, inner.get(0).asNumber(), 0.0);
        assertEquals(3.0, inner.get(1).asNumber(), 0.0);
    }

    // ------------------------------------------------------------
    // Realistic svlite "create" path: sv_create -> sv_push -> ctx.push([..])
    // ------------------------------------------------------------

    @Test
    public void sv_create_path_shouldProduceCreateOpTuple() {
        Value out = runMain(
                "let ctx = [];\n"
                        + "sv_create(ctx, \"vec\", \"inst\", {color:\"red\"}, 10, 20, null, null, null, null, null);\n"
                        + "return ctx;"
        );

        List<Value> ctx = out.asArray();
        assertEquals(1, ctx.size());

        List<Value> op = ctx.get(0).asArray();
        assertEquals("create", op.get(0).asString());

        Map<String, Value> p = op.get(1).asMap();
        assertEquals("vec.inst", p.get("id").asString());
        assertEquals("vec", p.get("vector").asString());
        assertEquals("inst", p.get("instance").asString());
        assertEquals(10.0, p.get("x").asNumber(), 0.0);
        assertEquals(20.0, p.get("y").asNumber(), 0.0);
        assertEquals(0.0, p.get("z").asNumber(), 0.0);
        assertEquals(1.0, p.get("sx").asNumber(), 0.0);
        assertEquals(1.0, p.get("sy").asNumber(), 0.0);
        assertEquals(0.0, p.get("r").asNumber(), 0.0);
        assertEquals(1.0, p.get("a").asNumber(), 0.0);

        // merged spec
        assertEquals("red", p.get("color").asString());
    }
}
