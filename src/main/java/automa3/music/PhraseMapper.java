package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts rekordbox phrase analysis (the "PSSI" song structure) into engine sections.
 *
 * <p>rekordbox moods: HIGH (Intro / Up / Down / Chorus / Outro), MID and LOW
 * (Intro / Verse 1-6 / Bridge / Chorus / Outro). Mapping for techno:
 * <ul>
 *   <li>Down, Bridge -> BREAKDOWN</li>
 *   <li>Chorus after a Down/Up/Bridge -> DROP (then PEAK), other Chorus -> PEAK</li>
 *   <li>Up: short and leading into a Chorus -> BUILD; longer -> GROOVE with the last 8 bars as BUILD</li>
 *   <li>Verse -> GROOVE</li>
 * </ul></p>
 */
public final class PhraseMapper {

    public enum Mood { HIGH, MID, LOW }

    /** One rekordbox phrase: start beat and phrase kind name (e.g. "UP", "VERSE_2", "CHORUS"). */
    public record Phrase(int beat, String kind) {
    }

    private PhraseMapper() {
    }

    public static TrackStructure map(String trackKey, Mood mood, List<Phrase> phrases, int endBeat,
                                     List<Double> barEnergy, int firstDownbeat, int dropBeats) {
        List<TrackStructure.Segment> raw = new ArrayList<>();
        for (int i = 0; i < phrases.size(); i++) {
            Phrase p = phrases.get(i);
            int end = i + 1 < phrases.size() ? phrases.get(i + 1).beat : endBeat;
            if (end <= p.beat) continue;
            String kind = p.kind == null ? "" : p.kind.toUpperCase();
            String prevKind = i > 0 && phrases.get(i - 1).kind != null ? phrases.get(i - 1).kind.toUpperCase() : "";
            String nextKind = i + 1 < phrases.size() && phrases.get(i + 1).kind != null ? phrases.get(i + 1).kind.toUpperCase() : "";
            String label = mood + " " + kind;

            if (kind.startsWith("INTRO")) {
                raw.add(new TrackStructure.Segment(p.beat, end, Section.INTRO, label));
            } else if (kind.startsWith("OUTRO")) {
                raw.add(new TrackStructure.Segment(p.beat, end, Section.OUTRO, label));
            } else if (kind.startsWith("DOWN") || kind.startsWith("BRIDGE")) {
                raw.add(new TrackStructure.Segment(p.beat, end, Section.BREAKDOWN, label));
            } else if (kind.startsWith("CHORUS")) {
                boolean afterLull = prevKind.startsWith("DOWN") || prevKind.startsWith("BRIDGE") || prevKind.startsWith("UP");
                raw.add(new TrackStructure.Segment(p.beat, end, afterLull ? Section.DROP : Section.PEAK, label));
            } else if (kind.startsWith("UP")) {
                boolean leadsToChorus = nextKind.startsWith("CHORUS");
                int len = end - p.beat;
                if (leadsToChorus && len <= 64) {
                    raw.add(new TrackStructure.Segment(p.beat, end, Section.BUILD, label));
                } else if (leadsToChorus) {
                    raw.add(new TrackStructure.Segment(p.beat, end - 32, Section.GROOVE, label));
                    raw.add(new TrackStructure.Segment(end - 32, end, Section.BUILD, label));
                } else {
                    raw.add(new TrackStructure.Segment(p.beat, end, Section.GROOVE, label));
                }
            } else {
                // VERSE_x and anything unknown
                raw.add(new TrackStructure.Segment(p.beat, end, Section.GROOVE, label));
            }
        }
        return new TrackStructure(trackKey, "phrase", TrackStructure.normalize(raw, dropBeats), barEnergy, firstDownbeat);
    }
}
