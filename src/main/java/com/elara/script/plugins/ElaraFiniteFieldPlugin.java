package com.elara.script.plugins;

import java.util.List;

import com.elara.script.ElaraScript;

/**
 * ElaraFiniteFieldPlugin
 *
 * Finite field / modular arithmetic helpers for ElaraScript v2.
 *
 * Scope:
 * - This plugin implements modular arithmetic over integers (Z_n).
 * - In particular, multiplicative inverse modulo n (when it exists).
 *
 * IMPORTANT:
 * - This operates in Z_n (integers modulo n). This is a ring for composite n,
 *   and a field only when n is prime.
 * - inv_mod(a, n) exists iff gcd(a, n) == 1.
 *
 * Functions:
 * - mod(a, n)            -> a mod n (normalized 0..n-1)
 * - gcd(a, b)            -> greatest common divisor
 * - egcd(a, b)           -> [g, x, y] such that ax + by = g
 * - inv_mod(a, n)        -> multiplicative inverse of a modulo n
 * - pow_mod(a, e, n)     -> a^e mod n  (supports e >= 0)
 * - add_mod(a, b, n)     -> (a + b) mod n
 * - sub_mod(a, b, n)     -> (a - b) mod n
 * - mul_mod(a, b, n)     -> (a * b) mod n (uses long intermediate)
 */
public final class ElaraFiniteFieldPlugin {

    private ElaraFiniteFieldPlugin() {}

