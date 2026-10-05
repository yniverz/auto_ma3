package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds sections from the full-track waveform when a track has no rekordbox phrase analysis.
 *
 * <p>Works per bar on low / mid / high band levels (CDJ-3000 three-band or NXS2 colour waveform),
 * each normalised to the track's own loud parts. Every bar gets an energy level:
 * <ul>
 *   <li>HIGH: full energy with bass (drops, peak parts)</li>
 *   <li>MID: bass / kick running but clearly below the loudest parts (grooves, builds with kick)</li>
 *   <li>LOW: no bass (breakdowns, quiet intros and outros)</li>
 * </ul>
 * Runs of equal level are snapped to the 4-bar phrase grid. A HIGH run that starts with a clear
 * energy jump over the bars before it is a DROP, and what leads into a drop becomes a BUILD.
 * This catches drops in house / EDM where the kick keeps running through the build, as well as
 * techno drops where the kick returns after a breakdown.</p>
 */
public final class WaveformAnalyzer {

    public record BarBands(double low, double mid, double high) {
    }

    /** Bass level (relative to the track's loud bars) from which a bar counts as having bass / kick. */
    static final double BASS_THRESHOLD = 0.3;
    /** Energy (relative to the track's loudest bars) from which a bar with bass counts as HIGH. */
    static final double HIGH_THRESHOLD = 0.82;
    /** Energy increase over the preceding bars that makes a HIGH run a drop. */
    static final double DROP_JUMP = 0.12;
    /** Increase of the bass level (two bars after vs. two bars before) that marks a drop. */
    static final double BASS_JUMP = 0.3;
    /** Runs shorter than this many bars are merged into a neighbour. */
    static final int MIN_RUN_BARS = 2;

    static final int LOW = 0, MID = 1, HIGH = 2;

    private record Run(int start, int end, int level) {
        int len() {
            return end - start;
        }
    }

    private WaveformAnalyzer() {
    }

    /** Average per-beat bands (index 0 = beat 1) into bars starting at the first downbeat. */
    public static List<BarBands> barsFromBeats(List<BarBands> beats, int firstDownbeat) {
        List<BarBands> bars = new ArrayList<>();
        for (int b = firstDownbeat - 1; b + 4 <= beats.size(); b += 4) {
            double lo = 0, mid = 0, hi = 0;
            for (int i = b; i < b + 4; i++) {
                lo += beats.get(i).low();
                mid += beats.get(i).mid();
                hi += beats.get(i).high();
            }
            bars.add(new BarBands(lo / 4, mid / 4, hi / 4));
        }
        return bars;
    }

    /** Analyse per-beat waveform bands; the result keeps them for display. */
    public static TrackStructure analyzeBeats(String trackKey, List<BarBands> beats, int firstDownbeat, int dropBeats) {
        return analyze(trackKey, barsFromBeats(beats, firstDownbeat), firstDownbeat, dropBeats).withBeatBands(beats);
    }

