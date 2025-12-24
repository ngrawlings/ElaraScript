package com.elara.script.plugins;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SVLite v0 (+ masks v0.2 + overlays v0.3)
 *
 * Commands: ["op", {...payload...}]
 *
 * ID convention (enforced): vector_name.instance_name
 *
 * Identity rules:
 * - create: stamps {id, vector, instance}
 * - ALL other ops: payload MUST include {id, vector, instance} (plugin stamps these)
 *
 * Instance canvas metadata (create ensures):
 *   x, y, z, sx, sy, r, a
 *
 * Masks v0.2:
 * - masks.set replaces active masks list for a target instance.
 * - Masks inherit target x,y,z,r,sx,sy (renderer responsibility).
 * - Each mask entry: { vector, instance, a } where a is invert flag 0/1
 *
 * Overlays v0.3:
 * - overlays.set replaces active overlays list for a target instance.
 * - Overlays inherit target x,y,z,r,sx,sy (renderer responsibility).
 * - Overlays share metadata except alpha: overlay has no separate alpha field.
 *   Alpha comes from fill color's alpha channel and blend behavior.
 * - Each overlay entry: { vector, instance, fill, blend }
 *   fill: "#AARRGGBB" or "#RRGGBB"
 *   blend: "srcOver|multiply|screen|overlay|srcATop" (default "srcOver")
 */
public final class SvLitePlugin {

    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern COLOR_PATTERN =
            Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private SvLitePlugin() {}

    public static void register(ElaraScript engine) {

        // sv_cmds() -> []
        engine.registerFunction("sv_cmds", args -> {
            if (!args.isEmpty()) throw new RuntimeException("sv_cmds() expects 0 args");
            return Value.array(new ArrayList<>());
        });

        /**
         * sv_emit(cmds, op, payloadMap) -> cmds
         * Escape hatch (no canonical enforcement beyond type checks).
         */
        engine.registerFunction("sv_emit", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_emit(cmds, op, payload) expects 3 args");
            Value cmds = args.get(0);
            Value op   = args.get(1);
            Value pay  = args.get(2);

            requireArray("sv_emit", cmds);
            requireString("sv_emit", op);
            requireMap("sv_emit", pay);

            append(cmds.asArray(), op.asString(), pay);
            return cmds;
        });

