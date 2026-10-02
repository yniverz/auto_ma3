package automa3;

import automa3.audio.AudioAnalyzer;
import automa3.config.Config;
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
    private final ConsoleHub hub;
    private final TaskScheduler scheduler = new TaskScheduler.Real();
    private final ShowEngine engine;
    private final MusicSource source;
    private final SessionRecorder recorder;
    private final WebServer web;
    private volatile AudioAnalyzer audio;
    private String audioKey = "";

    public App(ConfigStore config, MusicSource source, Path recordingsDir) throws Exception {
        this.config = config;
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
        source.start(new MusicListener() {
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
        });
        web.start(cfg.webHost, webPort);
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

    public ConfigStore config() {
        return config;
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
