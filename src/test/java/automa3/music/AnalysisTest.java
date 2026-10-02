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

    private static int bar(int bar) {
        return 1 + (bar - 1) * 4;
    }
}
