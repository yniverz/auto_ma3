package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts rekordbox phrase analysis (the "PSSI" song structure) into engine sections.
 *
 * <p>rekordbox moods: HIGH (Intro / Up / Down / Chorus / Outro), MID and LOW
 * (Intro / Verse 1-6 / Bridge / Chorus / Outro). Consecutive phrases of the same kind are merged
 * first (rekordbox splits a 24-bar chorus into 8-bar phrases). Mapping for electronic music:
 * <ul>
 *   <li>Down, Bridge -> BREAKDOWN</li>
 *   <li>Chorus after an Intro / Up / Down / Bridge -> DROP (then PEAK), other Chorus -> PEAK</li>
 *   <li>Up leading into a Chorus -> BUILD, otherwise GROOVE</li>
 *   <li>Verse -> GROOVE</li>
 * </ul>
 * rekordbox phrases give reliable boundaries but are coarse (an "Up" can hold a groove, a breakdown and
 * the build). {@link #refineWithWaveform} fills in that detail from the waveform analysis.</p>
 */
public final class PhraseMapper {

    public enum Mood { HIGH, MID, LOW }

    /** One rekordbox phrase: start beat and phrase kind name (e.g. "UP", "VERSE_2", "CHORUS"). */
    public record Phrase(int beat, String kind) {
    }

    /** Longest build when the waveform cannot tell where it starts. */
    static final int DEFAULT_BUILD_BEATS = 32;

    private PhraseMapper() {
    }

    public static TrackStructure map(String trackKey, Mood mood, List<Phrase> phrases, int endBeat,
                                     List<Double> barEnergy, int firstDownbeat, int dropBeats) {
        return map(trackKey, mood, phrases, endBeat, barEnergy, firstDownbeat, dropBeats, List.of());
    }

    public static TrackStructure map(String trackKey, Mood mood, List<Phrase> phrases, int endBeat,
                                     List<Double> barEnergy, int firstDownbeat, int dropBeats,
                                     List<WaveformAnalyzer.BarBands> bands) {
        List<Phrase> merged = mergeSameKind(phrases);
        List<TrackStructure.Segment> raw = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            Phrase p = merged.get(i);
            int end = i + 1 < merged.size() ? merged.get(i + 1).beat : endBeat;
            if (end <= p.beat) continue;
            String kind = family(p.kind);
            String prevKind = i > 0 ? family(merged.get(i - 1).kind) : "";
            String nextKind = i + 1 < merged.size() ? family(merged.get(i + 1).kind) : "";
            String label = mood + " " + kind;

            Section section = switch (kind) {
                case "INTRO" -> Section.INTRO;
                case "OUTRO" -> Section.OUTRO;
                case "DOWN", "BRIDGE" -> Section.BREAKDOWN;
                case "CHORUS" -> switch (prevKind) {
                    case "INTRO", "UP", "DOWN", "BRIDGE" -> Section.DROP;
                    default -> Section.PEAK;
                };
                case "UP" -> nextKind.equals("CHORUS") ? Section.BUILD : Section.GROOVE;
                default -> Section.GROOVE; // VERSE_x and anything unknown
            };
            raw.add(new TrackStructure.Segment(p.beat, end, section, label));
        }
        return new TrackStructure(trackKey, "phrase", TrackStructure.normalize(raw, dropBeats), barEnergy, firstDownbeat, bands);
    }

    /** "VERSE_1B" -> "VERSE", "CHORUS" -> "CHORUS". */
    static String family(String kind) {
        if (kind == null) return "VERSE";
        String k = kind.toUpperCase();
        for (String f : new String[]{"INTRO", "OUTRO", "CHORUS", "BRIDGE", "VERSE", "DOWN", "UP"}) {
            if (k.startsWith(f)) return f;
        }
        return k;
    }

    private static List<Phrase> mergeSameKind(List<Phrase> phrases) {
        List<Phrase> out = new ArrayList<>();
        for (Phrase p : phrases) {
            if (!out.isEmpty() && family(out.get(out.size() - 1).kind).equals(family(p.kind))) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Add detail the phrases do not have, using the waveform analysis (may be null):
     * <ul>
     *   <li>a long BUILD phrase is cut to the real build; what comes before it takes the waveform's sections
     *       (groove / breakdown)</li>
     *   <li>a DROP straight after an intro or groove gets the build the waveform found right before it</li>
     * </ul>
     * Phrase boundaries (where drops start) are kept.
     */
    public static TrackStructure refineWithWaveform(TrackStructure phrase, TrackStructure wave, int dropBeats) {
        List<TrackStructure.Segment> in = phrase.segments();
        List<TrackStructure.Segment> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            TrackStructure.Segment s = in.get(i);
            if (s.section() == Section.BUILD && s.lengthBeats() > DEFAULT_BUILD_BEATS) {
                int buildStart = s.endBeat() - DEFAULT_BUILD_BEATS;
                TrackStructure.Segment wb = waveBuildEndingNear(wave, s.endBeat());
                if (wb != null) buildStart = Math.max(s.startBeat(), Math.min(s.endBeat() - 4, wb.startBeat()));
                if (buildStart > s.startBeat()) {
                    out.addAll(headFromWave(wave, s.startBeat(), buildStart, s.label()));
                }
                out.add(new TrackStructure.Segment(buildStart, s.endBeat(), Section.BUILD, s.label()));
            } else if (s.section() == Section.DROP && !out.isEmpty() && out.get(out.size() - 1).section() != Section.BUILD
                    && out.get(out.size() - 1).section() != Section.BREAKDOWN) {
                TrackStructure.Segment prev = out.get(out.size() - 1);
                TrackStructure.Segment wb = waveBuildEndingNear(wave, s.startBeat());
                if (wb != null && wb.startBeat() > prev.startBeat() + 4) {
                    out.set(out.size() - 1, new TrackStructure.Segment(prev.startBeat(), wb.startBeat(), prev.section(), prev.label()));
                    out.add(new TrackStructure.Segment(wb.startBeat(), s.startBeat(), Section.BUILD, "waveform build"));
                }
                out.add(s);
            } else {
                out.add(s);
            }
        }
        return new TrackStructure(phrase.trackKey(), "phrase", TrackStructure.normalize(out, dropBeats),
                phrase.barEnergy(), phrase.firstDownbeat(), phrase.bands(), phrase.beatBands());
    }

    /** A waveform BUILD that ends within 4 bars of {@code beat}. */
    private static TrackStructure.Segment waveBuildEndingNear(TrackStructure wave, int beat) {
        if (wave == null) return null;
        for (TrackStructure.Segment w : wave.segments()) {
            if (w.section() == Section.BUILD && Math.abs(w.endBeat() - beat) <= 16 && w.startBeat() < beat) return w;
        }
        return null;
    }

    /** Sections for [from, to) taken from the waveform (groove or breakdown), GROOVE if unknown. */
    private static List<TrackStructure.Segment> headFromWave(TrackStructure wave, int from, int to, String label) {
        List<TrackStructure.Segment> out = new ArrayList<>();
        if (wave != null) {
            for (TrackStructure.Segment w : wave.segments()) {
                int a = Math.max(from, w.startBeat()), b = Math.min(to, w.endBeat());
                if (b <= a) continue;
                Section s = w.section().isKicking() ? Section.GROOVE : Section.BREAKDOWN;
                out.add(new TrackStructure.Segment(a, b, s, label + " / waveform"));
            }
        }
        // fill gaps (waveform grid may not cover the edges)
        List<TrackStructure.Segment> filled = new ArrayList<>();
        int pos = from;
        for (TrackStructure.Segment seg : out) {
            if (seg.startBeat() > pos) filled.add(new TrackStructure.Segment(pos, seg.startBeat(), Section.GROOVE, label));
            filled.add(seg);
            pos = seg.endBeat();
        }
        if (pos < to) filled.add(new TrackStructure.Segment(pos, to, Section.GROOVE, label));
        return filled;
    }
}
