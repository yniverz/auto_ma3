package automa3;

import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.music.MusicSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Entry point. Runs in a terminal; the UI is the web page it serves.
 *
 * <pre>
 *   java -jar auto-ma3.jar                       live: CDJs over Pro DJ Link
 *   java -jar auto-ma3.jar --sim [--speed 4]     simulated DJ set
 *   java -jar auto-ma3.jar --replay FILE         replay a recorded session
 *   options: --config FILE  --dry-run  --record  --port N  --open
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = defaultDataDir().resolve("config.json");
        boolean sim = false, dryRun = false, record = false, open = false;
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
                case "--open" -> open = true;
                case "--help", "-h" -> {
                    System.out.println("""
                            AutoMA3 - automatic grandMA3 lighting from Pioneer CDJs

                              (no args)          live: CDJs over Pro DJ Link (Ethernet)
                              --sim              simulated DJ set (no CDJs needed)
                              --replay FILE      replay a recorded session (recordings/*.jsonl)
                              --analyze FILE     print the waveform analysis of a track (analysis/*.json)
                              --speed X          playback speed for --sim / --replay
                              --config FILE      config file; its folder also holds setups, recordings,
                                                 analyses and logs (default %s)
                              --dry-run          do not send anything to consoles
                              --record           record the session from the start
                              --port N           web UI port (default from config, 8081)
                              --open             open the web UI in the browser once it runs
                            """.formatted(defaultDataDir().resolve("config.json")));
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

        // beat-link uses a few AWT classes: keep AWT from starting a GUI (Dock icon) next to the terminal
        System.setProperty("java.awt.headless", "true");
        Path log = startLogFile(configPath.toAbsolutePath().getParent());

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
        String url = "http://" + host + ":" + app.webPort() + "/";
        System.out.println();
        System.out.println("AutoMA3 " + automa3.Version.current() + " running (" + source.name() + (dryRun ? ", DRY RUN" : "") + ")");
        System.out.println();
        System.out.println("  Open in the browser:  " + url + "   (Cmd-click the link)");
        System.out.println();
        System.out.println("  Data folder: " + App.dataDir(store));
        if (log != null) System.out.println("  Log:         " + log);
        System.out.println("  Quit with Ctrl+C (or close this window).");
        System.out.println();
        if (open) openInBrowser(url);
        UpdateCheck.startInBackground();
        Thread.currentThread().join();
    }

    /** ~/Library/Application Support/AutoMA3 on a Mac, else ~/.automa3. */
    static Path defaultDataDir() {
        Path home = Path.of(System.getProperty("user.home"));
        return System.getProperty("os.name", "").toLowerCase().contains("mac")
                ? home.resolve("Library").resolve("Application Support").resolve("AutoMA3")
                : home.resolve(".automa3");
    }

    /**
     * Everything printed to the terminal also goes to logs/automa3.log in the data folder (the previous run's log is
     * kept as automa3.previous.log). Must run before the first logger is created.
     */
    private static Path startLogFile(Path dataDir) {
        try {
            Path logs = Files.createDirectories(dataDir.resolve("logs"));
            Path log = logs.resolve("automa3.log");
            if (Files.exists(log)) Files.move(log, logs.resolve("automa3.previous.log"), StandardCopyOption.REPLACE_EXISTING);
            OutputStream file = Files.newOutputStream(log);
            System.setOut(new PrintStream(new Tee(System.out, file), true));
            System.setErr(new PrintStream(new Tee(System.err, file), true));
            return log;
        } catch (IOException e) {
            System.err.println("No log file (" + e.getMessage() + "), logging to the terminal only");
            return null;
        }
    }

    /** Writes to the terminal and the log file; the file is shared by stdout and stderr. */
    private static final class Tee extends OutputStream {
        private final OutputStream terminal, file;

        Tee(OutputStream terminal, OutputStream file) {
            this.terminal = terminal;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            terminal.write(b);
            synchronized (file) {
                file.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            terminal.write(b, off, len);
            synchronized (file) {
                file.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            terminal.flush();
            synchronized (file) {
                file.flush();
            }
        }
    }

    private static void openInBrowser(String url) {
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) new ProcessBuilder("open", url).start();
        } catch (IOException e) {
            System.out.println("Could not open the browser (" + e.getMessage() + "): open " + url + " yourself.");
        }
    }
}
