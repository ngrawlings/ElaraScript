package com.elara.script.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.elara.script.ElaraScript;
import com.elara.script.parser.Value;

/**
 * ElaraTradingViewPlugin
 *
 * First ElaraScript v2 plugin: TradingView-style indicators.
 *
 * Goals:
 * - Provide a clean registration entry point: register(ElaraScript engine)
 * - Implement popular signal generators as pure functions over arrays
 * - No I/O, no time, no host access
 * - Output types are Value (NUMBER, ARRAY)
 *
 * Notes:
 * - This plugin assumes price series are arrays of numbers.
 * - Functions return arrays aligned to input length.
 *   Missing/insufficient lookback positions return null.
 * - "null" is represented by Value.nil() in the array.
 */
public final class ElaraTradingViewPlugin {

    private ElaraTradingViewPlugin() {}

    /** Register all TradingView functions into the engine. */
    public static void register(ElaraScript engine) {
        // Moving averages
        engine.registerFunction("sma", args -> {
            requireArgs("sma", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "sma", 0);
            int period = requireInt(args.get(1), "sma", 1);
            return Value.array(sma(src, period));
        });

        engine.registerFunction("ema", args -> {
            requireArgs("ema", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "ema", 0);
            int period = requireInt(args.get(1), "ema", 1);
            return Value.array(ema(src, period));
        });

        engine.registerFunction("wma", args -> {
            requireArgs("wma", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "wma", 0);
            int period = requireInt(args.get(1), "wma", 1);
            return Value.array(wma(src, period));
        });

        // Volatility bands
        engine.registerFunction("bollinger", args -> {
            // bollinger(src, period, mult) -> [upper[], middle[], lower[]]
            requireArgs("bollinger", args, 3);
            List<Value> src = requireNumberArray(args.get(0), "bollinger", 0);
            int period = requireInt(args.get(1), "bollinger", 1);
            double mult = requireNumber(args.get(2), "bollinger", 2);

            BollingerBands bb = bollinger(src, period, mult);
            List<Value> triple = new ArrayList<>();
            triple.add(Value.array(bb.upper));
            triple.add(Value.array(bb.middle));
            triple.add(Value.array(bb.lower));
            return Value.array(triple);
        });

        // Oscillators
        engine.registerFunction("rsi", args -> {
            requireArgs("rsi", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "rsi", 0);
            int period = requireInt(args.get(1), "rsi", 1);
            return Value.array(rsi(src, period));
        });

        engine.registerFunction("roc", args -> {
            requireArgs("roc", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "roc", 0);
            int period = requireInt(args.get(1), "roc", 1);
            return Value.array(roc(src, period));
        });

        engine.registerFunction("stoch", args -> {
            // stoch(high, low, close, kPeriod, dPeriod) -> [k[], d[]]
            requireArgs("stoch", args, 5);
            List<Value> high = requireNumberArray(args.get(0), "stoch", 0);
            List<Value> low = requireNumberArray(args.get(1), "stoch", 1);
            List<Value> close = requireNumberArray(args.get(2), "stoch", 2);
            int kPeriod = requireInt(args.get(3), "stoch", 3);
            int dPeriod = requireInt(args.get(4), "stoch", 4);

            StochOsc st = stoch(high, low, close, kPeriod, dPeriod);
            List<Value> pair = new ArrayList<>();
            pair.add(Value.array(st.k));
            pair.add(Value.array(st.d));
            return Value.array(pair);
        });

        // Trend
        engine.registerFunction("macd", args -> {
            // macd(src, fast, slow, signal) -> [macd[], signal[], hist[]]
            requireArgs("macd", args, 4);
            List<Value> src = requireNumberArray(args.get(0), "macd", 0);
            int fast = requireInt(args.get(1), "macd", 1);
            int slow = requireInt(args.get(2), "macd", 2);
            int signal = requireInt(args.get(3), "macd", 3);

            Macd m = macd(src, fast, slow, signal);
            List<Value> triple = new ArrayList<>();
            triple.add(Value.array(m.macd));
            triple.add(Value.array(m.signal));
            triple.add(Value.array(m.hist));
            return Value.array(triple);
        });

        // Utilities
        engine.registerFunction("diff", args -> {
            requireArgs("diff", args, 1);
            List<Value> src = requireNumberArray(args.get(0), "diff", 0);
            return Value.array(diff(src));
        });

        engine.registerFunction("zscore", args -> {
            requireArgs("zscore", args, 2);
            List<Value> src = requireNumberArray(args.get(0), "zscore", 0);
            int period = requireInt(args.get(1), "zscore", 1);
            return Value.array(zscore(src, period));
        });
    }

