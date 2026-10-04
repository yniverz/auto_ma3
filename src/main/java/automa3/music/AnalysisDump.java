package automa3.music;

import automa3.config.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What the app saw for one track: per-bar waveform bands, the beat grid layout and the sections it
 * found. Written to analysis/ for every analysed track so the analysis can be checked and tuned
 * offline ({@code --analyze FILE}) against real music.
 */
public record AnalysisDump(String title, String artist, String trackKey, String waveformStyle, double bpm,
                           int firstDownbeat, int beatCount, boolean hasPhraseAnalysis,
                           List<Long> barStartMs, List<WaveformAnalyzer.BarBands> bands,
                           List<WaveformAnalyzer.BarBands> beatBands,
                           List<TrackStructure.Segment> waveformSections,
                           List<TrackStructure.Segment> phraseSections) {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDump.class);

    public void save(Path dir) {
        try {
            Files.createDirectories(dir);
            String base = ((artist == null ? "" : artist + " - ") + (title == null ? trackKey : title))
                    .replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
            if (base.length() > 100) base = base.substring(0, 100);
            Path file = dir.resolve(base + ".json");
            ConfigStore.JSON.writeValue(file.toFile(), this);
            log.info("Analysis saved: {}", file.getFileName());
        } catch (IOException e) {
            log.warn("Could not save analysis: {}", e.getMessage());
        }
    }

    public static AnalysisDump load(Path file) throws IOException {
        return ConfigStore.JSON.readValue(file.toFile(), AnalysisDump.class);
    }

    /** Re-run the current waveform analysis on the stored bands and print a readable table. */
    public static void printAnalysis(Path file, int dropBeats) throws IOException {
        AnalysisDump d = load(file);
        TrackStructure st = d.beatBands() != null && !d.beatBands().isEmpty()
                ? WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), dropBeats)
                : WaveformAnalyzer.analyze(d.trackKey(), d.bands(), d.firstDownbeat(), dropBeats);
        System.out.printf("%s - %s  (%.1f BPM, %s waveform, first downbeat %d, %d bars)%n",
                d.artist(), d.title(), d.bpm(), d.waveformStyle(), d.firstDownbeat(), d.bands().size());
        System.out.println();
        System.out.println(" bar  beat   time    low  mid  high  energy  section");
        for (int i = 0; i < d.bands().size(); i++) {
            int beat = d.firstDownbeat() + i * 4;
            TrackStructure.Segment seg = st.segmentAt(beat);
            boolean starts = seg != null && seg.startBeat() == beat;
            WaveformAnalyzer.BarBands b = d.bands().get(i);
            long ms = i < d.barStartMs().size() ? d.barStartMs().get(i) : 0;
            System.out.printf("%4d %5d  %2d:%02d  %5.1f %4.1f %5.1f  %5.2f   %s%n", i + 1, beat, ms / 60000, (ms / 1000) % 60,
                    b.low(), b.mid(), b.high(), st.energyAt(beat), starts ? "<- " + seg.section() + " (" + seg.label() + ")" : "");
        }
        if (d.phraseSections() != null && !d.phraseSections().isEmpty()) {
            System.out.println();
            System.out.println("rekordbox phrases:");
            for (TrackStructure.Segment s : d.phraseSections()) {
                System.out.printf("  beat %5d - %5d  %s (%s)%n", s.startBeat(), s.endBeat(), s.section(), s.label());
            }
        }
    }
}
