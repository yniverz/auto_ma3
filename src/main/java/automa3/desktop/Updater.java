package automa3.desktop;

import automa3.Version;
import automa3.config.ConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * Updates the Mac app from GitHub Releases: finds the latest release, downloads its app zip, and hands over
 * to a small helper script that swaps the app bundle once this app has quit and then starts the new version.
 */
public class Updater {

    private static final Logger log = LoggerFactory.getLogger(Updater.class);

    static final String LATEST_URL = "https://api.github.com/repos/yniverz/auto_ma3/releases/latest";
    /** Release asset with the app for this kind of Mac. */
    static final String ASSET_SUFFIX = "-mac-arm64.zip";

    public record Release(String version, String notes, String pageUrl, String zipUrl, long zipSize) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The latest release on GitHub, or empty if there is none (or no app for this Mac). */
    public Optional<Release> latest() throws IOException, InterruptedException {
        String url = System.getProperty("automa3.updateUrl", LATEST_URL);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AutoMA3/" + Version.current())
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() != 200) throw new IOException("GitHub answered " + r.statusCode());
        return parse(ConfigStore.JSON.readTree(r.body()));
    }

    static Optional<Release> parse(JsonNode json) {
        String tag = json.path("tag_name").asText("");
        if (tag.isEmpty() || json.path("draft").asBoolean(false)) return Optional.empty();
        for (JsonNode asset : json.path("assets")) {
            String name = asset.path("name").asText("");
            if (name.endsWith(ASSET_SUFFIX)) {
                return Optional.of(new Release(tag.startsWith("v") ? tag.substring(1) : tag,
                        json.path("body").asText(""), json.path("html_url").asText(""),
                        asset.path("browser_download_url").asText(), asset.path("size").asLong(0)));
            }
        }
        return Optional.empty();
    }

    public static boolean isNewer(Release r) {
        return !"dev".equals(Version.current()) && Version.compare(r.version(), Version.current()) > 0;
    }

    /** The running AutoMA3.app, or null when not running as an installed app (e.g. from the IDE). */
    public static Path appBundle() {
        String launcher = System.getProperty("jpackage.app-path"); // .../AutoMA3.app/Contents/MacOS/AutoMA3
        if (launcher == null) return null;
        Path p = Path.of(launcher).toAbsolutePath();
        for (int i = 0; i < 3 && p != null; i++) p = p.getParent();
        return p != null && p.getFileName().toString().endsWith(".app") ? p : null;
    }

    /** False when the app cannot replace itself, e.g. when started from the mounted .dmg. */
    public static boolean canReplace(Path app) {
        return app != null && Files.isWritable(app.getParent()) && Files.isWritable(app);
    }

    /**
     * Download and unpack the release, then start the helper that swaps the app once this process exits.
     * The caller quits the app afterwards.
     */
    public void prepareInstall(Release r, Path app, Path logFile, DoubleConsumer progress) throws Exception {
        prepareInstall(r, app, logFile, progress, true);
    }

    void prepareInstall(Release r, Path app, Path logFile, DoubleConsumer progress, boolean relaunch) throws Exception {
        Path work = Files.createTempDirectory("automa3-update");
        Path zip = work.resolve("AutoMA3.zip");
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(r.zipUrl()))
                .header("User-Agent", "AutoMA3/" + Version.current()).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("Download failed: HTTP " + resp.statusCode());
        long total = r.zipSize() > 0 ? r.zipSize() : resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress.accept(Math.min(1.0, (double) done / total));
            }
        }
        Path unpacked = work.resolve("unpacked");
        run("ditto", "-x", "-k", zip.toString(), unpacked.toString());
        Path newApp;
        try (Stream<Path> s = Files.list(unpacked)) {
            newApp = s.filter(p -> p.getFileName().toString().endsWith(".app")).findFirst()
                    .orElseThrow(() -> new IOException("The download contains no app"));
        }
        Path script = writeHelper(work);
        long pid = ProcessHandle.current().pid();
        log.info("Update {} ready, handing over to {}", r.version(), script);
        startHelper(script, pid, app, newApp, logFile, relaunch);
    }

    /** The helper: waits for the app to quit, swaps the bundle (restoring the old one on failure), restarts. */
    static Path writeHelper(Path dir) throws IOException {
        Path script = dir.resolve("update.sh");
        Files.writeString(script, """
                #!/bin/bash
                # AutoMA3 updater: wait for the app to quit, swap the app bundle, start the new version
                PID="$1"; OLD="$2"; NEW="$3"; RELAUNCH="$4"
                for i in $(seq 1 600); do kill -0 "$PID" 2>/dev/null || break; sleep 0.1; done
                BACKUP="$OLD.update-backup"
                rm -rf "$BACKUP"
                if mv "$OLD" "$BACKUP" && ditto "$NEW" "$OLD"; then
                  rm -rf "$BACKUP"
                  echo "updated $OLD"
                else
                  echo "update failed, restoring the previous version"
                  rm -rf "$OLD"; mv "$BACKUP" "$OLD"
                fi
                xattr -dr com.apple.quarantine "$OLD" 2>/dev/null
                if [ "$RELAUNCH" = "yes" ]; then open "$OLD"; fi
                """);
        script.toFile().setExecutable(true);
        return script;
    }

    /** Start the helper detached, so it keeps running after this app has exited. */
    static Process startHelper(Path script, long pid, Path app, Path newApp, Path logFile, boolean relaunch) throws IOException {
        return new ProcessBuilder("/bin/bash", "-c", "nohup /bin/bash \"$0\" \"$1\" \"$2\" \"$3\" \"$4\" >>\"$5\" 2>&1 &",
                script.toString(), Long.toString(pid), app.toString(), newApp.toString(), relaunch ? "yes" : "no",
                logFile.toString()).start();
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " failed: " + out.trim());
    }
}
