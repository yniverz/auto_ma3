package automa3;

import automa3.config.ConfigStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Looks on GitHub Releases for a newer AutoMA3 and tells the terminal where to get it. Never blocks the start and
 * stays quiet without internet (club networks often have none).
 */
final class UpdateCheck {

    static final String LATEST_URL = "https://api.github.com/repos/yniverz/auto_ma3/releases/latest";

    record Release(String version, String pageUrl) {
    }

    private UpdateCheck() {
    }

    /** Check in the background; prints a notice when a newer release exists. */
    static void startInBackground() {
        if ("dev".equals(Version.current())) return; // built from the code: nothing to compare with
        Thread t = new Thread(() -> {
            try {
                latest().filter(UpdateCheck::isNewer).ifPresent(r -> System.out.println(
                        "\nAutoMA3 " + r.version() + " is available (this is " + Version.current() + "): " + r.pageUrl() + "\n"));
            } catch (Exception e) {
                // offline or GitHub unreachable: no notice
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    static Optional<Release> latest() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(System.getProperty("automa3.updateUrl", LATEST_URL)))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AutoMA3/" + Version.current())
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return Optional.empty();
        return parse(ConfigStore.JSON.readTree(r.body()));
    }

    static Optional<Release> parse(JsonNode json) {
        String tag = json.path("tag_name").asText("");
        if (tag.isEmpty() || json.path("draft").asBoolean(false)) return Optional.empty();
        return Optional.of(new Release(tag.startsWith("v") ? tag.substring(1) : tag, json.path("html_url").asText("")));
    }

    static boolean isNewer(Release r) {
        return !"dev".equals(Version.current()) && Version.compare(r.version(), Version.current()) > 0;
    }
}
