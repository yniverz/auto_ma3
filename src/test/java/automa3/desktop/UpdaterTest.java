package automa3.desktop;

import automa3.Version;
import automa3.config.ConfigStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {

    @Test
    void versionComparison() {
        assertTrue(Version.compare("1.0.12", "1.0.9") > 0, "numeric, not text");
        assertTrue(Version.compare("v1.1.0", "1.0.99") > 0);
        assertEquals(0, Version.compare("1.0", "1.0.0"));
        assertTrue(Version.compare("1.0.1", "dev") > 0, "dev is older than any release");
    }

    @Test
    void parsesGitHubReleaseAndPicksTheMacZip() throws Exception {
        String json = """
                {"tag_name": "v1.0.12", "draft": false, "body": "Fixes", "html_url": "https://github.com/yniverz/auto_ma3/releases/tag/v1.0.12",
                 "assets": [
                   {"name": "AutoMA3-1.0.12.dmg", "browser_download_url": "https://x/dmg", "size": 100},
                   {"name": "AutoMA3-1.0.12-mac-arm64.zip", "browser_download_url": "https://x/zip", "size": 200}]}""";
        Updater.Release r = Updater.parse(ConfigStore.JSON.readTree(json)).orElseThrow();
        assertEquals("1.0.12", r.version());
        assertEquals("https://x/zip", r.zipUrl());
        assertEquals(200, r.zipSize());
        assertTrue(Updater.parse(ConfigStore.JSON.readTree("{\"tag_name\": \"v2\", \"assets\": []}")).isEmpty(),
                "no app for this Mac in the release");
    }

    @Test
    void helperSwapsTheAppAfterTheProcessQuits() throws Exception {
        Path dir = Files.createTempDirectory("automa3-updater-test");
        Path oldApp = Files.createDirectories(dir.resolve("Apps/AutoMA3.app/Contents"));
        Files.writeString(oldApp.resolve("version"), "old");
        Path newApp = Files.createDirectories(dir.resolve("new/AutoMA3.app/Contents"));
        Files.writeString(newApp.resolve("version"), "new");
        Path app = dir.resolve("Apps/AutoMA3.app");

        Process running = new ProcessBuilder("sleep", "1").start(); // stands in for the running app
        Path script = Updater.writeHelper(dir);
        Updater.startHelper(script, running.pid(), app, dir.resolve("new/AutoMA3.app"), dir.resolve("update.log"), false);

        Thread.sleep(300);
        assertEquals("old", Files.readString(app.resolve("Contents/version")), "waits while the app still runs");
        assertTrue(running.waitFor(5, TimeUnit.SECONDS));
        Path version = app.resolve("Contents/version");
        // during the swap the old app is moved away briefly: wait for the new one to be complete
        Path backup = dir.resolve("Apps/AutoMA3.app.update-backup");
        for (int i = 0; i < 50 && !(Files.exists(version) && Files.readString(version).equals("new") && !Files.exists(backup)); i++) {
            Thread.sleep(100);
        }
        assertEquals("new", Files.readString(app.resolve("Contents/version")), "swapped after the app quit");
        assertFalse(Files.exists(backup), "backup removed");
    }

    @Test
    void helperRestoresTheOldAppWhenTheNewOneIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("automa3-updater-test");
        Path oldApp = Files.createDirectories(dir.resolve("Apps/AutoMA3.app/Contents"));
        Files.writeString(oldApp.resolve("version"), "old");
        Path app = dir.resolve("Apps/AutoMA3.app");
        Process done = new ProcessBuilder("true").start();
        done.waitFor();
        Updater.startHelper(Updater.writeHelper(dir), done.pid(), app, dir.resolve("missing/AutoMA3.app"), dir.resolve("update.log"), false);
        for (int i = 0; i < 50 && !Files.exists(dir.resolve("update.log")); i++) Thread.sleep(100);
        Thread.sleep(500);
        assertEquals("old", Files.readString(app.resolve("Contents/version")), "old version restored");
    }
}
