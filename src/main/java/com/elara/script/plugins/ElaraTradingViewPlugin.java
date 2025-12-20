package com.elara.script.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.elara.script.ElaraScript;

/**
 * ElaraTradingViewPlugin
 *
 * First ElaraScript v2 plugin: TradingView-style indicators.
 *
 * Goals:
 * - Provide a clean registration entry point: register(ElaraScript engine)
 * - Implement popular signal generators as pure functions over arrays
 * - No I/O, no time, no host access
 * - Output types are ElaraScript.Value (NUMBER, ARRAY)
 *
 * Notes:
 * - This plugin assumes price series are arrays of numbers.
 * - Functions return arrays aligned to input length.
 *   Missing/insufficient lookback positions return null.
 * - "null" is represented by ElaraScript.Value.nil() in the array.
 */
public final class ElaraTradingViewPlugin {

    private ElaraTradingViewPlugin() {}

    /** Register all TradingView functions into the engine. */
    public static void register(ElaraScript engine) {
        // Moving averages
        engine.registerFunction("sma", args -> {
            requireArgs("sma", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "sma", 0);
            int period = requireInt(args.get(1), "sma", 1);
            return ElaraScript.Value.array(sma(src, period));
        });

        engine.registerFunction("ema", args -> {
            requireArgs("ema", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "ema", 0);
            int period = requireInt(args.get(1), "ema", 1);
            return ElaraScript.Value.array(ema(src, period));
        });

        engine.registerFunction("wma", args -> {
            requireArgs("wma", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "wma", 0);
            int period = requireInt(args.get(1), "wma", 1);
            return ElaraScript.Value.array(wma(src, period));
        });

        // Volatility bands
        engine.registerFunction("bollinger", args -> {
            // bollinger(src, period, mult) -> [upper[], middle[], lower[]]
            requireArgs("bollinger", args, 3);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "bollinger", 0);
            int period = requireInt(args.get(1), "bollinger", 1);
            double mult = requireNumber(args.get(2), "bollinger", 2);

            BollingerBands bb = bollinger(src, period, mult);
            List<ElaraScript.Value> triple = new ArrayList<>();
            triple.add(ElaraScript.Value.array(bb.upper));
            triple.add(ElaraScript.Value.array(bb.middle));
            triple.add(ElaraScript.Value.array(bb.lower));
            return ElaraScript.Value.array(triple);
        });

        // Oscillators
        engine.registerFunction("rsi", args -> {
            requireArgs("rsi", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "rsi", 0);
            int period = requireInt(args.get(1), "rsi", 1);
            return ElaraScript.Value.array(rsi(src, period));
        });

        engine.registerFunction("roc", args -> {
            requireArgs("roc", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "roc", 0);
            int period = requireInt(args.get(1), "roc", 1);
            return ElaraScript.Value.array(roc(src, period));
        });

        engine.registerFunction("stoch", args -> {
            // stoch(high, low, close, kPeriod, dPeriod) -> [k[], d[]]
            requireArgs("stoch", args, 5);
            List<ElaraScript.Value> high = requireNumberArray(args.get(0), "stoch", 0);
            List<ElaraScript.Value> low = requireNumberArray(args.get(1), "stoch", 1);
            List<ElaraScript.Value> close = requireNumberArray(args.get(2), "stoch", 2);
            int kPeriod = requireInt(args.get(3), "stoch", 3);
            int dPeriod = requireInt(args.get(4), "stoch", 4);

            StochOsc st = stoch(high, low, close, kPeriod, dPeriod);
            List<ElaraScript.Value> pair = new ArrayList<>();
            pair.add(ElaraScript.Value.array(st.k));
            pair.add(ElaraScript.Value.array(st.d));
            return ElaraScript.Value.array(pair);
        });

