package com.elara.script.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.elara.script.ElaraScript;

/**
 * ElaraTradingViewPluginExotic
 *
 * Second plugin pack: more "exotic" / popular TradingView indicators.
 *
 * Registration:
 *   ElaraTradingViewPluginExotic.register(engine);
 *
 * Conventions:
 * - Inputs are arrays of numbers (allow nulls).
 * - Outputs are arrays aligned to input length; insufficient lookback yields null.
 * - Multi-output indicators return an array of arrays.
 */
public final class ElaraTradingViewPluginExotic {

    private ElaraTradingViewPluginExotic() {}

    public static void register(ElaraScript engine) {
        // Volatility / ranges
        engine.registerFunction("atr", args -> {
            // atr(high, low, close, period)
            requireArgs("atr", args, 4);
            List<ElaraScript.Value> high = numArray(args.get(0), "atr", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "atr", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "atr", 2);
            int period = posInt(args.get(3), "atr", 3);
            return ElaraScript.Value.array(atr(high, low, close, period));
        });

        engine.registerFunction("tr", args -> {
            // tr(high, low, close)
            requireArgs("tr", args, 3);
            List<ElaraScript.Value> high = numArray(args.get(0), "tr", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "tr", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "tr", 2);
            return ElaraScript.Value.array(trueRange(high, low, close));
        });

        // Channels
        engine.registerFunction("donchian", args -> {
            // donchian(high, low, period) -> [upper[], middle[], lower[]]
            requireArgs("donchian", args, 3);
            List<ElaraScript.Value> high = numArray(args.get(0), "donchian", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "donchian", 1);
            int period = posInt(args.get(2), "donchian", 2);
            Triple t = donchian(high, low, period);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.array(t.a),
                    ElaraScript.Value.array(t.b),
                    ElaraScript.Value.array(t.c)
            ));
        });

        engine.registerFunction("keltner", args -> {
            // keltner(high, low, close, emaPeriod, atrPeriod, mult) -> [upper[], middle[], lower[]]
            requireArgs("keltner", args, 6);
            List<ElaraScript.Value> high = numArray(args.get(0), "keltner", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "keltner", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "keltner", 2);
            int emaPeriod = posInt(args.get(3), "keltner", 3);
            int atrPeriod = posInt(args.get(4), "keltner", 4);
            double mult = num(args.get(5), "keltner", 5);
            Triple t = keltner(high, low, close, emaPeriod, atrPeriod, mult);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.array(t.a),
                    ElaraScript.Value.array(t.b),
                    ElaraScript.Value.array(t.c)
            ));
        });

        // Trend / strength
        engine.registerFunction("adx", args -> {
            // adx(high, low, close, period) -> [adx[], plusDI[], minusDI[]]
            requireArgs("adx", args, 4);
            List<ElaraScript.Value> high = numArray(args.get(0), "adx", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "adx", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "adx", 2);
            int period = posInt(args.get(3), "adx", 3);
            AdxOut o = adx(high, low, close, period);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.array(o.adx),
                    ElaraScript.Value.array(o.plusDi),
                    ElaraScript.Value.array(o.minusDi)
            ));
        });

        engine.registerFunction("supertrend", args -> {
            // supertrend(high, low, close, atrPeriod, mult) -> [trend[], direction[]]
            // trend[] is the supertrend line value, direction[] is 1 (up) or -1 (down)
            requireArgs("supertrend", args, 5);
            List<ElaraScript.Value> high = numArray(args.get(0), "supertrend", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "supertrend", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "supertrend", 2);
            int atrPeriod = posInt(args.get(3), "supertrend", 3);
            double mult = num(args.get(4), "supertrend", 4);
            SuperTrend st = supertrend(high, low, close, atrPeriod, mult);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.array(st.line),
                    ElaraScript.Value.array(st.dir)
            ));
        });

        // Volume-based
        engine.registerFunction("vwap", args -> {
            // vwap(high, low, close, volume) -> vwap[] (cumulative session vwap)
            // This uses typical price = (h+l+c)/3.
            requireArgs("vwap", args, 4);
            List<ElaraScript.Value> high = numArray(args.get(0), "vwap", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "vwap", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "vwap", 2);
            List<ElaraScript.Value> vol = numArray(args.get(3), "vwap", 3);
            return ElaraScript.Value.array(vwap(high, low, close, vol));
        });

        engine.registerFunction("mfi", args -> {
            // mfi(high, low, close, volume, period)
            requireArgs("mfi", args, 5);
            List<ElaraScript.Value> high = numArray(args.get(0), "mfi", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "mfi", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "mfi", 2);
            List<ElaraScript.Value> vol = numArray(args.get(3), "mfi", 3);
            int period = posInt(args.get(4), "mfi", 4);
            return ElaraScript.Value.array(mfi(high, low, close, vol, period));
        });

        // Mean reversion / oscillators
        engine.registerFunction("cci", args -> {
            // cci(high, low, close, period)
            requireArgs("cci", args, 4);
            List<ElaraScript.Value> high = numArray(args.get(0), "cci", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "cci", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "cci", 2);
            int period = posInt(args.get(3), "cci", 3);
            return ElaraScript.Value.array(cci(high, low, close, period));
        });

        engine.registerFunction("ichimoku", args -> {
            // ichimoku(high, low, close, tenkan, kijun, senkouB) -> [tenkan[], kijun[], spanA[], spanB[], chikou[]]
            requireArgs("ichimoku", args, 6);
            List<ElaraScript.Value> high = numArray(args.get(0), "ichimoku", 0);
            List<ElaraScript.Value> low = numArray(args.get(1), "ichimoku", 1);
            List<ElaraScript.Value> close = numArray(args.get(2), "ichimoku", 2);
            int tenkan = posInt(args.get(3), "ichimoku", 3);
            int kijun = posInt(args.get(4), "ichimoku", 4);
            int senkouB = posInt(args.get(5), "ichimoku", 5);
            Ichimoku ic = ichimoku(high, low, close, tenkan, kijun, senkouB);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.array(ic.tenkan),
                    ElaraScript.Value.array(ic.kijun),
                    ElaraScript.Value.array(ic.spanA),
                    ElaraScript.Value.array(ic.spanB),
                    ElaraScript.Value.array(ic.chikou)
            ));
        });
    }

    // ===================== IMPLEMENTATIONS =====================

    private static final class Triple {
        final List<ElaraScript.Value> a;
        final List<ElaraScript.Value> b;
        final List<ElaraScript.Value> c;
        Triple(List<ElaraScript.Value> a, List<ElaraScript.Value> b, List<ElaraScript.Value> c) {
            this.a = a; this.b = b; this.c = c;
        }
    }

    private static List<ElaraScript.Value> trueRange(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close) {
        int n = minLen(high.size(), low.size(), close.size());
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        Double prevClose = null;
        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            Double c = d(close.get(i));
            if (h == null || l == null || c == null) { prevClose = null; continue; }

            double tr;
            if (prevClose == null) {
                tr = h - l;
            } else {
                tr = Math.max(h - l, Math.max(Math.abs(h - prevClose), Math.abs(l - prevClose)));
            }
            out.set(i, ElaraScript.Value.number(tr));
            prevClose = c;
        }

        return out;
    }

    private static List<ElaraScript.Value> atr(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close, int period) {
        requirePositive(period, "atr period");
        List<ElaraScript.Value> tr = trueRange(high, low, close);
        return rma(tr, period); // Wilder's RMA
    }

    private static Triple donchian(List<ElaraScript.Value> high, List<ElaraScript.Value> low, int period) {
        requirePositive(period, "donchian period");
        int n = minLen(high.size(), low.size());
        List<ElaraScript.Value> upper = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> lower = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> mid = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = period - 1; i < n; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            boolean ok = true;

            for (int j = 0; j < period; j++) {
                Double h = d(high.get(i - j));
                Double l = d(low.get(i - j));
                if (h == null || l == null) { ok = false; break; }
                if (h > hh) hh = h;
                if (l < ll) ll = l;
            }

            if (!ok) continue;
            double m = (hh + ll) / 2.0;
            upper.set(i, ElaraScript.Value.number(hh));
            lower.set(i, ElaraScript.Value.number(ll));
            mid.set(i, ElaraScript.Value.number(m));
        }

        return new Triple(upper, mid, lower);
    }

    private static Triple keltner(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                 int emaPeriod, int atrPeriod, double mult) {
        int n = minLen(high.size(), low.size(), close.size());
        List<ElaraScript.Value> typical = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            Double c = d(close.get(i));
            if (h == null || l == null || c == null) continue;
            typical.set(i, ElaraScript.Value.number((h + l + c) / 3.0));
        }

        List<ElaraScript.Value> mid = ema(typical, emaPeriod);
        List<ElaraScript.Value> a = atr(high, low, close, atrPeriod);

        List<ElaraScript.Value> upper = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> lower = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = 0; i < n; i++) {
            if (mid.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            if (a.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            double m = mid.get(i).asNumber();
            double atr = a.get(i).asNumber();
            upper.set(i, ElaraScript.Value.number(m + mult * atr));
            lower.set(i, ElaraScript.Value.number(m - mult * atr));
        }

        return new Triple(upper, mid, lower);
    }

    private static final class AdxOut {
        final List<ElaraScript.Value> adx;
        final List<ElaraScript.Value> plusDi;
        final List<ElaraScript.Value> minusDi;
        AdxOut(List<ElaraScript.Value> adx, List<ElaraScript.Value> plusDi, List<ElaraScript.Value> minusDi) {
            this.adx = adx; this.plusDi = plusDi; this.minusDi = minusDi;
        }
    }

    private static AdxOut adx(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close, int period) {
        requirePositive(period, "adx period");
        int n = minLen(high.size(), low.size(), close.size());

        List<ElaraScript.Value> tr = trueRange(high, low, close);

        List<ElaraScript.Value> plusDM = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> minusDM = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        Double prevH = null, prevL = null;
        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            if (h == null || l == null || prevH == null || prevL == null) {
                prevH = h; prevL = l;
                continue;
            }

            double upMove = h - prevH;
            double downMove = prevL - l;

            double pdm = (upMove > downMove && upMove > 0.0) ? upMove : 0.0;
            double mdm = (downMove > upMove && downMove > 0.0) ? downMove : 0.0;

            plusDM.set(i, ElaraScript.Value.number(pdm));
            minusDM.set(i, ElaraScript.Value.number(mdm));

            prevH = h;
            prevL = l;
        }

        // Wilder smoothing
        List<ElaraScript.Value> atr = rma(tr, period);
        List<ElaraScript.Value> pDM = rma(plusDM, period);
        List<ElaraScript.Value> mDM = rma(minusDM, period);

        List<ElaraScript.Value> plusDI = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> minusDI = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> dx = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = 0; i < n; i++) {
            if (atr.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            double a = atr.get(i).asNumber();
            if (a == 0.0) continue;

            if (pDM.get(i).getType() != ElaraScript.Value.Type.NULL) {
                plusDI.set(i, ElaraScript.Value.number(100.0 * pDM.get(i).asNumber() / a));
            }
            if (mDM.get(i).getType() != ElaraScript.Value.Type.NULL) {
                minusDI.set(i, ElaraScript.Value.number(100.0 * mDM.get(i).asNumber() / a));
            }

            if (plusDI.get(i).getType() == ElaraScript.Value.Type.NULL || minusDI.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            double p = plusDI.get(i).asNumber();
            double m = minusDI.get(i).asNumber();
            double denom = p + m;
            if (denom == 0.0) continue;
            dx.set(i, ElaraScript.Value.number(100.0 * Math.abs(p - m) / denom));
        }

        List<ElaraScript.Value> adx = rma(dx, period);
        return new AdxOut(adx, plusDI, minusDI);
    }

    private static final class SuperTrend {
        final List<ElaraScript.Value> line;
        final List<ElaraScript.Value> dir;
        SuperTrend(List<ElaraScript.Value> line, List<ElaraScript.Value> dir) {
            this.line = line; this.dir = dir;
        }
    }

    private static SuperTrend supertrend(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                        int atrPeriod, double mult) {
        int n = minLen(high.size(), low.size(), close.size());

        List<ElaraScript.Value> atr = atr(high, low, close, atrPeriod);
        List<ElaraScript.Value> hl2 = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            if (h == null || l == null) continue;
            hl2.set(i, ElaraScript.Value.number((h + l) / 2.0));
        }

        List<ElaraScript.Value> upperBand = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> lowerBand = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            if (hl2.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            if (atr.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            double mid = hl2.get(i).asNumber();
            double a = atr.get(i).asNumber();
            upperBand.set(i, ElaraScript.Value.number(mid + mult * a));
            lowerBand.set(i, ElaraScript.Value.number(mid - mult * a));
        }

        List<ElaraScript.Value> finalUpper = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> finalLower = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> stLine = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        List<ElaraScript.Value> dir = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        Double prevFinalUpper = null;
        Double prevFinalLower = null;
        Double prevClose = null;
        Integer prevDir = null;

        for (int i = 0; i < n; i++) {
            Double ub = d(upperBand.get(i));
            Double lb = d(lowerBand.get(i));
            Double c = d(close.get(i));
            if (ub == null || lb == null || c == null) {
                prevFinalUpper = null; prevFinalLower = null; prevClose = null; prevDir = null;
                continue;
            }

            double fu;
            double fl;

            if (prevFinalUpper == null || prevFinalLower == null || prevClose == null) {
                fu = ub;
                fl = lb;
            } else {
                fu = (ub < prevFinalUpper || prevClose > prevFinalUpper) ? ub : prevFinalUpper;
                fl = (lb > prevFinalLower || prevClose < prevFinalLower) ? lb : prevFinalLower;
            }

            finalUpper.set(i, ElaraScript.Value.number(fu));
            finalLower.set(i, ElaraScript.Value.number(fl));

            int curDir;
            double line;
            if (prevDir == null) {
                // initialize direction using close vs bands
                curDir = (c <= fu) ? -1 : 1;
            } else {
                if (prevDir == 1 && c < fl) curDir = -1;
                else if (prevDir == -1 && c > fu) curDir = 1;
                else curDir = prevDir;
            }

            line = (curDir == 1) ? fl : fu;

            stLine.set(i, ElaraScript.Value.number(line));
            dir.set(i, ElaraScript.Value.number(curDir));

            prevFinalUpper = fu;
            prevFinalLower = fl;
            prevClose = c;
            prevDir = curDir;
        }

        return new SuperTrend(stLine, dir);
    }

    private static List<ElaraScript.Value> vwap(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                               List<ElaraScript.Value> volume) {
        int n = minLen(high.size(), low.size(), close.size(), volume.size());
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        double cumPV = 0.0;
        double cumV = 0.0;

        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            Double c = d(close.get(i));
            Double v = d(volume.get(i));
            if (h == null || l == null || c == null || v == null) continue;
            double tp = (h + l + c) / 3.0;
            cumPV += tp * v;
            cumV += v;
            if (cumV == 0.0) continue;
            out.set(i, ElaraScript.Value.number(cumPV / cumV));
        }

        return out;
    }

    private static List<ElaraScript.Value> mfi(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                              List<ElaraScript.Value> volume, int period) {
        requirePositive(period, "mfi period");
        int n = minLen(high.size(), low.size(), close.size(), volume.size());
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        List<Double> tp = new ArrayList<>(Collections.nCopies(n, null));
        List<Double> rmf = new ArrayList<>(Collections.nCopies(n, null));

        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            Double c = d(close.get(i));
            Double v = d(volume.get(i));
            if (h == null || l == null || c == null || v == null) continue;
            double t = (h + l + c) / 3.0;
            tp.set(i, t);
            rmf.set(i, t * v);
        }

        for (int i = period; i < n; i++) {
            if (tp.get(i) == null || tp.get(i - 1) == null) continue;
            double pos = 0.0;
            double neg = 0.0;
            boolean ok = true;

            for (int j = 0; j < period; j++) {
                int k = i - j;
                if (tp.get(k) == null || tp.get(k - 1) == null || rmf.get(k) == null) { ok = false; break; }
                if (tp.get(k) > tp.get(k - 1)) pos += rmf.get(k);
                else if (tp.get(k) < tp.get(k - 1)) neg += rmf.get(k);
            }

            if (!ok) continue;
            if (neg == 0.0) {
                out.set(i, ElaraScript.Value.number(100.0));
            } else {
                double mr = pos / neg;
                out.set(i, ElaraScript.Value.number(100.0 - (100.0 / (1.0 + mr))));
            }
        }

        return out;
    }

    private static List<ElaraScript.Value> cci(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close, int period) {
        requirePositive(period, "cci period");
        int n = minLen(high.size(), low.size(), close.size());
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        List<Double> tp = new ArrayList<>(Collections.nCopies(n, null));
        for (int i = 0; i < n; i++) {
            Double h = d(high.get(i));
            Double l = d(low.get(i));
            Double c = d(close.get(i));
            if (h == null || l == null || c == null) continue;
            tp.set(i, (h + l + c) / 3.0);
        }

        for (int i = period - 1; i < n; i++) {
            double sum = 0.0;
            boolean ok = true;
            for (int j = 0; j < period; j++) {
                Double x = tp.get(i - j);
                if (x == null) { ok = false; break; }
                sum += x;
            }
            if (!ok) continue;

            double sma = sum / period;
            double dev = 0.0;
            for (int j = 0; j < period; j++) {
                double x = tp.get(i - j);
                dev += Math.abs(x - sma);
            }
            dev /= period;
            if (dev == 0.0) continue;

            double cci = (tp.get(i) - sma) / (0.015 * dev);
            out.set(i, ElaraScript.Value.number(cci));
        }

        return out;
    }

    private static final class Ichimoku {
        final List<ElaraScript.Value> tenkan;
        final List<ElaraScript.Value> kijun;
        final List<ElaraScript.Value> spanA;
        final List<ElaraScript.Value> spanB;
        final List<ElaraScript.Value> chikou;
        Ichimoku(List<ElaraScript.Value> tenkan, List<ElaraScript.Value> kijun, List<ElaraScript.Value> spanA,
                 List<ElaraScript.Value> spanB, List<ElaraScript.Value> chikou) {
            this.tenkan = tenkan;
            this.kijun = kijun;
            this.spanA = spanA;
            this.spanB = spanB;
            this.chikou = chikou;
        }
    }

    private static Ichimoku ichimoku(List<ElaraScript.Value> high, List<ElaraScript.Value> low, List<ElaraScript.Value> close,
                                    int tenkan, int kijun, int senkouB) {
        int n = minLen(high.size(), low.size(), close.size());
        List<ElaraScript.Value> ten = midpoint(high, low, tenkan);
        List<ElaraScript.Value> kij = midpoint(high, low, kijun);

        // spanA = (tenkan + kijun)/2 shifted forward by kijun
        List<ElaraScript.Value> spanA = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            if (ten.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            if (kij.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            int j = i + kijun;
            if (j >= n) break;
            spanA.set(j, ElaraScript.Value.number((ten.get(i).asNumber() + kij.get(i).asNumber()) / 2.0));
        }

        // spanB = midpoint(high, low, senkouB) shifted forward by kijun
        List<ElaraScript.Value> rawSpanB = midpoint(high, low, senkouB);
        List<ElaraScript.Value> spanB = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            if (rawSpanB.get(i).getType() == ElaraScript.Value.Type.NULL) continue;
            int j = i + kijun;
            if (j >= n) break;
            spanB.set(j, rawSpanB.get(i));
        }

        // chikou = close shifted back by kijun
        List<ElaraScript.Value> chikou = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));
        for (int i = 0; i < n; i++) {
            Double c = d(close.get(i));
            if (c == null) continue;
            int j = i - kijun;
            if (j < 0) continue;
            chikou.set(j, ElaraScript.Value.number(c));
        }

        return new Ichimoku(ten, kij, spanA, spanB, chikou);
    }

    private static List<ElaraScript.Value> midpoint(List<ElaraScript.Value> high, List<ElaraScript.Value> low, int period) {
        requirePositive(period, "ichimoku period");
        int n = minLen(high.size(), low.size());
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        for (int i = period - 1; i < n; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            boolean ok = true;

            for (int j = 0; j < period; j++) {
                Double h = d(high.get(i - j));
                Double l = d(low.get(i - j));
                if (h == null || l == null) { ok = false; break; }
                if (h > hh) hh = h;
                if (l < ll) ll = l;
            }

            if (!ok) continue;
            out.set(i, ElaraScript.Value.number((hh + ll) / 2.0));
        }

        return out;
    }

    // Wilder RMA: like EMA with alpha = 1/period, seeded by SMA.
    private static List<ElaraScript.Value> rma(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "rma period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        Double rma = null;
        double sum = 0.0;
        int valid = 0;

        for (int i = 0; i < n; i++) {
            Double x = d(src.get(i));
            if (x == null) {
                rma = null; sum = 0.0; valid = 0;
                continue;
            }

            if (rma == null) {
                sum += x;
                valid++;
                if (valid == period) {
                    rma = sum / period;
                    out.set(i, ElaraScript.Value.number(rma));
                }
            } else {
                rma = (rma * (period - 1) + x) / period;
                out.set(i, ElaraScript.Value.number(rma));
            }
        }

        return out;
    }

    private static List<ElaraScript.Value> ema(List<ElaraScript.Value> src, int period) {
        requirePositive(period, "ema period");
        int n = src.size();
        List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(n, ElaraScript.Value.nil()));

        double alpha = 2.0 / (period + 1.0);
        Double ema = null;
        double sum = 0.0;
        int valid = 0;

        for (int i = 0; i < n; i++) {
            Double x = d(src.get(i));
            if (x == null) {
                ema = null; sum = 0.0; valid = 0;
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

    // ===================== ARG / TYPE HELPERS =====================

    private static void requireArgs(String fn, List<ElaraScript.Value> args, int n) {
        if (args.size() != n) throw new RuntimeException(fn + "() expects " + n + " arguments, got " + args.size());
    }

    private static List<ElaraScript.Value> numArray(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.ARRAY) throw new RuntimeException(fn + " arg[" + idx + "] must be an array");
        List<ElaraScript.Value> a = v.asArray();
        for (int i = 0; i < a.size(); i++) {
            ElaraScript.Value x = a.get(i);
            if (x.getType() == ElaraScript.Value.Type.NULL) continue;
            if (x.getType() != ElaraScript.Value.Type.NUMBER) {
                throw new RuntimeException(fn + " arg[" + idx + "] element[" + i + "] must be number or null");
            }
        }
        return a;
    }

    private static int posInt(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        double d = v.asNumber();
        if (d != Math.rint(d)) throw new RuntimeException(fn + " arg[" + idx + "] must be an integer");
        int i = (int) d;
        if (i <= 0) throw new RuntimeException(fn + " arg[" + idx + "] must be > 0");
        return i;
    }

    private static double num(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        return v.asNumber();
    }

    private static void requirePositive(int v, String label) {
        if (v <= 0) throw new RuntimeException(label + " must be > 0");
    }

    private static Double d(ElaraScript.Value v) {
        if (v == null) return null;
        if (v.getType() == ElaraScript.Value.Type.NULL) return null;
        if (v.getType() != ElaraScript.Value.Type.NUMBER) return null;
        return v.asNumber();
    }

    private static int minLen(int a, int b) { return Math.min(a, b); }
    private static int minLen(int a, int b, int c) { return Math.min(a, Math.min(b, c)); }
    private static int minLen(int a, int b, int c, int d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
}
