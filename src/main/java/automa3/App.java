package automa3;

import automa3.audio.AudioAnalyzer;
import automa3.config.Config;
import automa3.config.ConfigLibrary;
import automa3.config.ConfigStore;
import automa3.engine.OperatorState;
import automa3.engine.ShowEngine;
import automa3.engine.TaskScheduler;
import automa3.ma3.ConsoleHub;
import automa3.music.BeatEvent;
import automa3.music.DeckState;
import automa3.music.MusicListener;
import automa3.music.MusicSource;
import automa3.music.SessionRecorder;
import automa3.music.TrackStructure;
import automa3.web.WebServer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Random;

/**
 * Wires the parts together: music source -> engine -> console hub, plus web UI and recorder.
 */
public class App {

    private final ConfigStore config;
    private final ConfigLibrary library;
    private final ConsoleHub hub;
    private final TaskScheduler scheduler = new TaskScheduler.Real();
    private final ShowEngine engine;
    private volatile MusicSource source;
    private MusicListener musicListener;
    private final SessionRecorder recorder;
    private final WebServer web;
    private volatile AudioAnalyzer audio;
    private String audioKey = "";

    public App(ConfigStore config, MusicSource source, Path recordingsDir) throws Exception {
        this.config = config;
        this.library = new ConfigLibrary(config, config.path().toAbsolutePath().getParent().resolve("configs"));
        this.source = source;
        this.hub = new ConsoleHub(config);
        this.recorder = new SessionRecorder(recordingsDir);
        this.engine = new ShowEngine(config, scheduler, hub::send, new OperatorState(),
                () -> audio == null ? AudioAnalyzer.AudioState.OFF : audio.state(), new Random());
        this.web = new WebServer(this);
        hub.onOsc((m, from) -> engine.onOsc(m));
        config.onChange(c -> updateAudio());
    }

    public void start(int webPort) throws Exception {
        Config cfg = config.get();
        hub.startReceiver();
        updateAudio();
        musicListener = new MusicListener() {
            @Override
            public void onDeck(DeckState deck) {
                engine.onDeck(deck);
                recorder.onDeck(deck);
            }

            @Override
            public void onBeat(BeatEvent beat) {
                engine.onBeat(beat);
                recorder.onBeat(beat);
            }

            @Override
            public void onStructure(int player, TrackStructure structure) {
                engine.onStructure(player, structure);
                recorder.onStructure(player, structure);
            }
        };
        source.start(musicListener);
        web.start(cfg.webHost, webPort);
    }

    /** Replace the music source while running (live CDJs / simulator / replay). */
    public synchronized void switchSource(MusicSource newSource) throws Exception {
        source.stop();
        engine.clearDecks("Source: " + newSource.name());
        source = newSource;
        source.start(musicListener);
    }

    private synchronized void updateAudio() {
        Config.AudioConfig a = config.get().audio;
        String key = a.enabled + "|" + a.device + "|" + a.sampleRate;
        if (Objects.equals(key, audioKey)) return;
        audioKey = key;
        if (audio != null) audio.stop();
        audio = null;
        if (a.enabled) {
            AudioAnalyzer analyzer = new AudioAnalyzer(a.device, a.sampleRate);
            analyzer.start();
            audio = analyzer;
        }
    }

    public void stop() {
        web.stop();
        source.stop();
        recorder.stop();
        if (audio != null) audio.stop();
        hub.stop();
        scheduler.shutdown();
    }

    /** Folder holding config.json, configs/, recordings/ and analysis/. */
    public static Path dataDir(ConfigStore store) {
        return store.path().toAbsolutePath().getParent();
    }

    public static MusicSource liveSource(ConfigStore store) {
        return new automa3.music.ProDjLinkSource(store, dataDir(store).resolve("analysis"));
    }

    public static MusicSource simulator(ConfigStore store, double speed) {
        return new automa3.music.SimulatedSource(speed, store.get().engine.dropBars * 4);
    }

    public static MusicSource replay(ConfigStore store, Path file, double speed) {
        return new automa3.music.ReplaySource(file, speed, store.get().engine.dropBars * 4);
    }

    public int webPort() {
        return web.port();
    }

    public ConfigStore config() {
        return config;
    }

    public ConfigLibrary library() {
        return library;
    }

    public ConsoleHub hub() {
        return hub;
    }

    public ShowEngine engine() {
        return engine;
    }

    public MusicSource source() {
        return source;
    }

    public SessionRecorder recorder() {
        return recorder;
    }

    public AudioAnalyzer audio() {
        return audio;
    }
}