        // sv_create(cmds, "vector.instance", payloadMap) -> cmds
        engine.registerFunction("sv_create", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_create(cmds, id, payload) expects 3 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value payV = args.get(2);

            requireArray("sv_create", cmds);
            requireString("sv_create", idV);
            requireMap("sv_create", payV);

            final String id = idV.asString();
            validateId(id);

            final String[] parts = splitId(id);
            final String vectorName = parts[0];
            final String instanceName = parts[1];

            Map<String, Value> payload = new LinkedHashMap<>(payV.asMap());

            payload.put("id", Value.string(id));
            payload.put("vector", Value.string(vectorName));
            payload.put("instance", Value.string(instanceName));

            CanvasMeta meta = extractAndNormalizeCanvasMeta(payload);
            payload.put("x",  Value.number(meta.x));
            payload.put("y",  Value.number(meta.y));
            payload.put("z",  Value.number(meta.z));
            payload.put("sx", Value.number(meta.sx));
            payload.put("sy", Value.number(meta.sy));
            payload.put("r",  Value.number(meta.r));
            payload.put("a",  Value.number(meta.a));

            append(cmds.asArray(), "create", Value.map(payload));
            return cmds;
        });

        // sv_remove(cmds, id) -> cmds
        engine.registerFunction("sv_remove", args -> {
            if (args.size() != 2) throw new RuntimeException("sv_remove(cmds, id) expects 2 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);

            requireArray("sv_remove", cmds);
            requireString("sv_remove", idV);

            Map<String, Value> p = idPayload(idV.asString());
            append(cmds.asArray(), "remove", Value.map(p));
            return cmds;
        });

        // sv_move(cmds, id, x, y) -> cmds
        engine.registerFunction("sv_move", args -> {
            if (args.size() != 4) throw new RuntimeException("sv_move(cmds, id, x, y) expects 4 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value xV   = args.get(2);
            Value yV   = args.get(3);

            requireArray("sv_move", cmds);
            requireString("sv_move", idV);
            requireNumber("sv_move", xV);
            requireNumber("sv_move", yV);

            Map<String, Value> p = idPayload(idV.asString());
            p.put("x", xV);
            p.put("y", yV);

            append(cmds.asArray(), "move", Value.map(p));
            return cmds;
        });

        // sv_rotate(cmds, id, r) -> cmds
        engine.registerFunction("sv_rotate", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_rotate(cmds, id, r) expects 3 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value rV   = args.get(2);

            requireArray("sv_rotate", cmds);
            requireString("sv_rotate", idV);
            requireNumber("sv_rotate", rV);

            Map<String, Value> p = idPayload(idV.asString());
            p.put("r", rV);

            append(cmds.asArray(), "rotate", Value.map(p));
            return cmds;
        });

        // sv_scale(cmds, id, sx, sy) -> cmds
        engine.registerFunction("sv_scale", args -> {
            if (args.size() != 4) throw new RuntimeException("sv_scale(cmds, id, sx, sy) expects 4 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value sxV  = args.get(2);
            Value syV  = args.get(3);

            requireArray("sv_scale", cmds);
            requireString("sv_scale", idV);
            requireNumber("sv_scale", sxV);
            requireNumber("sv_scale", syV);

            Map<String, Value> p = idPayload(idV.asString());
            p.put("sx", sxV);
            p.put("sy", syV);

            append(cmds.asArray(), "scale", Value.map(p));
            return cmds;
        });

        // sv_alpha(cmds, id, a) -> cmds (clamped 0..1)
        engine.registerFunction("sv_alpha", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_alpha(cmds, id, a) expects 3 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value aV   = args.get(2);

            requireArray("sv_alpha", cmds);
            requireString("sv_alpha", idV);
            requireNumber("sv_alpha", aV);

            double a = aV.asNumber();
            if (a < 0) a = 0;
            if (a > 1) a = 1;

            Map<String, Value> p = idPayload(idV.asString());
            p.put("a", Value.number(a));

            append(cmds.asArray(), "alpha", Value.map(p));
            return cmds;
        });

        // sv_z(cmds, id, z) -> cmds
        engine.registerFunction("sv_z", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_z(cmds, id, z) expects 3 args");
            Value cmds = args.get(0);
            Value idV  = args.get(1);
            Value zV   = args.get(2);

            requireArray("sv_z", cmds);
            requireString("sv_z", idV);
            requireNumber("sv_z", zV);

            Map<String, Value> p = idPayload(idV.asString());
            p.put("z", zV);

            append(cmds.asArray(), "z", Value.map(p));
            return cmds;
        });

        /**
         * sv_masks(cmds, targetId, masksArray) -> cmds
         *
         * Emits: ["masks.set", { targetIdentity..., masks:[ {vector,instance,a} ... ] }]
         *
         * masksArray entries:
         * - "vector.instance"                       -> defaults a=1 (invert)
         * - { id:"vector.instance", a:0|1 }         -> explicit
         * - { vector:"v", instance:"i", a:0|1 }     -> explicit
         */
        engine.registerFunction("sv_masks", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_masks(cmds, targetId, masks) expects 3 args");

            Value cmds = args.get(0);
            Value targetIdV = args.get(1);
            Value masksV = args.get(2);

            requireArray("sv_masks", cmds);
            requireString("sv_masks", targetIdV);
            requireArray("sv_masks", masksV);

            Map<String, Value> payload = idPayload(targetIdV.asString());

            List<Value> outMasks = new ArrayList<>();
            for (Value entry : masksV.asArray()) {
                outMasks.add(parseMaskEntry(entry));
            }

            payload.put("masks", Value.array(outMasks));
            append(cmds.asArray(), "masks.set", Value.map(payload));
            return cmds;
        });

        /**
         * sv_overlays(cmds, targetId, overlaysArray) -> cmds
         *
         * Emits: ["overlays.set", { targetIdentity..., overlays:[ {vector,instance,fill,blend} ... ] }]
         *
         * overlaysArray entries:
         * - "vector.instance"  -> defaults fill="#FFFFFFFF", blend="srcOver"
         * - { id:"vector.instance", fill:"#AARRGGBB", blend:"multiply" }
         * - { vector:"v", instance:"i", fill:"#...", blend:"..." }
         *
         * Overlays have no independent alpha field. Use fill alpha channel instead.
         */
        engine.registerFunction("sv_overlays", args -> {
            if (args.size() != 3) throw new RuntimeException("sv_overlays(cmds, targetId, overlays) expects 3 args");

            Value cmds = args.get(0);
            Value targetIdV = args.get(1);
            Value overlaysV = args.get(2);

            requireArray("sv_overlays", cmds);
            requireString("sv_overlays", targetIdV);
            requireArray("sv_overlays", overlaysV);

            Map<String, Value> payload = idPayload(targetIdV.asString());

            List<Value> out = new ArrayList<>();
            for (Value entry : overlaysV.asArray()) {
                out.add(parseOverlayEntry(entry));
            }

            payload.put("overlays", Value.array(out));
            append(cmds.asArray(), "overlays.set", Value.map(payload));
            return cmds;
        });
    }

    // -------------------------
    // Masks (resource selectors)
    // -------------------------

    private static Value parseMaskEntry(Value entry) {

        // string id: default invert (a=1)
        if (entry.getType() == Value.Type.STRING) {
            String id = entry.asString();
            validateId(id);
            String[] parts = splitId(id);

            Map<String, Value> m = new LinkedHashMap<>();
            m.put("vector", Value.string(parts[0]));
            m.put("instance", Value.string(parts[1]));
            m.put("a", Value.number(1));
            return Value.map(m);
        }

        if (entry.getType() == Value.Type.MAP) {
            Map<String, Value> in = entry.asMap();

            String[] vi = parseVectorInstance(in);
            String vector = vi[0], instance = vi[1];

            int inv = 1; // default invert
            Value aV = in.get("a");
            if (aV != null) {
                if (aV.getType() != Value.Type.NUMBER) {
                    throw new RuntimeException("sv_masks: mask 'a' must be number 0 or 1");
                }
                inv = (aV.asNumber() >= 0.5) ? 1 : 0;
            }

            Map<String, Value> m = new LinkedHashMap<>();
            m.put("vector", Value.string(vector));
            m.put("instance", Value.string(instance));
            m.put("a", Value.number(inv));
            return Value.map(m);
        }

        throw new RuntimeException("sv_masks: each mask entry must be string id or map");
    }

    // -------------------------
    // Overlays (resource selectors)
    // -------------------------

    private static final Set<String> ALLOWED_BLEND = new HashSet<>(Arrays.asList(
            "srcOver", "multiply", "screen", "overlay", "srcATop"
    ));

    private static Value parseOverlayEntry(Value entry) {

        // string id: defaults
        if (entry.getType() == Value.Type.STRING) {
            String id = entry.asString();
            validateId(id);
            String[] parts = splitId(id);

            Map<String, Value> o = new LinkedHashMap<>();
            o.put("vector", Value.string(parts[0]));
            o.put("instance", Value.string(parts[1]));
            o.put("fill", Value.string("#FFFFFFFF"));
            o.put("blend", Value.string("srcOver"));
            return Value.map(o);
        }

        if (entry.getType() == Value.Type.MAP) {
            Map<String, Value> in = entry.asMap();

            String[] vi = parseVectorInstance(in);
            String vector = vi[0], instance = vi[1];

            String fill = "#FFFFFFFF";
            Value fillV = in.get("fill");
            if (fillV != null) {
                if (fillV.getType() != Value.Type.STRING) {
                    throw new RuntimeException("sv_overlays: 'fill' must be string '#RRGGBB' or '#AARRGGBB'");
                }
                fill = fillV.asString();
                if (!COLOR_PATTERN.matcher(fill).matches()) {
                    throw new RuntimeException("sv_overlays: fill must be '#RRGGBB' or '#AARRGGBB'. Got: " + fill);
                }
            }

            String blend = "srcOver";
            Value blendV = in.get("blend");
            if (blendV != null) {
                if (blendV.getType() != Value.Type.STRING) {
                    throw new RuntimeException("sv_overlays: 'blend' must be string");
                }
                blend = blendV.asString();
            }
            if (!ALLOWED_BLEND.contains(blend)) {
                throw new RuntimeException("sv_overlays: blend must be one of " + ALLOWED_BLEND + ". Got: " + blend);
            }

            Map<String, Value> o = new LinkedHashMap<>();
            o.put("vector", Value.string(vector));
            o.put("instance", Value.string(instance));
            o.put("fill", Value.string(fill));
            o.put("blend", Value.string(blend));
            return Value.map(o);
        }

        throw new RuntimeException("sv_overlays: each overlay entry must be string id or map");
    }

    /**
     * Parses vector/instance from either:
     * - { id:"vector.instance" }
     * - { vector:"v", instance:"i" }
     */
    private static String[] parseVectorInstance(Map<String, Value> in) {
        Value idV = in.get("id");
        if (idV != null) {
            if (idV.getType() != Value.Type.STRING) {
                throw new RuntimeException("SVLite: 'id' must be string if provided");
            }
            String id = idV.asString();
            validateId(id);
            return splitId(id);
        }

        Value vV = in.get("vector");
        Value iV = in.get("instance");
        if (vV == null || iV == null ||
                vV.getType() != Value.Type.STRING ||
                iV.getType() != Value.Type.STRING) {
            throw new RuntimeException("SVLite: entry must contain either 'id' or ('vector' and 'instance')");
        }

        String vector = vV.asString();
        String instance = iV.asString();
        if (!NAME_PATTERN.matcher(vector).matches() || !NAME_PATTERN.matcher(instance).matches()) {
            throw new RuntimeException("SVLite: vector/instance must use [A-Za-z0-9_-]");
        }
        return new String[] { vector, instance };
    }

    // -------------------------
    // Canonical canvas meta
    // -------------------------

    private static final class CanvasMeta {
        final double x, y, z, sx, sy, r, a;
        CanvasMeta(double x, double y, double z, double sx, double sy, double r, double a) {
            this.x = x; this.y = y; this.z = z; this.sx = sx; this.sy = sy; this.r = r; this.a = a;
        }
    }

    private static CanvasMeta extractAndNormalizeCanvasMeta(Map<String, Value> payload) {

        double x = 0, y = 0, r = 0, sx = 1, sy = 1;
        double z = 0;
        double a = 1;

        Value xformV = payload.get("xform");
        if (xformV != null && xformV.getType() == Value.Type.MAP) {
            Map<String, Value> xform = xformV.asMap();
            x  = optNum(xform.get("x"), 0);
            y  = optNum(xform.get("y"), 0);
            r  = optNum(xform.get("r"), 0);
            sx = optNum(xform.get("sx"), 1);
            sy = optNum(xform.get("sy"), 1);
        }

        x  = optNum(payload.get("x"), x);
        y  = optNum(payload.get("y"), y);
        r  = optNum(payload.get("r"), r);
        sx = optNum(payload.get("sx"), sx);
        sy = optNum(payload.get("sy"), sy);

        z = optNum(payload.get("z"), 0);

        a = optNum(payload.get("a"), optNum(payload.get("alpha"), 1));
        if (a < 0) a = 0;
        if (a > 1) a = 1;

        return new CanvasMeta(x, y, z, sx, sy, r, a);
    }

    private static double optNum(Value v, double dflt) {
        if (v == null) return dflt;
        if (v.getType() != Value.Type.NUMBER) return dflt;
        return v.asNumber();
    }

    // -------------------------
    // ID / naming
    // -------------------------

    private static void validateId(String id) {
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new RuntimeException(
                    "SVLite id must be 'vector_name.instance_name' using [A-Za-z0-9_-]. Got: " + id
            );
        }
    }

    private static String[] splitId(String id) {
        int dot = id.indexOf('.');
        return new String[] { id.substring(0, dot), id.substring(dot + 1) };
    }

    /** Canonical identity payload for non-create commands: { id, vector, instance } */
    private static Map<String, Value> idPayload(String id) {
        validateId(id);
        String[] parts = splitId(id);
        Map<String, Value> m = new LinkedHashMap<>();
        m.put("id", Value.string(id));
        m.put("vector", Value.string(parts[0]));
        m.put("instance", Value.string(parts[1]));
        return m;
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static void append(List<Value> cmds, String op, Value payload) {
        List<Value> cmd = new ArrayList<>(2);
        cmd.add(Value.string(op));
        cmd.add(payload);
        cmds.add(Value.array(cmd));
    }

    private static void requireArray(String fn, Value v) {
        if (v.getType() != Value.Type.ARRAY) throw new RuntimeException(fn + ": expected array");
    }

    private static void requireMap(String fn, Value v) {
        if (v.getType() != Value.Type.MAP) throw new RuntimeException(fn + ": expected map");
    }

    private static void requireString(String fn, Value v) {
        if (v.getType() != Value.Type.STRING) throw new RuntimeException(fn + ": expected string");
    }

    private static void requireNumber(String fn, Value v) {
        if (v.getType() != Value.Type.NUMBER) throw new RuntimeException(fn + ": expected number");
    }
}
