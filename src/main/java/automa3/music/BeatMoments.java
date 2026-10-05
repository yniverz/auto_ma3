package automa3.music;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Short musical moments inside sections, found in the per-beat waveform levels:
 * <ul>
 *     <li>{@link Kind#BREAK}: the kick / bass stops for half a bar to two bars and comes back (techno fills,
 *     the gap right before a drop), the rest of the music keeps going;</li>
 *     <li>{@link Kind#STOP}: the same, but everything goes (nearly) silent;</li>
 *     <li>{@link Kind#HIT}: a single bass hit that decays, without a running kick, repeating with the bar
 *     (an 808 on the one in a build);</li>
 *     <li>{@link Kind#STEP}: a build-up gets clearly harder (more bass, steadier kick, louder mids) from one
 *     4-bar phrase to the next.</li>
 * </ul>
 * Levels are relative to the track (90th percentile = 1), so the thresholds work for quiet and loud masters.
 */
public final class BeatMoments {

    public enum Kind { BREAK, STOP, HIT, STEP }

    /** {@code length} beats from {@code startBeat} (beat numbers as in the beat grid, starting at 1). */
    public record Moment(int startBeat, int length, Kind kind) {
        /** First beat after the moment: where the kick returns after a break. */
        public int endBeat() {
            return startBeat + length;
        }
    }

    /** Bass below this (relative) counts as "no bass" for a break. */
    static final double BASS_GONE = 0.5;
    /** Bass at or above this counts as the kick running. */
    static final double KICK = 0.6;
    /** All bands below this: silence. */
    static final double SILENT = 0.3;
    /** Mid and high below this during a break: everything stopped, not only the bass. */
    static final double REST_GONE = 0.45;
    static final int MIN_BEATS = 2, MAX_BEATS = 8;
    /** A hit: bass at least this high, falling by {@link #HIT_DECAY} within the next three beats. */
    static final double HIT_LEVEL = 0.45, HIT_DECAY = 0.25, HIT_ONSET = 0.1;

    private BeatMoments() {
    }

    /** Moments of a track from its per-beat bands (index 0 = beat 1), ordered by beat. */
    public static List<Moment> detect(List<WaveformAnalyzer.BarBands> beats) {
        int n = beats.size();
        if (n < 16) return List.of();
        double[] low = relative(beats, WaveformAnalyzer.BarBands::low);
        double[] mid = relative(beats, WaveformAnalyzer.BarBands::mid);
        double[] high = relative(beats, WaveformAnalyzer.BarBands::high);
        double[] all = new double[n];
        for (int i = 0; i < n; i++) all[i] = Math.max(low[i], Math.max(mid[i], high[i]));

        List<Moment> out = new ArrayList<>();
        boolean[] covered = new boolean[n + 1];

        // the bass stops between two stretches of kick
        for (int i = 0; i < n; ) {
            if (low[i] >= BASS_GONE) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && low[j] < BASS_GONE) j++;
            int len = j - i;
            if (len >= MIN_BEATS && len <= MAX_BEATS && i >= 4 && j + 4 <= n
                    && count(low, i - 4, i, 0.5) >= 2 && max(low, i - 4, i) >= KICK && count(low, j, j + 4, KICK) >= 3) {
                boolean silent = mean(mid, i, j) < REST_GONE && mean(high, i, j) < REST_GONE;
                out.add(new Moment(i + 1, len, silent ? Kind.STOP : Kind.BREAK));
                for (int k = i; k <= j; k++) covered[k] = true;
            }
            i = j;
        }

        // everything goes silent after loud music (also without a kick, e.g. at the top of a breakdown)
        for (int i = 0; i < n; ) {
            if (all[i] >= SILENT) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && all[j] < SILENT) j++;
            int len = j - i;
            if (len >= MIN_BEATS && len <= MAX_BEATS && !covered[i] && i >= 4 && j + 4 <= n
                    && mean(all, i - 4, i) >= KICK && mean(all, j, j + 4) >= 0.5) {
                out.add(new Moment(i + 1, len, Kind.STOP));
                for (int k = i; k <= j; k++) covered[k] = true;
            }
            i = j;
        }

        // single bass hits: rise, then decay (a running kick stays level), repeating every bar or two
        List<Integer> hits = new ArrayList<>();
        for (int b = 1; b + 4 <= n; b++) {
            if (covered[b]) continue;
            double decay = low[b] - Math.min(low[b + 1], Math.min(low[b + 2], low[b + 3]));
            if (low[b] >= HIT_LEVEL && decay >= HIT_DECAY && low[b] - low[b - 1] >= HIT_ONSET) hits.add(b);
        }
        for (int b : hits) {
            boolean repeats = hits.contains(b - 4) || hits.contains(b + 4) || hits.contains(b - 8) || hits.contains(b + 8);
            if (repeats) out.add(new Moment(b + 1, 1, Kind.HIT));
        }
        out.sort(Comparator.comparingInt(Moment::startBeat));
        return out;
    }

    /** Increase of a 4-bar phrase's intensity over the phrase before it that makes it a build step. */
    static final double STEP_RISE = 0.07;

    /**
     * Where each build of at least two phrases gets harder. Intensity per bar weighs bass most, then the steadiness
     * of the kick, mids and highs; per phrase the quietest bar is left out (a gap or a stop before the drop).
     */
    public static List<Moment> buildSteps(TrackStructure st) {
        List<WaveformAnalyzer.BarBands> bars = WaveformAnalyzer.barsFromBeats(st.beatBands(), st.firstDownbeat());
        if (bars.size() < 8) return List.of();
        double[] low = WaveformAnalyzer.normalize(bars.stream().mapToDouble(WaveformAnalyzer.BarBands::low).toArray());
        double[] mid = WaveformAnalyzer.normalize(bars.stream().mapToDouble(WaveformAnalyzer.BarBands::mid).toArray());
        double[] high = WaveformAnalyzer.normalize(bars.stream().mapToDouble(WaveformAnalyzer.BarBands::high).toArray());
        int[] kick = WaveformAnalyzer.kickBeats(st.beatBands(), st.firstDownbeat(), bars.size());
        double[] intensity = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            intensity[i] = 0.45 * low[i] + 0.25 * mid[i] + 0.15 * high[i] + 0.15 * kick[i] / 4.0;
        }
        List<Moment> out = new ArrayList<>();
        for (TrackStructure.Segment seg : st.segments()) {
            if (seg.section() != automa3.model.Section.BUILD || seg.lengthBeats() < 32) continue;
            double before = Double.NaN;
            for (int beat = seg.startBeat(); beat < seg.endBeat(); beat += 16) {
                int from = Math.floorDiv(beat - st.firstDownbeat(), 4);
                int to = Math.min(bars.size(), Math.floorDiv(Math.min(seg.endBeat(), beat + 16) - st.firstDownbeat(), 4));
                if (from < 0 || to - from < 2) continue;
                double[] block = Arrays.copyOfRange(intensity, from, to);
                Arrays.sort(block);
                double level = 0;
                for (int i = 1; i < block.length; i++) level += block[i];
                level /= block.length - 1;
                if (!Double.isNaN(before) && level - before >= STEP_RISE) out.add(new Moment(beat, 1, Kind.STEP));
                before = level;
            }
        }
        return out;
    }

    private static double[] relative(List<WaveformAnalyzer.BarBands> beats, java.util.function.ToDoubleFunction<WaveformAnalyzer.BarBands> band) {
        double[] v = beats.stream().mapToDouble(band).toArray();
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double ref = Math.max(1e-9, percentile(sorted, 0.9));
        for (int i = 0; i < v.length; i++) v[i] /= ref;
        return v;
    }

    /** Linear interpolation between closest ranks, like numpy's default. */
    private static double percentile(double[] sorted, double p) {
        double pos = (sorted.length - 1) * p;
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    private static int count(double[] v, int from, int to, double atLeast) {
        int c = 0;
        for (int i = from; i < to; i++) if (v[i] >= atLeast) c++;
        return c;
    }

    private static double max(double[] v, int from, int to) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) m = Math.max(m, v[i]);
        return m;
    }

    private static double mean(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += v[i];
        return to > from ? s / (to - from) : 0;
    }
}