        // Trend
        engine.registerFunction("macd", args -> {
            // macd(src, fast, slow, signal) -> [macd[], signal[], hist[]]
            requireArgs("macd", args, 4);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "macd", 0);
            int fast = requireInt(args.get(1), "macd", 1);
            int slow = requireInt(args.get(2), "macd", 2);
            int signal = requireInt(args.get(3), "macd", 3);

            Macd m = macd(src, fast, slow, signal);
            List<ElaraScript.Value> triple = new ArrayList<>();
            triple.add(ElaraScript.Value.array(m.macd));
            triple.add(ElaraScript.Value.array(m.signal));
            triple.add(ElaraScript.Value.array(m.hist));
            return ElaraScript.Value.array(triple);
        });

        // Utilities
        engine.registerFunction("diff", args -> {
            requireArgs("diff", args, 1);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "diff", 0);
            return ElaraScript.Value.array(diff(src));
        });

        engine.registerFunction("zscore", args -> {
            requireArgs("zscore", args, 2);
            List<ElaraScript.Value> src = requireNumberArray(args.get(0), "zscore", 0);
            int period = requireInt(args.get(1), "zscore", 1);
            return ElaraScript.Value.array(zscore(src, period));
        });
    }

    // ===================== INDICATOR IMPLEMENTATIONS =====================

    private static List<ElaraScript.Value> sma(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "sma period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        double sum = 0.0;
        int valid = 0;

        for (int i = 0; i < n; i++) {
            Double x = asDoubleOrNull(src.get(i));
            if (x == null) {
                // reset window on null (conservative)
                sum = 0.0;
                valid = 0;
                continue;
            }

            sum += x;
            valid++;

            if (valid > period) {
                Double back = asDoubleOrNull(src.get(i - period));
                // if we reset on nulls, back should exist, but keep safe
                if (back != null) sum -= back;
                valid = period;
            }

            if (valid == period) {
                out.set(i, ElaraScript.Value.number(sum / period));
            }
        }

        return out;
    }

    private static List<ElaraScript.Value> ema(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "ema period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        double alpha = 2.0 / (period + 1.0);

        // seed with SMA of first period values (after null resets)
        Double ema = null;
        double sum = 0.0;
        int valid = 0;

        for (int i = 0; i < n; i++) {
            Double x = asDoubleOrNull(src.get(i));
            if (x == null) {
                // reset
                ema = null;
                sum = 0.0;
                valid = 0;
                continue;
            }

            if (ema == null) {
                sum += x;
                valid++;
                if (valid == period) {
                    ema = sum / period;
                    out.set(i, ElaraScript.Value.number(ema));
                }
            } else {
                ema = (x - ema) * alpha + ema;
                out.set(i, ElaraScript.Value.number(ema));
            }
        }

        return out;
    }

    private static List<ElaraScript.Value> wma(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "wma period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        int denom = period * (period + 1) / 2;

        for (int i = period - 1; i < n; i++) {
            double num = 0.0;
            boolean ok = true;
            for (int k = 0; k < period; k++) {
                Double x = asDoubleOrNull(src.get(i - (period - 1 - k)));
                if (x == null) { ok = false; break; }
                int w = k + 1;
                num += x * w;
            }
            if (ok) out.set(i, ElaraScript.Value.number(num / denom));
        }

        return out;
    }

    private static final class BollingerBands {
        final List<ElaraScript.Value> upper;
        final List<ElaraScript.Value> middle;
        final List<ElaraScript.Value> lower;
        BollingerBands(List<ElaraScript.Value> upper, List<ElaraScript.Value> middle, List<ElaraScript.Value> lower) {
            this.upper = upper;
            this.middle = middle;
            this.lower = lower;
        }
    }

    private static BollingerBands bollinger(List<ElaraScript.Value> src, int period, double mult) {
        requirePositive(period, "bollinger period");
        int n = src.size();
        List<ElaraScript.Value> mid = sma(src, period);
        List<ElaraScript.Value> upper = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> lower = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = period - 1; i < n; i++) {
            // only compute when mid exists
            if (mid.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            double mean = mid.get(i).asNumber();

            double sse = 0.0;
            boolean ok = true;
            for (int k = 0; k < period; k++) {
                Double x = asDoubleOrNull(src.get(i - k));
                if (x == null) { ok = false; break; }
                double d = x - mean;
                sse += d * d;
            }
            if (!ok) continue;

            double variance = sse / period;
            double stdev = Math.sqrt(variance);
            upper.set(i, ElaraScript.Value.number(mean + mult * stdev));
            lower.set(i, ElaraScript.Value.number(mean - mult * stdev));
        }

        return new BollingerBands(upper, mid, lower);
    }

    private static List<ElaraScript.Value> rsi(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "rsi period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        Double prev = null;
        double avgGain = 0.0;
        double avgLoss = 0.0;
        int seeded = 0;
        boolean ready = false;

        for (int i = 0; i < n; i++) {
            Double x = asDoubleOrNull(src.get(i));
            if (x == null) {
                prev = null;
                avgGain = 0.0;
                avgLoss = 0.0;
                seeded = 0;
                ready = false;
                continue;
            }

            if (prev == null) {
                prev = x;
                continue;
            }

            double change = x - prev;
            prev = x;

            double gain = Math.max(change, 0.0);
            double loss = Math.max(-change, 0.0);

            if (!ready) {
                avgGain += gain;
                avgLoss += loss;
                seeded++;
                if (seeded == period) {
                    avgGain /= period;
                    avgLoss /= period;
                    ready = true;
                    out.set(i, ElaraScript.Value.number(rsiFromAverages(avgGain, avgLoss)));
                }
            } else {
                // Wilder smoothing
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
                out.set(i, ElaraScript.Value.number(rsiFromAverages(avgGain, avgLoss)));
            }
        }

        return out;
    }

    private static double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static List<ElaraScript.Value> roc(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "roc period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = period; i < n; i++) {
            Double now = asDoubleOrNull(src.get(i));
            Double then = asDoubleOrNull(src.get(i - period));
            if (now == null || then == null) continue;
            if (then == 0.0) continue;
            out.set(i, ElaraScript.Value.number((now - then) / then * 100.0));
        }

        return out;
    }

    private static final class StochOsc {
        final List<ElaraScript.Value> k;
        final List<ElaraScript.Value> d;
        StochOsc(List<ElaraScript.Value> k, List<ElaraScript.Value> d) {
            this.k = k;
            this.d = d;
        }
    }

    private static StochOsc stoch(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                  int kPeriod, int dPeriod) {
        requirePositive(kPeriod, "stoch kPeriod");
        requirePositive(dPeriod, "stoch dPeriod");

        int n = minLen(high.size(), low.size(), close.size());
        List<ElaraScript.Value> k = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = kPeriod - 1; i < n; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            boolean ok = true;

            for (int j = 0; j < kPeriod; j++) {
                Double h = asDoubleOrNull(high.get(i - j));
                Double l = asDoubleOrNull(low.get(i - j));
                if (h == null || l == null) { ok = false; break; }
                if (h > hh) hh = h;
                if (l < ll) ll = l;
            }

            Double c = asDoubleOrNull(close.get(i));
            if (!ok || c == null) continue;
            double denom = (hh - ll);
            if (denom == 0.0) continue;

            double pctK = (c - ll) / denom * 100.0;
            k.set(i, ElaraScript.Value.number(pctK));
        }

        // %D is SMA of %K
        List<ElaraScript.Value> d = sma(k, dPeriod);
        return new StochOsc(k, d);
    }

    private static final class Macd {
        final List<ElaraScript.Value> macd;
        final List<ElaraScript.Value> signal;
        final List<ElaraScript.Value> hist;
        Macd(List<ElaraScript.Value> macd, List<ElaraScript.Value> signal, List<ElaraScript.Value> hist) {
            this.macd = macd;
            this.signal = signal;
            this.hist = hist;
        }
    }

    private static Macd macd(List<ElaraScript.Value> src, int fast, int slow, int signalPeriod) {
        requirePositive(fast, "macd fast");
        requirePositive(slow, "macd slow");
        requirePositive(signalPeriod, "macd signal");
        if (fast >= slow) throw new RuntimeException("macd: fast must be < slow");

        List<ElaraScript.Value> emaFast = ema(src, fast);
        List<ElaraScript.Value> emaSlow = ema(src, slow);

        int n = src.size();
        List<ElaraScript.Value> line = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = 0; i < n; i++) {
            if (emaFast.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            if (emaSlow.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            line.set(i, ElaraScript.Value.number(emaFast.get(i).asNumber() - emaSlow.get(i).asNumber()));
        }

        List<ElaraScript.Value> sig = ema(line, signalPeriod);
        List<ElaraScript.Value> hist = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = 0; i < n; i++) {
            if (line.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            if (sig.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            hist.set(i, ElaraScript.Value.number(line.get(i).asNumber() - sig.get(i).asNumber()));
        }

        return new Macd(line, sig, hist);
    }

    private static List<ElaraScript.Value> diff(List<ElaraScript.Value> src) {
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        Double prev = null;
        for (int i = 0; i < n; i++) {
            Double x = asDoubleOrNull(src.get(i));
            if (x == null) { prev = null; continue; }
            if (prev == null) { prev = x; continue; }
            out.set(i, ElaraScript.Value.number(x - prev));
            prev = x;
        }
        return out;
    }

    private static List<ElaraScript.Value> zscore(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "zscore period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = period - 1; i < n; i++) {
            double sum = 0.0;
            double sumSq = 0.0;
            boolean ok = true;

            for (int j = 0; j < period; j++) {
                Double x = asDoubleOrNull(src.get(i - j));
                if (x == null) { ok = false; break; }
                sum += x;
                sumSq += x * x;
            }

            if (!ok) continue;
            double mean = sum / period;
            double var = (sumSq / period) - (mean * mean);
            if (var < 0.0) var = 0.0;
            double sd = Math.sqrt(var);
            if (sd == 0.0) continue;

            Double xNow = asDoubleOrNull(src.get(i));
            if (xNow == null) continue;
            out.set(i, ElaraScript.Value.number((xNow - mean) / sd));
        }

        return out;
    }

    // ===================== ARG / TYPE HELPERS =====================

    private static void requireArgs(String fn, List<ElaraScript.Value> args, int count) {
        if (args.size() != count) {
            throw new RuntimeException(fn + "() expects " + count + " arguments, got " + args.size());
        }
    }

    private static void requirePositive(int v, String label) {
        if (v <= 0) throw new RuntimeException(label + " must be > 0");
    }

    private static int requireInt(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        }
        double d = v.asNumber();
        if (d != Math.rint(d)) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be an integer");
        }
        int i = (int) d;
        if (i <= 0) throw new RuntimeException(fn + " arg[" + idx + "] must be > 0");
        return i;
    }

    private static double requireNumber(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        }
        return v.asNumber();
    }

    private static List<ElaraScript.Value> requireNumberArray(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be an array");
        }
        List<ElaraScript.Value> arr = v.asArray();
        for (int i = 0; i < arr.size(); i++) {
            ElaraScript.Value item = arr.get(i);
            if (item.getType() == ElaraScript.Value.Type.NULL) continue; // allow nulls
            if (item.getType() != ElaraScript.Value.Type.NUMBER) {
                throw new RuntimeException(fn + " arg[" + idx + "] element[" + i + "] must be number or null");
            }
        }
        return arr;
    }

    private static Double asDoubleOrNull(ElaraScript.Value v) {
        if (v == null) return null;
        if (v.getType() == ElaraScript.Value.Type.NULL) return null;
        if (v.getType() != ElaraScript.Value.Type.NUMBER) return null;
        return v.asNumber();
    }

    private static int minLen(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }
}
