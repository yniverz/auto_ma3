package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Section map of one track in beats (beat numbers as in the rekordbox beat grid, starting at 1).
 *
 * @param trackKey identifies the track (player + rekordbox id or title)
 * @param source   "phrase" (rekordbox phrase analysis), "waveform" (own analysis) or "sim"
 * @param segments ordered, non-overlapping; endBeat is exclusive
 * @param barEnergy optional relative energy (0..1) per bar index from the waveform, may be empty
 * @param firstDownbeat beat number of the first bar's downbeat (for bar energy lookup)
 * @param bands raw low / mid / high level per bar from the waveform (for tuning and re-analysis), may be empty
 * @param beatBands raw low / mid / high level per beat (index 0 = beat 1) for display and re-analysis, may be empty
 */
public record TrackStructure(String trackKey, String source, List<Segment> segments, List<Double> barEnergy,
                             int firstDownbeat, List<WaveformAnalyzer.BarBands> bands,
                             List<WaveformAnalyzer.BarBands> beatBands) {

    public record Segment(int startBeat, int endBeat, Section section, String label) {
        public int lengthBeats() {
            return endBeat - startBeat;
        }
    }

    public TrackStructure {
        segments = List.copyOf(segments);
        barEnergy = barEnergy == null ? List.of() : List.copyOf(barEnergy);
        bands = bands == null ? List.of() : List.copyOf(bands);
        beatBands = beatBands == null ? List.of() : List.copyOf(beatBands);
    }

    public TrackStructure(String trackKey, String source, List<Segment> segments, List<Double> barEnergy, int firstDownbeat) {
        this(trackKey, source, segments, barEnergy, firstDownbeat, List.of(), List.of());
    }

    public TrackStructure(String trackKey, String source, List<Segment> segments, List<Double> barEnergy, int firstDownbeat,
                          List<WaveformAnalyzer.BarBands> bands) {
        this(trackKey, source, segments, barEnergy, firstDownbeat, bands, List.of());
    }

    public TrackStructure withBeatBands(List<WaveformAnalyzer.BarBands> beats) {
        return new TrackStructure(trackKey, source, segments, barEnergy, firstDownbeat, bands, beats);
    }

    public Segment segmentAt(int beat) {
        for (Segment s : segments) if (beat >= s.startBeat && beat < s.endBeat) return s;
        return null;
    }

    public Segment nextAfter(Segment current) {
        int idx = segments.indexOf(current);
        return idx >= 0 && idx + 1 < segments.size() ? segments.get(idx + 1) : null;
    }

    public int lastBeat() {
        return segments.isEmpty() ? 0 : segments.get(segments.size() - 1).endBeat;
    }

    /** Relative energy at a beat from the waveform, or NaN if unknown. */
    public double energyAt(int beat) {
        if (barEnergy.isEmpty()) return Double.NaN;
        int bar = Math.floorDiv(beat - firstDownbeat, 4);
        if (bar < 0 || bar >= barEnergy.size()) return Double.NaN;
        return barEnergy.get(bar);
    }

    /**
     * Post-process a raw section list: merge neighbours of equal type, split long DROP runs into
     * DROP + PEAK and make sure every drop that follows a breakdown has a BUILD lead-in.
     */
    public static List<Segment> normalize(List<Segment> raw, int dropBeats) {
        List<Segment> in = new ArrayList<>(raw);
        Collections.sort(in, (a, b) -> Integer.compare(a.startBeat, b.startBeat));

        // merge equal neighbours
        List<Segment> merged = new ArrayList<>();
        for (Segment s : in) {
            if (s.lengthBeats() <= 0) continue;
            if (!merged.isEmpty()) {
                Segment last = merged.get(merged.size() - 1);
                if (last.section == s.section && last.endBeat >= s.startBeat) {
                    merged.set(merged.size() - 1, new Segment(last.startBeat, Math.max(last.endBeat, s.endBeat), s.section, last.label));
                    continue;
                }
            }
            merged.add(s);
        }

        // BUILD lead-in before drops that come straight out of a breakdown
        List<Segment> withBuild = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            Segment s = merged.get(i);
            Segment next = i + 1 < merged.size() ? merged.get(i + 1) : null;
            if (s.section == Section.BREAKDOWN && next != null && next.section == Section.DROP && s.lengthBeats() >= 32) {
                int buildLen = Math.min(32, (s.lengthBeats() / 2) / 4 * 4);
                withBuild.add(new Segment(s.startBeat, s.endBeat - buildLen, Section.BREAKDOWN, s.label));
                withBuild.add(new Segment(s.endBeat - buildLen, s.endBeat, Section.BUILD, "build (derived)"));
            } else {
                withBuild.add(s);
            }
        }

        // split DROP into DROP (first dropBeats) + PEAK
        List<Segment> out = new ArrayList<>();
        for (Segment s : withBuild) {
            if (s.section == Section.DROP && s.lengthBeats() > dropBeats + 16) {
                out.add(new Segment(s.startBeat, s.startBeat + dropBeats, Section.DROP, s.label));
                out.add(new Segment(s.startBeat + dropBeats, s.endBeat, Section.PEAK, s.label));
            } else {
                out.add(s);
            }
        }
        return out;
    }
}
