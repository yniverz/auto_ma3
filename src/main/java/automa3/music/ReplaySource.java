package automa3.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replays a session recorded by {@link SessionRecorder} in real time (or faster).
 */
public class ReplaySource implements MusicSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final double speed;
    private volatile boolean running;
    private volatile String status = "idle";
    private Thread thread;

    public ReplaySource(Path file, double speed) {
        this.file = file;
        this.speed = speed <= 0 ? 1 : speed;
    }

    @Override
    public String name() {
        return "Replay " + file.getFileName();
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void start(MusicListener listener) {
        running = true;
        thread = new Thread(() -> run(listener), "replay");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(MusicListener listener) {
        long start = System.nanoTime();
        int lines = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while (running && (line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = JSON.readTree(line);
                long due = start + (long) (node.get("t").asLong() * 1_000_000L / speed);
                long wait = due - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                JsonNode data = node.get("data");
                switch (node.get("type").asText()) {
                    case "deck" -> listener.onDeck(JSON.treeToValue(data, DeckState.class));
                    case "beat" -> {
                        BeatEvent b = JSON.treeToValue(data, BeatEvent.class);
                        listener.onBeat(new BeatEvent(b.player(), b.beatNumber(), b.beatWithinBar(), b.bpm(), System.nanoTime()));
                    }
                    case "structure" -> listener.onStructure(data.get("player").asInt(),
                            JSON.treeToValue(data.get("structure"), TrackStructure.class));
                    default -> {
                    }
                }
                if (++lines % 50 == 0) status = "replaying, " + (node.get("t").asLong() / 1000) + " s in";
            }
            status = "replay finished";
        } catch (InterruptedException e) {
            status = "stopped";
        } catch (Exception e) {
            status = "replay error: " + e.getMessage();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