    public static void register(ElaraScript engine) {

        engine.registerFunction("mod", args -> {
            requireArgs("mod", args, 2);
            long a = requireInt64(args.get(0), "mod", 0);
            long n = requirePosInt64(args.get(1), "mod", 1);
            return ElaraScript.Value.number(mod(a, n));
        });

        engine.registerFunction("gcd", args -> {
            requireArgs("gcd", args, 2);
            long a = requireInt64(args.get(0), "gcd", 0);
            long b = requireInt64(args.get(1), "gcd", 1);
            return ElaraScript.Value.number(gcd(a, b));
        });

        engine.registerFunction("egcd", args -> {
            requireArgs("egcd", args, 2);
            long a = requireInt64(args.get(0), "egcd", 0);
            long b = requireInt64(args.get(1), "egcd", 1);
            EGcd r = egcd(a, b);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.number(r.g),
                    ElaraScript.Value.number(r.x),
                    ElaraScript.Value.number(r.y)
            ));
        });

        engine.registerFunction("inv_mod", args -> {
            requireArgs("inv_mod", args, 2);
            long a = requireInt64(args.get(0), "inv_mod", 0);
            long n = requirePosInt64(args.get(1), "inv_mod", 1);
            Long inv = invMod(a, n);
            if (inv == null) {
                // No inverse exists; return null to keep scripts flow-friendly.
                return ElaraScript.Value.nil();
            }
            return ElaraScript.Value.number(inv);
        });

        engine.registerFunction("pow_mod", args -> {
            requireArgs("pow_mod", args, 3);
            long a = requireInt64(args.get(0), "pow_mod", 0);
            long e = requireNonNegInt64(args.get(1), "pow_mod", 1);
            long n = requirePosInt64(args.get(2), "pow_mod", 2);
            return ElaraScript.Value.number(powMod(a, e, n));
        });

        engine.registerFunction("add_mod", args -> {
            requireArgs("add_mod", args, 3);
            long a = requireInt64(args.get(0), "add_mod", 0);
            long b = requireInt64(args.get(1), "add_mod", 1);
            long n = requirePosInt64(args.get(2), "add_mod", 2);
            return ElaraScript.Value.number(mod(a + b, n));
        });

        engine.registerFunction("sub_mod", args -> {
            requireArgs("sub_mod", args, 3);
            long a = requireInt64(args.get(0), "sub_mod", 0);
            long b = requireInt64(args.get(1), "sub_mod", 1);
            long n = requirePosInt64(args.get(2), "sub_mod", 2);
            return ElaraScript.Value.number(mod(a - b, n));
        });

        engine.registerFunction("mul_mod", args -> {
            requireArgs("mul_mod", args, 3);
            long a = requireInt64(args.get(0), "mul_mod", 0);
            long b = requireInt64(args.get(1), "mul_mod", 1);
            long n = requirePosInt64(args.get(2), "mul_mod", 2);
            return ElaraScript.Value.number(mulMod(a, b, n));
        });
    }

    // ===================== MATH =====================

    private static long mod(long a, long n) {
        long r = a % n;
        if (r < 0) r += n;
        return r;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static final class EGcd {
        final long g;
        final long x;
        final long y;
        EGcd(long g, long x, long y) { this.g = g; this.x = x; this.y = y; }
    }

    // Extended Euclidean algorithm.
    private static EGcd egcd(long a, long b) {
        if (b == 0) {
            return new EGcd(Math.abs(a), a >= 0 ? 1 : -1, 0);
        }
        long oldR = a, r = b;
        long oldS = 1, s = 0;
        long oldT = 0, t = 1;

        while (r != 0) {
            long q = oldR / r;

            long tmpR = oldR - q * r;
            oldR = r;
            r = tmpR;

            long tmpS = oldS - q * s;
            oldS = s;
            s = tmpS;

            long tmpT = oldT - q * t;
            oldT = t;
            t = tmpT;
        }

        long g = oldR;
        if (g < 0) {
            g = -g;
            oldS = -oldS;
            oldT = -oldT;
        }
        return new EGcd(g, oldS, oldT);
    }

    /**
     * Multiplicative inverse of a modulo n, or null if none exists.
     * Returns value in range [0, n-1].
     */
    private static Long invMod(long a, long n) {
        EGcd r = egcd(a, n);
        if (r.g != 1) return null;
        return mod(r.x, n);
    }

    /** Fast pow: a^e mod n for e >= 0. */
    private static long powMod(long a, long e, long n) {
        long base = mod(a, n);
        long result = 1 % n;
        long exp = e;
        while (exp > 0) {
            if ((exp & 1L) == 1L) result = mulMod(result, base, n);
            base = mulMod(base, base, n);
            exp >>= 1;
        }
        return result;
    }

    /**
     * Multiply modulo n using long intermediates.
     *
     * Note: This is safe from overflow only when |a*b| fits in signed 64-bit.
     * For typical app use (small/moderate n), this is fine.
     * If you later need huge moduli, we can switch to BigInteger.
     */
    private static long mulMod(long a, long b, long n) {
        long x = mod(a, n);
        long y = mod(b, n);

        // Try fast path with Math.multiplyExact if it fits.
        try {
            long prod = Math.multiplyExact(x, y);
            return mod(prod, n);
        } catch (ArithmeticException overflow) {
            // Fallback: double-and-add method (always safe from overflow).
            long res = 0;
            while (y > 0) {
                if ((y & 1L) == 1L) res = mod(res + x, n);
                x = mod(x + x, n);
                y >>= 1;
            }
            return res;
        }
    }

    // ===================== ARG HELPERS =====================

    private static void requireArgs(String fn, List<ElaraScript.Value> args, int n) {
        if (args.size() != n) {
            throw new RuntimeException(fn + "() expects " + n + " arguments, got " + args.size());
        }
    }

    private static long requireInt64(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        }
        double d = v.asNumber();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a finite integer");
        }
        if (d != Math.rint(d)) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be an integer");
        }
        return (long) d;
    }

    private static long requirePosInt64(ElaraScript.Value v, String fn, int idx) {
        long x = requireInt64(v, fn, idx);
        if (x <= 0) throw new RuntimeException(fn + " arg[" + idx + "] must be > 0");
        return x;
    }

    private static long requireNonNegInt64(ElaraScript.Value v, String fn, int idx) {
        long x = requireInt64(v, fn, idx);
        if (x < 0) throw new RuntimeException(fn + " arg[" + idx + "] must be >= 0");
        return x;
    }
}