    // ===================== INDICATOR IMPLEMENTATIONS =====================

    private static List<Value> sma(List<Value> src, int period) {
        requirePositive(period, "sma period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
                out.set(i, Value.number(sum / period));
            }
        }

        return out;
    }

    private static List<Value> ema(List<Value> src, int period) {
        requirePositive(period, "ema period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
                    out.set(i, Value.number(ema));
                }
            } else {
                ema = (x - ema) * alpha + ema;
                out.set(i, Value.number(ema));
            }
        }

        return out;
    }

    private static List<Value> wma(List<Value> src, int period) {
        requirePositive(period, "wma period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
            if (ok) out.set(i, Value.number(num / denom));
        }

        return out;
    }

    private static final class BollingerBands {
        final List<Value> upper;
        final List<Value> middle;
        final List<Value> lower;
        BollingerBands(List<Value> upper, List<Value> middle, List<Value> lower) {
            this.upper = upper;
            this.middle = middle;
            this.lower = lower;
        }
    }

    private static BollingerBands bollinger(List<Value> src, int period, double mult) {
        requirePositive(period, "bollinger period");
        int n = src.size();
        List<Value> mid = sma(src, period);
        List<Value> upper = new ArrayList<>(Collections.nCopies(n, Value.nil()));
        List<Value> lower = new ArrayList<>(Collections.nCopies(n, Value.nil()));

        for (int i = period - 1; i < n; i++) {
            // only compute when mid exists
            if (mid.get(i).getType() == Value.Type.NULL) continue;
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
            upper.set(i, Value.number(mean + mult * stdev));
            lower.set(i, Value.number(mean - mult * stdev));
        }

        return new BollingerBands(upper, mid, lower);
    }

    private static List<Value> rsi(List<Value> src, int period) {
        requirePositive(period, "rsi period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
                    out.set(i, Value.number(rsiFromAverages(avgGain, avgLoss)));
                }
            } else {
                // Wilder smoothing
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
                out.set(i, Value.number(rsiFromAverages(avgGain, avgLoss)));
            }
        }

        return out;
    }

    private static double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static List<Value> roc(List<Value> src, int period) {
        requirePositive(period, "roc period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

        for (int i = period; i < n; i++) {
            Double now = asDoubleOrNull(src.get(i));
            Double then = asDoubleOrNull(src.get(i - period));
            if (now == null || then == null) continue;
            if (then == 0.0) continue;
            out.set(i, Value.number((now - then) / then * 100.0));
        }

        return out;
    }

    private static final class StochOsc {
        final List<Value> k;
        final List<Value> d;
        StochOsc(List<Value> k, List<Value> d) {
            this.k = k;
            this.d = d;
        }
    }

    private static StochOsc stoch(List<Value> high, List<Value> low, List<Value> close,
                                  int kPeriod, int dPeriod) {
        requirePositive(kPeriod, "stoch kPeriod");
        requirePositive(dPeriod, "stoch dPeriod");

        int n = minLen(high.size(), low.size(), close.size());
        List<Value> k = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
            k.set(i, Value.number(pctK));
        }

        // %D is SMA of %K
        List<Value> d = sma(k, dPeriod);
        return new StochOsc(k, d);
    }

    private static final class Macd {
        final List<Value> macd;
        final List<Value> signal;
        final List<Value> hist;
        Macd(List<Value> macd, List<Value> signal, List<Value> hist) {
            this.macd = macd;
            this.signal = signal;
            this.hist = hist;
        }
    }

    private static Macd macd(List<Value> src, int fast, int slow, int signalPeriod) {
        requirePositive(fast, "macd fast");
        requirePositive(slow, "macd slow");
        requirePositive(signalPeriod, "macd signal");
        if (fast >= slow) throw new RuntimeException("macd: fast must be < slow");

        List<Value> emaFast = ema(src, fast);
        List<Value> emaSlow = ema(src, slow);

        int n = src.size();
        List<Value> line = new ArrayList<>(Collections.nCopies(n, Value.nil()));

        for (int i = 0; i < n; i++) {
            if (emaFast.get(i).getType() == Value.Type.NULL) continue;
            if (emaSlow.get(i).getType() == Value.Type.NULL) continue;
            line.set(i, Value.number(emaFast.get(i).asNumber() - emaSlow.get(i).asNumber()));
        }

        List<Value> sig = ema(line, signalPeriod);
        List<Value> hist = new ArrayList<>(Collections.nCopies(n, Value.nil()));

        for (int i = 0; i < n; i++) {
            if (line.get(i).getType() == Value.Type.NULL) continue;
            if (sig.get(i).getType() == Value.Type.NULL) continue;
            hist.set(i, Value.number(line.get(i).asNumber() - sig.get(i).asNumber()));
        }

        return new Macd(line, sig, hist);
    }

    private static List<Value> diff(List<Value> src) {
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));
        Double prev = null;
        for (int i = 0; i < n; i++) {
            Double x = asDoubleOrNull(src.get(i));
            if (x == null) { prev = null; continue; }
            if (prev == null) { prev = x; continue; }
            out.set(i, Value.number(x - prev));
            prev = x;
        }
        return out;
    }

    private static List<Value> zscore(List<Value> src, int period) {
        requirePositive(period, "zscore period");
        int n = src.size();
        List<Value> out = new ArrayList<>(Collections.nCopies(n, Value.nil()));

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
            out.set(i, Value.number((xNow - mean) / sd));
        }

        return out;
    }

    // ===================== ARG / TYPE HELPERS =====================

    private static void requireArgs(String fn, List<Value> args, int count) {
        if (args.size() != count) {
            throw new RuntimeException(fn + "() expects " + count + " arguments, got " + args.size());
        }
    }

    private static void requirePositive(int v, String label) {
        if (v <= 0) throw new RuntimeException(label + " must be > 0");
    }

    private static int requireInt(Value v, String fn, int idx) {
        if (v.getType() != Value.Type.NUMBER) {
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

    private static double requireNumber(Value v, String fn, int idx) {
        if (v.getType() != Value.Type.NUMBER) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        }
        return v.asNumber();
    }

    private static List<Value> requireNumberArray(Value v, String fn, int idx) {
        if (v.getType() != Value.Type.ARRAY) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be an array");
        }
        List<Value> arr = v.asArray();
        for (int i = 0; i < arr.size(); i++) {
            Value item = arr.get(i);
            if (item.getType() == Value.Type.NULL) continue; // allow nulls
            if (item.getType() != Value.Type.NUMBER) {
                throw new RuntimeException(fn + " arg[" + idx + "] element[" + i + "] must be number or null");
            }
        }
        return arr;
    }

    private static Double asDoubleOrNull(Value v) {
        if (v == null) return null;
        if (v.getType() == Value.Type.NULL) return null;
        if (v.getType() != Value.Type.NUMBER) return null;
        return v.asNumber();
    }

    private static int minLen(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }
}
