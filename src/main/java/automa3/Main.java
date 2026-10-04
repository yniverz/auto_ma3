package automa3;

import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.music.MusicSource;

import java.nio.file.Path;

/**
 * Entry point.
 *
 * <pre>
 *   java -jar auto-ma3.jar                       live: CDJs over Pro DJ Link
 *   java -jar auto-ma3.jar --sim [--speed 4]     simulated DJ set
 *   java -jar auto-ma3.jar --replay FILE         replay a recorded session
 *   options: --config FILE  --dry-run  --record  --port N
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config.json");
        boolean sim = false, dryRun = false, record = false;
        Path replay = null, analyze = null;
        double speed = 1;
        Integer port = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--sim" -> sim = true;
                case "--replay" -> replay = Path.of(args[++i]);
                case "--analyze" -> analyze = Path.of(args[++i]);
                case "--speed" -> speed = Double.parseDouble(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--record" -> record = true;
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("""
                            AutoMA3 - automatic grandMA3 lighting from Pioneer CDJs

                              (no args)          live: CDJs over Pro DJ Link (Ethernet)
                              --sim              simulated DJ set (no CDJs needed)
                              --replay FILE      replay a recorded session (recordings/*.jsonl)
                              --analyze FILE     print the waveform analysis of a track (analysis/*.json)
                              --speed X          playback speed for --sim / --replay
                              --config FILE      config file (default ./config.json)
                              --dry-run          do not send anything to consoles
                              --record           record the session from the start
                              --port N           web UI port (default from config, 8081)
                            """);
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + args[i] + " (try --help)");
                    System.exit(2);
                }
            }
        }

        if (analyze != null) {
            automa3.music.AnalysisDump.printAnalysis(analyze, 16 * 4);
            return;
        }

        ConfigStore store = new ConfigStore(configPath);
        Config cfg = store.get();
        int webPort = port != null ? port : cfg.webPort;
        MusicSource source;
        if (replay != null) source = App.replay(store, replay, speed);
        else if (sim) source = App.simulator(store, speed);
        else source = App.liveSource(store);

        Path recordings = App.dataDir(store).resolve("recordings");
        App app = new App(store, source, recordings);
        app.hub().setDryRun(dryRun);
        app.start(webPort);
        if (record) app.recorder().start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));

        String host = cfg.webHost.equals("0.0.0.0") ? "localhost" : cfg.webHost;
        System.out.println("AutoMA3 " + automa3.Version.current() + " running (" + source.name() + (dryRun ? ", DRY RUN" : "") + ")");
        System.out.println("Open http://" + host + ":" + app.webPort() + "/   (Ctrl+C to quit)");
        Thread.currentThread().join();
    }
}