    public static TrackStructure analyze(String trackKey, List<BarBands> bars, int firstDownbeat, int dropBeats) {
        int n = bars.size();
        if (n == 0) return new TrackStructure(trackKey, "waveform", List.of(), List.of(), firstDownbeat, bars);

        double[] low = normalize(bars.stream().mapToDouble(BarBands::low).toArray());
        double[] mid = normalize(bars.stream().mapToDouble(BarBands::mid).toArray());
        double[] high = normalize(bars.stream().mapToDouble(BarBands::high).toArray());
        double[] energy = new double[n];
        for (int i = 0; i < n; i++) energy[i] = 0.5 * low[i] + 0.3 * mid[i] + 0.2 * high[i];
        double eRef = percentile(energy, 0.95);
        if (eRef <= 1e-9) eRef = 1;
        for (int i = 0; i < n; i++) energy[i] = Math.min(1, energy[i] / eRef);

        int[] level = new int[n];
        for (int i = 0; i < n; i++) {
            boolean bass = low[i] >= BASS_THRESHOLD;
            level[i] = !bass ? LOW : energy[i] >= HIGH_THRESHOLD ? HIGH : MID;
        }
        level = median3(level);

        List<Run> runs = new ArrayList<>(snapToPhrases(mergeShort(runs(level), energy), n));

        // pass 1: which HIGH runs are drops
        boolean[] drop = new boolean[runs.size()];
        for (int r = 1; r < runs.size(); r++) {
            Run run = runs.get(r);
            if (run.level != HIGH || runs.get(r - 1).level == HIGH) continue;
            double after = mean(energy, run.start, Math.min(n, run.start + 2));
            double before = mean(energy, Math.max(0, run.start - 4), run.start);
            // the phrase-grid snap can move a boundary by a bar, so also look one bar either side
            double bass = Math.max(bassJump(low, run.start),
                    Math.max(bassJump(low, run.start - 1), bassJump(low, run.start + 1)));
            drop[r] = after - before >= DROP_JUMP || bass >= BASS_JUMP;
        }
        // Builds get loud a few bars early (kick comes in, snare rolls, risers in the mids and highs). The drop
        // itself is where the bass jumps, so put each drop there.
        for (int r = 1; r < runs.size(); r++) {
            if (!drop[r]) continue;
            Run prev = runs.get(r - 1), run = runs.get(r);
            int best = run.start;
            double bestJump = bassJump(low, run.start);
            for (int b = Math.max(prev.start + 1, run.start - 4); b <= Math.min(run.end - 1, run.start + 4); b++) {
                double j = bassJump(low, b);
                if (j > bestJump + 1e-9) {
                    bestJump = j;
                    best = b;
                }
            }
            // prefer the 4-bar phrase grid when it is nearly as good
            int grid = Math.round(best / 4f) * 4;
            if (grid != best && grid > prev.start && grid < run.end && bassJump(low, grid) >= 0.8 * bestJump) best = grid;
            if (best != run.start) {
                runs.set(r - 1, new Run(prev.start, best, prev.level));
                runs.set(r, new Run(best, run.end, run.level));
            }
        }

        // pass 2: label
        List<Double> energyList = new ArrayList<>(n);
        for (double e : energy) energyList.add(e);
        List<TrackStructure.Segment> segs = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            Run run = runs.get(r);
            boolean first = r == 0, last = r == runs.size() - 1;
            boolean beforeDrop = r + 1 < runs.size() && drop[r + 1];
            int sb = firstDownbeat + run.start * 4, eb = firstDownbeat + run.end * 4;
            switch (run.level) {
                case HIGH -> segs.add(new TrackStructure.Segment(sb, eb,
                        drop[r] ? Section.DROP : first ? Section.GROOVE : Section.PEAK,
                        drop[r] ? "energy jump" : "full energy"));
                case MID -> {
                    if (beforeDrop && !first && run.len() <= 16) {
                        segs.add(new TrackStructure.Segment(sb, eb, Section.BUILD, "lead-in to drop"));
                    } else if (beforeDrop && run.len() > 8) {
                        int split = firstDownbeat + buildStart(mid, high, run.start, run.end) * 4;
                        segs.add(new TrackStructure.Segment(sb, split, first ? Section.INTRO : Section.GROOVE, "bass running"));
                        segs.add(new TrackStructure.Segment(split, eb, Section.BUILD, "lead-in to drop"));
                    } else {
                        segs.add(new TrackStructure.Segment(sb, eb,
                                first ? Section.INTRO : last ? Section.OUTRO : Section.GROOVE, "bass running"));
                    }
                }
                default -> {
                    if (first) {
                        segs.add(new TrackStructure.Segment(sb, eb, Section.INTRO, "no bass (start)"));
                    } else if (last) {
                        segs.add(new TrackStructure.Segment(sb, eb, Section.OUTRO, "no bass (end)"));
                    } else {
                        int buildBars = beforeDrop && run.len() >= 8 && risingHighs(mid, high, run.start, run.end)
                                ? Math.min(8, run.len() / 2 / 4 * 4) : 0;
                        if (buildBars > 0) {
                            int split = firstDownbeat + (run.end - buildBars) * 4;
                            segs.add(new TrackStructure.Segment(sb, split, Section.BREAKDOWN, "no bass"));
                            segs.add(new TrackStructure.Segment(split, eb, Section.BUILD, "rising highs"));
                        } else {
                            segs.add(new TrackStructure.Segment(sb, eb, Section.BREAKDOWN, "no bass"));
                        }
                    }
                }
            }
        }
        return new TrackStructure(trackKey, "waveform", TrackStructure.normalize(segs, dropBeats), energyList,
                firstDownbeat, bars);
    }

    /**
     * Where the build starts inside a run with bass that leads into a drop: the earliest 4-bar block
     * (within the last 16 bars) from which mids / highs stay clearly above the run's earlier level.
     * Falls back to the last 8 bars.
     */
    static int buildStart(double[] mid, double[] high, int start, int end) {
        int maxLen = Math.min(16, (end - start - 4) / 4 * 4); // keep at least 4 bars as baseline
        int searchFrom = end - maxLen;
        if (searchFrom - start < 4) return Math.max(start, end - 8);
        double base = 0;
        for (int i = start; i < searchFrom; i++) base += mid[i] * 0.4 + high[i] * 0.6;
        base /= searchFrom - start;
        double threshold = base * 1.15 + 0.03;
        for (int b = searchFrom; b <= end - 4; b += 4) {
            boolean allAbove = true;
            for (int k = b; k + 4 <= end && allAbove; k += 4) {
                double block = 0;
                for (int i = k; i < k + 4; i++) block += mid[i] * 0.4 + high[i] * 0.6;
                allAbove = block / 4 > threshold;
            }
            if (allAbove) return b;
        }
        return Math.max(start, end - 8);
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

    /** Scale so the track's loud parts (95th percentile) are 1. */
    static double[] normalize(double[] v) {
        double ref = percentile(v, 0.95);
        if (ref <= 1e-9) ref = 1;
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Math.max(0, Math.min(1, v[i] / ref));
        return out;
    }

    static double percentile(double[] v, double p) {
        if (v.length == 0) return 0;
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * p))];
    }

    /** Bass level of the two bars from {@code bar} minus the two bars before it. */
    static double bassJump(double[] low, int bar) {
        if (bar <= 0 || bar >= low.length) return 0;
        return mean(low, bar, Math.min(low.length, bar + 2)) - mean(low, Math.max(0, bar - 2), bar);
    }

    private static double mean(double[] v, int from, int to) {
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += v[i];
        return s / (to - from);
    }

    /** Remove single-bar blips. */
    private static int[] median3(int[] in) {
        int[] out = in.clone();
        for (int i = 1; i < in.length - 1; i++) {
            int a = in[i - 1], b = in[i], c = in[i + 1];
            out[i] = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
        }
        return out;
    }

    private static List<Run> runs(int[] level) {
        List<Run> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= level.length; i++) {
            if (i == level.length || level[i] != level[start]) {
                runs.add(new Run(start, i, level[start]));
                start = i;
            }
        }
        return runs;
    }

    /** Merge runs shorter than MIN_RUN_BARS into the neighbour with the closer energy. */
    private static List<Run> mergeShort(List<Run> in, double[] energy) {
        List<Run> list = new ArrayList<>(in);
        boolean changed = true;
        while (changed && list.size() > 1) {
            changed = false;
            for (int i = 0; i < list.size(); i++) {
                Run r = list.get(i);
                if (r.len() >= MIN_RUN_BARS) continue;
                Run prev = i > 0 ? list.get(i - 1) : null;
                Run next = i + 1 < list.size() ? list.get(i + 1) : null;
                double e = mean(energy, r.start, r.end);
                boolean toPrev = next == null || (prev != null
                        && Math.abs(mean(energy, prev.start, prev.end) - e) <= Math.abs(mean(energy, next.start, next.end) - e));
                if (toPrev) list.set(i - 1, new Run(prev.start, r.end, prev.level));
                else list.set(i + 1, new Run(r.start, next.end, next.level));
                list.remove(i);
                changed = true;
                break;
            }
            for (int i = list.size() - 1; i > 0; i--) {
                if (list.get(i).level == list.get(i - 1).level) {
                    list.set(i - 1, new Run(list.get(i - 1).start, list.get(i).end, list.get(i).level));
                    list.remove(i);
                    changed = true;
                }
            }
        }
        return list;
    }

    /** Move boundaries that are at most one bar off the 4-bar phrase grid onto it. */
    private static List<Run> snapToPhrases(List<Run> runs, int n) {
        List<Run> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            int end = r.end;
            if (i < runs.size() - 1) {
                int nearest = Math.round(end / 4f) * 4;
                if (Math.abs(nearest - end) <= 1) end = nearest;
                end = Math.max(start + 1, Math.min(n, end));
            } else {
                end = n;
            }
            if (end > start) {
                if (!out.isEmpty() && out.get(out.size() - 1).level == r.level) {
                    out.set(out.size() - 1, new Run(out.get(out.size() - 1).start, end, r.level));
                } else {
                    out.add(new Run(start, end, r.level));
                }
            }
            start = end;
        }
        return out;
    }
}
