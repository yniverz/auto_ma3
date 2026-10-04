package automa3.music;

import automa3.model.Section;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {

    private static Section at(TrackStructure st, int beat) {
        TrackStructure.Segment s = st.segmentAt(beat);
        return s == null ? null : s.section();
    }

    @Test
    void highMoodPhrasesMapToTechnoSections() {
        // intro 16 bars, up 8, chorus 32, down 16, chorus 32, outro 16 (beats)
        List<PhraseMapper.Phrase> phrases = List.of(
                new PhraseMapper.Phrase(1, "INTRO"),
                new PhraseMapper.Phrase(65, "UP"),
                new PhraseMapper.Phrase(97, "CHORUS"),
                new PhraseMapper.Phrase(225, "DOWN"),
                new PhraseMapper.Phrase(289, "CHORUS"),
                new PhraseMapper.Phrase(417, "OUTRO"));
        TrackStructure st = PhraseMapper.map("t", PhraseMapper.Mood.HIGH, phrases, 481, List.of(), 1, 64);
        assertEquals(Section.INTRO, at(st, 1));
        assertEquals(Section.BUILD, at(st, 65));
        assertEquals(Section.DROP, at(st, 97));
        assertEquals(Section.PEAK, at(st, 97 + 64));
        // a 16 bar DOWN before a CHORUS gets its last 8 bars turned into a BUILD
        assertEquals(Section.BREAKDOWN, at(st, 225));
        assertEquals(Section.BUILD, at(st, 289 - 4));
        assertEquals(Section.DROP, at(st, 289));
        assertEquals(Section.OUTRO, at(st, 420));
    }

    @Test
    void midMoodVerseIsGrooveAndChorusWithoutLullIsPeak() {
        List<PhraseMapper.Phrase> phrases = List.of(
                new PhraseMapper.Phrase(1, "INTRO"),
                new PhraseMapper.Phrase(33, "VERSE_1"),
                new PhraseMapper.Phrase(161, "CHORUS"),
                new PhraseMapper.Phrase(225, "OUTRO"));
        TrackStructure st = PhraseMapper.map("t", PhraseMapper.Mood.MID, phrases, 289, List.of(), 1, 64);
        assertEquals(Section.GROOVE, at(st, 40));
        assertEquals(Section.PEAK, at(st, 170));
    }

    @Test
    void waveformFindsBreakdownBuildAndDrop() {
        SimulatedSource.Template t = SimulatedSource.TEMPLATES.get(0);
        TrackStructure st = WaveformAnalyzer.analyze("t", SimulatedSource.syntheticWaveform(t, new Random(1)), 1, 64);
        // template 0: INTRO 16, GROOVE 32, BREAKDOWN 16, BUILD 8, DROP 32, BREAKDOWN 8, BUILD 8, DROP 32, OUTRO 16 (bars)
        assertEquals(Section.INTRO, at(st, 1));
        assertEquals(Section.GROOVE, at(st, bar(20)));
        assertEquals(Section.BREAKDOWN, at(st, bar(50)));
        assertEquals(Section.BUILD, at(st, bar(70)));
        assertEquals(Section.DROP, at(st, bar(74)));
        assertEquals(Section.PEAK, at(st, bar(95)));
        assertEquals(Section.BUILD, at(st, bar(118)));
        assertEquals(Section.DROP, at(st, bar(122)));
        assertEquals(Section.OUTRO, at(st, bar(160)));
        assertFalse(Double.isNaN(st.energyAt(bar(80))));
        assertTrue(st.energyAt(bar(80)) > st.energyAt(bar(50)));
    }

    @Test
    void waveformOfAllDifferentTemplatesHasADrop() {
        Random r = new Random(3);
        for (SimulatedSource.Template t : SimulatedSource.TEMPLATES) {
            TrackStructure st = WaveformAnalyzer.analyze(t.name(), SimulatedSource.syntheticWaveform(t, r), 1, 64);
            assertTrue(st.segments().stream().anyMatch(s -> s.section() == Section.DROP), t.name());
        }
    }

    /** House / EDM layout: the kick keeps running through the build, the drop is an energy jump. */
    @Test
    void waveformFindsEdmDropAfterBuildWithKick() {
        Random r = new Random(5);
        List<WaveformAnalyzer.BarBands> bars = new java.util.ArrayList<>();
        java.util.function.BiConsumer<Integer, double[]> add = (count, lmh) -> {
            for (int i = 0; i < count; i++) {
                double n = r.nextGaussian() * 0.03;
                bars.add(new WaveformAnalyzer.BarBands(lmh[0] + n, lmh[1] + n, lmh[2] + n));
            }
        };
        add.accept(12, new double[]{0.65, 0.35, 0.35});  // intro: kick + hats      bars 1-12
        add.accept(4, new double[]{0.65, 0.50, 0.60});   // build with kick + riser bars 13-16
        add.accept(16, new double[]{1.00, 0.90, 0.85});  // drop                    bars 17-32
        add.accept(16, new double[]{0.10, 0.45, 0.30});  // breakdown, no bass      bars 33-48
        add.accept(8, new double[]{0.60, 0.55, 0.70});   // build, kick back        bars 49-56
        add.accept(16, new double[]{1.00, 0.90, 0.85});  // drop 2                  bars 57-72
        add.accept(16, new double[]{0.60, 0.40, 0.40});  // outro with kick         bars 73-88
        TrackStructure st = WaveformAnalyzer.analyze("edm", bars, 1, 64);

        assertEquals(Section.INTRO, at(st, bar(12)), "intro with kick is not part of the build");
        assertEquals(Section.BUILD, at(st, bar(13)), "build starts where mids / highs rise");
        assertEquals(Section.DROP, at(st, bar(17)), "first drop exactly on bar 17 (beat 65)");
        assertEquals(bar(17), st.segmentAt(bar(17)).startBeat());
        assertEquals(Section.BREAKDOWN, at(st, bar(35)));
        assertEquals(Section.BUILD, at(st, bar(50)));
        assertEquals(Section.DROP, at(st, bar(57)));
        assertEquals(bar(57), st.segmentAt(bar(57)).startBeat());
        assertEquals(Section.OUTRO, at(st, bar(80)));
    }

    /** A build whose last bars are as loud as the drop (snare roll, riser) but without full bass. */
    @Test
    void dropIsPlacedOnTheBassJumpNotOnALoudBuild() {
        List<WaveformAnalyzer.BarBands> beats = new java.util.ArrayList<>();
        java.util.function.BiConsumer<Integer, double[]> addBars = (count, lmh) -> {
            for (int i = 0; i < count * 4; i++) beats.add(new WaveformAnalyzer.BarBands(lmh[0], lmh[1], lmh[2]));
        };
        addBars.accept(12, new double[]{0.65, 0.35, 0.35}); // intro with kick       bars 1-12
        addBars.accept(1, new double[]{0.65, 0.40, 0.70});  // build starts         bar 13
        addBars.accept(3, new double[]{0.80, 0.45, 1.00});  // kick + loud snare roll bars 14-16
        addBars.accept(16, new double[]{1.00, 0.90, 0.85}); // drop                 bars 17-32
        addBars.accept(16, new double[]{0.60, 0.40, 0.40}); // outro                bars 33-48
        TrackStructure st = WaveformAnalyzer.analyzeBeats("loud-build", beats, 1, 64);
        assertEquals(Section.DROP, at(st, bar(17)));
        assertEquals(bar(17), st.segmentAt(bar(17)).startBeat(), "drop on the bass jump, not on the loud build");
        assertNotEquals(Section.DROP, at(st, bar(15)));
        assertEquals(beats.size(), st.beatBands().size(), "per-beat data kept for the display");
    }

    /** Real CDJ-3000 waveform data of James Hype - Wild (as saved by the app). Drops at beat 67 and 227. */
    @Test
    void realTrackWildDropsOnTheBeat() throws Exception {
        TrackStructure st = analyzeFixture("wild.json");
        assertEquals(Section.DROP, at(st, 67));
        assertEquals(67, st.segmentAt(67).startBeat(), "first drop at beat 67");
        assertEquals(Section.BUILD, at(st, 63), "build right before the drop");
        assertEquals(Section.DROP, at(st, 227));
        assertEquals(227, st.segmentAt(227).startBeat(), "second drop at beat 227");
        assertNotEquals(Section.DROP, at(st, 215));
    }

    private static TrackStructure analyzeFixture(String name) throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(AnalysisTest.class.getResource("/tracks/" + name).toURI());
        AnalysisDump d = AnalysisDump.load(file);
        return WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), 64);
    }

    private static int bar(int bar) {
        return 1 + (bar - 1) * 4;
    }
}
