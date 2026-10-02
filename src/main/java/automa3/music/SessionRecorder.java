package automa3.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records every music event to a JSON-lines file so a gig can be replayed later
 * ({@link ReplaySource}) for debugging and tuning without CDJs.
 */
public class SessionRecorder implements MusicListener {

    private static final Logger log = LoggerFactory.getLogger(SessionRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private BufferedWriter writer;
    private Path file;
    private long startNanos;

    public SessionRecorder(Path dir) {
        this.dir = dir;
    }

    public synchronized boolean isRecording() {
        return writer != null;
    }

    public synchronized Path file() {
        return file;
    }

    public synchronized void start() throws IOException {
        if (writer != null) return;
        Files.createDirectories(dir);
        file = dir.resolve("session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        startNanos = System.nanoTime();
        log.info("Recording session to {}", file);
    }

    public synchronized void stop() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Closing recording failed: {}", e.getMessage());
        }
        writer = null;
        log.info("Recording saved: {}", file);
    }

    private synchronized void write(String type, Object data) {
        if (writer == null) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", (System.nanoTime() - startNanos) / 1_000_000);
        line.put("type", type);
        line.put("data", data);
        try {
            writer.write(JSON.writeValueAsString(line));
            writer.newLine();
            if ("structure".equals(type)) writer.flush();
        } catch (IOException e) {
            log.warn("Recording write failed: {}", e.getMessage());
        }
    }

    @Override
    public void onDeck(DeckState deck) {
        write("deck", deck);
    }

    @Override
    public void onBeat(BeatEvent beat) {
        write("beat", beat);
    }

    @Override
    public void onStructure(int player, TrackStructure structure) {
        write("structure", Map.of("player", player, "structure", structure));
    }
}
