package automa3.desktop;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of the Mac app: one window, backend inside the same process.
 *
 * <p>Kept free of other app classes so logging can be pointed at a file before any logger exists
 * (a double-clicked app has no terminal). Not a JavaFX Application subclass, so the fat jar can start
 * JavaFX from the class path.</p>
 */
public final class DesktopMain {

    private DesktopMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = dataDir();
        Path logs = Files.createDirectories(dataDir.resolve("logs"));
        Path log = logs.resolve("automa3.log");
        // keep the previous run's log, e.g. to see why a start failed
        if (Files.exists(log)) Files.move(log, logs.resolve("automa3.previous.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.setProperty("org.slf4j.simpleLogger.logFile", log.toString());
        System.setProperty("automa3.dataDir", dataDir.toString());
        // beat-link uses a few AWT classes; keep AWT headless so it never starts its GUI next to JavaFX
        System.setProperty("java.awt.headless", "true");
        javafx.application.Application.launch(DesktopApp.class, args);
    }

    /** ~/Library/Application Support/AutoMA3, or -Dautoma3.dataDir=... */
    static Path dataDir() {
        String override = System.getProperty("automa3.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "AutoMA3");
    }
}
