package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds sections from the full-track waveform when a track has no rekordbox phrase analysis.
 *
 * <p>Works per bar on low / mid / high band energy (CDJ-3000 three-band or NXS2 colour waveform):
 * bars without kick (low band missing) are breakdowns / intro / outro, rising highs at the end
 * of a breakdown are a build, and the kick returning after a long breakdown is a drop. This is
 * reliable for techno and most four-on-the-floor electronic music, and it gives look-ahead
 * because the whole waveform is known when the track is loaded.</p>
 */
public final class WaveformAnalyzer {

    public record BarBands(double low, double mid, double high) {
    }

    /** Low band level (relative to the track's loud bars) above which a bar counts as kicking. */
    static final double KICK_THRESHOLD = 0.55;
    static final int MIN_RUN_BARS = 4;

    private WaveformAnalyzer() {
    }

    public static TrackStructure analyze(String trackKey, List<BarBands> bars, int firstDownbeat, int dropBeats) {
        int n = bars.size();
        if (n == 0) return new TrackStructure(trackKey, "waveform", List.of(), List.of(), firstDownbeat);

        double[] low = normalize(bars.stream().mapToDouble(BarBands::low).toArray());
        double[] mid = normalize(bars.stream().mapToDouble(BarBands::mid).toArray());
        double[] high = normalize(bars.stream().mapToDouble(BarBands::high).toArray());

        boolean[] kick = new boolean[n];
        for (int i = 0; i < n; i++) kick[i] = low[i] >= KICK_THRESHOLD;
        kick = majority(kick);

        // runs of kick / no kick, snapped to 4 bar phrases
        List<int[]> runs = runs(kick); // {startBar, endBar(excl), kick?1:0}
        runs = mergeShortRuns(runs, MIN_RUN_BARS);
        runs = snap(runs, n);

        List<Double> energy = new ArrayList<>(n);
        for (int i = 0; i < n; i++) energy.add(clamp01(0.5 * low[i] + 0.3 * mid[i] + 0.2 * high[i]));

        List<TrackStructure.Segment> segs = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            int[] run = runs.get(r);
            int start = run[0], end = run[1];
            boolean isKick = run[2] == 1;
            int sb = firstDownbeat + start * 4, eb = firstDownbeat + end * 4;
            if (!isKick) {
                if (r == 0) {
                    segs.add(new TrackStructure.Segment(sb, eb, Section.INTRO, "no kick (start)"));
                } else if (r == runs.size() - 1) {
                    segs.add(new TrackStructure.Segment(sb, eb, Section.OUTRO, "no kick (end)"));
                } else {
                    int len = end - start;
                    int buildBars = len >= 8 && risingHighs(mid, high, start, end) ? Math.min(8, len / 2 / 4 * 4) : 0;
                    if (buildBars > 0) {
                        int split = firstDownbeat + (end - buildBars) * 4;
                        segs.add(new TrackStructure.Segment(sb, split, Section.BREAKDOWN, "no kick"));
                        segs.add(new TrackStructure.Segment(split, eb, Section.BUILD, "rising highs"));
                    } else {
                        segs.add(new TrackStructure.Segment(sb, eb, Section.BREAKDOWN, "no kick"));
                    }
                }
            } else {
                boolean afterBreak = r > 0 && runs.get(r - 1)[2] == 0 && r - 1 != 0
                        && runs.get(r - 1)[1] - runs.get(r - 1)[0] >= 8;
                Section s = afterBreak ? Section.DROP : Section.GROOVE;
                segs.add(new TrackStructure.Segment(sb, eb, s, afterBreak ? "kick returns" : "kick"));
            }
        }
        return new TrackStructure(trackKey, "waveform", TrackStructure.normalize(segs, dropBeats), energy, firstDownbeat);
    }

    private static boolean risingHighs(double[] mid, double[] high, int start, int end) {
        int len = end - start;
        int q = Math.max(1, len / 4);
        double first = 0, last = 0;
        for (int i = start; i < start + len / 2; i++) first += mid[i] * 0.4 + high[i] * 0.6;
        first /= Math.max(1, len / 2);
        for (int i = end - q; i < end; i++) last += mid[i] * 0.4 + high[i] * 0.6;
        last /= q;
        return last > first * 1.15 + 0.03;
    }

    static double[] normalize(double[] v) {
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double ref = sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.9))];
        if (ref <= 1e-9) ref = 1;
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = clamp01(v[i] / ref);
        return out;
    }

    private static boolean[] majority(boolean[] in) {
        boolean[] out = in.clone();
        for (int i = 1; i < in.length - 1; i++) {
            int c = (in[i - 1] ? 1 : 0) + (in[i] ? 1 : 0) + (in[i + 1] ? 1 : 0);
            out[i] = c >= 2;
        }
        return out;
    }

    private static List<int[]> runs(boolean[] kick) {
        List<int[]> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= kick.length; i++) {
            if (i == kick.length || kick[i] != kick[start]) {
                runs.add(new int[]{start, i, kick[start] ? 1 : 0});
                start = i;
            }
        }
        return runs;
    }

    private static List<int[]> mergeShortRuns(List<int[]> runs, int minBars) {
        List<int[]> list = new ArrayList<>();
        for (int[] r : runs) list.add(r.clone());
        boolean changed = true;
        while (changed && list.size() > 1) {
            changed = false;
            for (int i = 0; i < list.size(); i++) {
                int[] r = list.get(i);
                if (r[1] - r[0] >= minBars) continue;
                // absorb into the longer neighbour
                int[] prev = i > 0 ? list.get(i - 1) : null;
                int[] next = i + 1 < list.size() ? list.get(i + 1) : null;
                int[] target = prev == null ? next : next == null ? prev
                        : (prev[1] - prev[0] >= next[1] - next[0] ? prev : next);
                if (target == prev) prev[1] = r[1];
                else next[0] = r[0];
                list.remove(i);
                changed = true;
                break;
            }
            // join equal neighbours
            for (int i = list.size() - 1; i > 0; i--) {
                if (list.get(i)[2] == list.get(i - 1)[2]) {
                    list.get(i - 1)[1] = list.get(i)[1];
                    list.remove(i);
                    changed = true;
                }
            }
        }
        return list;
    }

    private static List<int[]> snap(List<int[]> runs, int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            int[] r = runs.get(i).clone();
            if (i > 0) r[0] = out.get(out.size() - 1)[1];
            if (i < runs.size() - 1) r[1] = Math.max(r[0] + 1, Math.min(n, Math.round(r[1] / 4f) * 4));
            else r[1] = n;
            if (r[1] > r[0]) out.add(r);
        }
        return out;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
