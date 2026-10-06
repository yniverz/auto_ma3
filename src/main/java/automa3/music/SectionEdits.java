package automa3.music;

import automa3.config.ConfigStore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sections the operator corrected by hand, per song. They replace the analysis of that song (rekordbox phrases or
 * the waveform) wherever it is loaded, and are kept in a file so they are there again next time.
 *
 * <p>A song is identified by artist and title, not by the CDJ's track key (player, slot and rekordbox id), so the
 * edit still applies when the USB stick is in another player. Beats are rekordbox beat grid numbers.</p>
 */
public class SectionEdits {

    private static final Logger log = LoggerFactory.getLogger(SectionEdits.class);
    static final int MAX_SEGMENTS = 400;
    static final int MAX_BEAT = 100_000;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edit(String title, String artist, String basedOn, List<TrackStructure.Segment> segments, long updatedMs) {
    }

    private final Path file;
    private final Map<String, Edit> edits = new LinkedHashMap<>();

    /** In memory only (tests, or no data folder). */
    public SectionEdits() {
        this(null);
    }

    public SectionEdits(Path file) {
        this.file = file;
        if (file != null && Files.exists(file)) {
            try {
                Map<String, Edit> loaded = ConfigStore.JSON.readValue(file.toFile(), new TypeReference<>() {
                });
                loaded.forEach((k, e) -> {
                    try {
                        edits.put(k, new Edit(e.title(), e.artist(), e.basedOn(), check(e.segments()), e.updatedMs()));
                    } catch (IllegalArgumentException bad) {
                        log.warn("Ignoring the section edit of {}: {}", k, bad.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Cannot read {}: {}", file, e.getMessage());
            }
        }
    }

    /** The key of a song: artist and title (case and spacing ignored), or the CDJ's track key without a title. */
    public static String songKey(String title, String artist, String trackKey) {
        if (title == null || title.isBlank()) return trackKey == null ? null : "track:" + trackKey;
        return norm(artist) + "\u0000" + norm(title);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public synchronized Edit get(String songKey) {
        return songKey == null ? null : edits.get(songKey);
    }

    /** Store the corrected sections of a song (checked) and return them. */
    public synchronized List<TrackStructure.Segment> put(String songKey, String title, String artist, String basedOn,
                                                         List<TrackStructure.Segment> segments) {
        if (songKey == null) throw new IllegalArgumentException("no track loaded");
        List<TrackStructure.Segment> clean = check(segments);
        edits.put(songKey, new Edit(title, artist, basedOn, clean, System.currentTimeMillis()));
        save();
        return clean;
    }

    public synchronized boolean remove(String songKey) {
        boolean had = songKey != null && edits.remove(songKey) != null;
        if (had) save();
        return had;
    }

    public synchronized int size() {
        return edits.size();
    }

    /** Sorted, back to back (each section starts where the previous one ends), at least one beat long. */
    static List<TrackStructure.Segment> check(List<TrackStructure.Segment> in) {
        if (in == null || in.isEmpty()) throw new IllegalArgumentException("no sections");
        if (in.size() > MAX_SEGMENTS) throw new IllegalArgumentException("too many sections");
        List<TrackStructure.Segment> out = new ArrayList<>();
        for (TrackStructure.Segment s : in) {
            if (s == null || s.section() == null) throw new IllegalArgumentException("a section without a type");
            if (s.startBeat() < 1 || s.endBeat() > MAX_BEAT || s.endBeat() <= s.startBeat()) {
                throw new IllegalArgumentException("section " + s.startBeat() + "-" + s.endBeat() + " is not a valid beat range");
            }
            if (!out.isEmpty() && s.startBeat() != out.get(out.size() - 1).endBeat()) {
                throw new IllegalArgumentException("sections must follow each other without gaps (beat " + s.startBeat() + ")");
            }
            // neighbours of the same type stay apart: a split section is retyped right after
            out.add(new TrackStructure.Segment(s.startBeat(), s.endBeat(), s.section(), "edited"));
        }
        return List.copyOf(out);
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            ConfigStore.JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), edits);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Cannot save {}: {}", file, e.getMessage());
        }
    }
}
