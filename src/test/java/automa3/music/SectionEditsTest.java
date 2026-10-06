package automa3.music;

import automa3.model.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectionEditsTest {

    @TempDir
    Path tmp;

    static TrackStructure.Segment seg(int from, int to, Section s) {
        return new TrackStructure.Segment(from, to, s, "x");
    }

    @Test
    void keptPerSongAcrossRestarts() {
        Path file = tmp.resolve("section-edits.json");
        SectionEdits a = new SectionEdits(file);
        String key = SectionEdits.songKey("Feb 7", "Someone", "2:3:117");
        List<TrackStructure.Segment> stored = a.put(key, "Feb 7", "Someone", "phrase",
                List.of(seg(1, 65, Section.INTRO), seg(65, 129, Section.GROOVE), seg(129, 193, Section.GROOVE)));
        assertEquals(3, stored.size(), "a split stays split until it is retyped");
        assertEquals("edited", stored.get(1).label());

        SectionEdits b = new SectionEdits(file);
        SectionEdits.Edit e = b.get(SectionEdits.songKey("  feb 7 ", "SOMEONE", "3:2:999")); // another player and stick
        assertNotNull(e);
        assertEquals(stored, e.segments());
        assertEquals("phrase", e.basedOn());
        assertTrue(b.remove(key));
        assertNull(new SectionEdits(file).get(key));
    }

    @Test
    void withoutATitleTheCdjsTrackKeyIdentifiesTheSong() {
        assertEquals("track:2:3:117", SectionEdits.songKey(null, null, "2:3:117"));
        assertEquals("track:2:3:117", SectionEdits.songKey(" ", "x", "2:3:117"));
        assertNull(SectionEdits.songKey(null, null, null));
        assertNotEquals(SectionEdits.songKey("A", "B", null), SectionEdits.songKey("B", "A", null));
    }

    @Test
    void rejectsSectionsThatDoNotFitTogether() {
        SectionEdits e = new SectionEdits();
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null, List.of(seg(1, 65, Section.INTRO), seg(69, 129, Section.DROP))), "gap");
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null, List.of(seg(1, 65, Section.INTRO), seg(60, 129, Section.DROP))), "overlap");
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null, List.of(seg(5, 5, Section.INTRO))), "empty");
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null, List.of(seg(0, 5, Section.INTRO))), "before beat 1");
        assertThrows(IllegalArgumentException.class, () -> e.put("k", "t", "a", null,
                List.of(new TrackStructure.Segment(1, 5, null, "x"))), "no type");
        assertThrows(IllegalArgumentException.class, () -> e.put(null, "t", "a", null, List.of(seg(1, 5, Section.INTRO))), "no track");
        assertEquals(0, e.size());
    }

    @Test
    void aBrokenFileIsIgnored() throws Exception {
        Path file = tmp.resolve("section-edits.json");
        java.nio.file.Files.writeString(file, "{\"x\": {\"segments\": [{\"startBeat\": 9, \"endBeat\": 3, \"section\": \"DROP\"}]}, ");
        assertEquals(0, new SectionEdits(file).size());
        java.nio.file.Files.writeString(file, "{\"bad\": {\"segments\": [{\"startBeat\": 9, \"endBeat\": 3, \"section\": \"DROP\"}]},"
                + " \"good\": {\"segments\": [{\"startBeat\": 1, \"endBeat\": 3, \"section\": \"DROP\"}]}}");
        SectionEdits e = new SectionEdits(file);
        assertEquals(1, e.size());
        assertNotNull(e.get("good"));
    }
}
