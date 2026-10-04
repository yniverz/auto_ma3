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
        Files.createDirectories(dataDir.resolve("logs"));
        System.setProperty("org.slf4j.simpleLogger.logFile", dataDir.resolve("logs").resolve("automa3.log").toString());
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
