package com.elara.script.plugins;

import java.util.List;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

/**
 * ElaraMathPlugin
 *
 * Extended math functions for ElaraScript v2.
 *
 * These are intentionally NOT built into the core engine to keep it minimal
 * and deterministic. This plugin adds common Math.* style functions.
 *
 * Usage:
 *   ElaraMathPlugin.register(engine);
 *
 * Then in scripts:
 *   let x = pow(2, 8);
 *   let y = sqrt(16);
 *   let z = clamp(a, 0, 1);
 */
public final class ElaraMathPlugin {

    private ElaraMathPlugin() {}

    public static void register(ElaraScript engine) {

        engine.registerFunction("pow", args -> {
            requireArgs("pow", args, 2);
            return Value.number(Math.pow(num(args, 0), num(args, 1)));
        });

        engine.registerFunction("sqrt", args -> {
            requireArgs("sqrt", args, 1);
            return Value.number(Math.sqrt(num(args, 0)));
        });

        engine.registerFunction("cbrt", args -> {
            requireArgs("cbrt", args, 1);
            return Value.number(Math.cbrt(num(args, 0)));
        });

        engine.registerFunction("exp", args -> {
            requireArgs("exp", args, 1);
            return Value.number(Math.exp(num(args, 0)));
        });

        engine.registerFunction("log", args -> {
            requireArgs("log", args, 1);
            return Value.number(Math.log(num(args, 0)));
        });

        engine.registerFunction("log10", args -> {
            requireArgs("log10", args, 1);
            return Value.number(Math.log10(num(args, 0)));
        });

        engine.registerFunction("abs", args -> {
            requireArgs("abs", args, 1);
            return Value.number(Math.abs(num(args, 0)));
        });

        engine.registerFunction("min", args -> {
            requireArgs("min", args, 2);
            return Value.number(Math.min(num(args, 0), num(args, 1)));
        });

        engine.registerFunction("max", args -> {
            requireArgs("max", args, 2);
            return Value.number(Math.max(num(args, 0), num(args, 1)));
        });

        engine.registerFunction("clamp", args -> {
            requireArgs("clamp", args, 3);
            double v = num(args, 0);
            double lo = num(args, 1);
            double hi = num(args, 2);
            return Value.number(Math.max(lo, Math.min(hi, v)));
        });

        engine.registerFunction("round", args -> {
            requireArgs("round", args, 1);
            return Value.number(Math.round(num(args, 0)));
        });

        engine.registerFunction("floor", args -> {
            requireArgs("floor", args, 1);
            return Value.number(Math.floor(num(args, 0)));
        });

        engine.registerFunction("ceil", args -> {
            requireArgs("ceil", args, 1);
            return Value.number(Math.ceil(num(args, 0)));
        });

        engine.registerFunction("sign", args -> {
            requireArgs("sign", args, 1);
            return Value.number(Math.signum(num(args, 0)));
        });

        engine.registerFunction("lerp", args -> {
            // linear interpolation: lerp(a, b, t)
            requireArgs("lerp", args, 3);
            double a = num(args, 0);
            double b = num(args, 1);
            double t = num(args, 2);
            return Value.number(a + (b - a) * t);
        });

        engine.registerFunction("deg2rad", args -> {
            requireArgs("deg2rad", args, 1);
            return Value.number(Math.toRadians(num(args, 0)));
        });

        engine.registerFunction("rad2deg", args -> {
            requireArgs("rad2deg", args, 1);
            return Value.number(Math.toDegrees(num(args, 0)));
        });

        engine.registerFunction("sin", args -> {
            requireArgs("sin", args, 1);
            return Value.number(Math.sin(num(args, 0)));
        });

        engine.registerFunction("cos", args -> {
            requireArgs("cos", args, 1);
            return Value.number(Math.cos(num(args, 0)));
        });

        engine.registerFunction("tan", args -> {
            requireArgs("tan", args, 1);
            return Value.number(Math.tan(num(args, 0)));
        });

        engine.registerFunction("asin", args -> {
            requireArgs("asin", args, 1);
            return Value.number(Math.asin(num(args, 0)));
        });

        engine.registerFunction("acos", args -> {
            requireArgs("acos", args, 1);
            return Value.number(Math.acos(num(args, 0)));
        });

        engine.registerFunction("atan", args -> {
            requireArgs("atan", args, 1);
            return Value.number(Math.atan(num(args, 0)));
        });

        engine.registerFunction("atan2", args -> {
            requireArgs("atan2", args, 2);
            return Value.number(Math.atan2(num(args, 0), num(args, 1)));
        });
    }

    // ===================== HELPERS =====================

    private static void requireArgs(String fn, List<Value> args, int n) {
        if (args.size() != n) {
            throw new RuntimeException(fn + "() expects " + n + " arguments, got " + args.size());
        }
    }

    private static double num(List<Value> args, int idx) {
        Value v = args.get(idx);
        if (v.getType() != Value.Type.NUMBER) {
            throw new RuntimeException("Argument " + idx + " must be a number");
        }
        return v.asNumber();
    }
}
