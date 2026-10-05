package automa3.music;

import automa3.engine.ShowEngine;
import automa3.music.BeatMoments.Kind;
import automa3.music.BeatMoments.Moment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Breaks and bass hits in real CDJ-3000 waveform data (as saved by the app), checked against the audio. */
class BeatMomentsTest {

    private static AnalysisDump dump(String name) throws Exception {
        return AnalysisDump.load(java.nio.file.Path.of(BeatMomentsTest.class.getResource("/tracks/" + name).toURI()));
    }

    private static TrackStructure structure(AnalysisDump d) {
        return WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), 64);
    }

    /** Breaks the engine reacts to (filtered like the engine does). */
    private static List<Moment> breaks(TrackStructure st) {
        return BeatMoments.detect(st.beatBands()).stream()
                .filter(m -> m.kind() != Kind.HIT && ShowEngine.breakCounts(st, m)).toList();
    }

    private static List<Integer> hitBeats(TrackStructure st) {
        return BeatMoments.detect(st.beatBands()).stream().filter(m -> m.kind() == Kind.HIT).map(Moment::startBeat).toList();
    }

    @Test
    void february7BarLongBreaksInThePeakAndBeforeTheDrops() throws Exception {
        TrackStructure st = structure(dump("february-7.json"));
        List<Moment> breaks = breaks(st);
        // bar 33 (beat 130): almost silent; every 8 bars after: only the bass drops out for a bar
        assertTrue(breaks.contains(new Moment(130, 4, Kind.STOP)), breaks.toString());
        for (int beat : new int[]{162, 194, 226, 434, 498}) {
            assertTrue(breaks.contains(new Moment(beat, 4, Kind.BREAK)), "1-bar break at beat " + beat + ": " + breaks);
        }
        // the bar without bass right before the drop at 406
        assertTrue(breaks.contains(new Moment(402, 4, Kind.BREAK)), breaks.toString());
        // the fade into the drop at 470 counts too
        assertTrue(breaks.stream().anyMatch(m -> m.endBeat() == 470), breaks.toString());
        // nothing else: no break inside the build (bass hits only there) or the breakdown
        assertEquals(8, breaks.size(), breaks.toString());
    }

    @Test
    void february7SingleBassHitsEveryBarBeforeTheDrop() throws Exception {
        TrackStructure st = structure(dump("february-7.json"));
        List<Integer> hits = hitBeats(st);
        // the 808 on the one in the build (bars 90-98), seen in the audio
        for (int beat : new int[]{358, 362, 366, 370, 374, 382, 390}) {
            assertTrue(hits.contains(beat), "hit at beat " + beat + ": " + hits);
        }
        // never inside the drops, where the kick runs
        for (int beat : hits) {
            assertFalse(beat >= 406 && beat < 530 || beat >= 134 && beat < 258, "no hit in a drop: " + beat);
        }
    }

    @Test
    void voicemailHalfBarKickBreaksSilenceAndPreDropGap() throws Exception {
        TrackStructure st = structure(dump("voicemail.json"));
        List<Moment> breaks = breaks(st);
        assertEquals(List.of(new Moment(130, 2, Kind.BREAK), new Moment(293, 4, Kind.STOP),
                new Moment(357, 4, Kind.BREAK), new Moment(390, 2, Kind.BREAK)), breaks);
    }

    @Test
    void noBassHitsWhereNoneAre() throws Exception {
        for (String name : new String[]{"voicemail.json", "wild.json", "twilight-zone.json"}) {
            TrackStructure st = structure(dump(name));
            for (int beat : hitBeats(st)) {
                TrackStructure.Segment seg = st.segmentAt(beat);
                assertFalse(seg != null && (seg.section() == automa3.model.Section.BUILD
                        || seg.section() == automa3.model.Section.BREAKDOWN), name + ": hit in " + seg + " at beat " + beat);
            }
        }
    }

    @Test
    void steadyKickHasNoMoments() {
        List<WaveformAnalyzer.BarBands> beats = new java.util.ArrayList<>();
        for (int i = 0; i < 256; i++) beats.add(new WaveformAnalyzer.BarBands(200, 120, 90));
        assertEquals(List.of(), BeatMoments.detect(beats));
    }
}
