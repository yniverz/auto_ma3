package automa3.web;

import automa3.App;
import automa3.audio.AudioAnalyzer;
import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.ma3.Ma3Profile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local web UI and JSON API (JDK built-in HTTP server, no extra dependencies).
 */
public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final App app;
    private HttpServer server;

    public WebServer(App app) {
        this.app = app;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // cached pool: each open live stream (SSE) holds one thread
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "web");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        log.info("Web UI: http://{}:{}/", host.equals("0.0.0.0") ? "localhost" : host, port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            if (path.equals("/") || path.equals("/index.html")) {
                staticFile(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/api/stream")) {
                stream(ex);
            } else if (path.equals("/api/state")) {
                json(ex, 200, state());
            } else if (path.equals("/api/config") && method.equals("GET")) {
                json(ex, 200, app.config().get());
            } else if (path.equals("/api/config") && method.equals("PUT")) {
                Config c = ConfigStore.JSON.readValue(ex.getRequestBody(), Config.class);
                app.config().replace(c);
                json(ex, 200, app.config().get());
            } else if (path.equals("/api/profiles")) {
                json(ex, 200, profiles());
            } else if (path.equals("/api/dryrun") && method.equals("POST")) {
                app.hub().setDryRun(Boolean.parseBoolean(query.getOrDefault("value", "true")));
                json(ex, 200, Map.of("dryRun", app.hub().isDryRun()));
            } else if (path.startsWith("/api/control/") && method.equals("POST")) {
                String action = path.substring("/api/control/".length());
                Double value = query.containsKey("value") ? Double.valueOf(query.get("value")) : null;
                app.engine().control(action, value, "web");
                json(ex, 200, Map.of("ok", true));
            } else if (path.startsWith("/api/test/") && method.equals("POST")) {
                app.engine().testLook(path.substring("/api/test/".length()));
                json(ex, 200, Map.of("ok", true));
            } else if (path.equals("/api/record/start") && method.equals("POST")) {
                app.recorder().start();
                json(ex, 200, Map.of("ok", true, "file", String.valueOf(app.recorder().file())));
            } else if (path.equals("/api/record/stop") && method.equals("POST")) {
                app.recorder().stop();
                json(ex, 200, Map.of("ok", true));
            } else if (path.equals("/api/audio/devices")) {
                json(ex, 200, AudioAnalyzer.inputDevices());
            } else {
                json(ex, 404, Map.of("error", "not found"));
            }
        } catch (Exception e) {
            log.warn("Web request failed: {}", e.toString());
            json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Live state as Server-Sent Events: one open connection, pushed whenever the state changes
     * (checked every 40 ms), with a keep-alive comment when nothing changes.
     */
    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            String last = null;
            long lastWrite = 0;
            while (true) {
                String json = ConfigStore.JSON.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(state());
                long now = System.currentTimeMillis();
                if (!json.equals(last)) {
                    os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    last = json;
                    lastWrite = now;
                } else if (now - lastWrite > 15_000) {
                    os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    lastWrite = now;
                }
                Thread.sleep(40);
            }
        } catch (IOException | InterruptedException e) {
            // browser closed the page
        }
    }

    private Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("engine", app.engine().snapshot());
        s.put("source", Map.of("name", app.source().name(), "status", app.source().status()));
        List<Map<String, Object>> consoles = new ArrayList<>();
        for (Config.ConsoleConfig c : app.config().get().consoles) {
            Ma3Profile p = Ma3Profile.forVersion(c.version);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("enabled", c.enabled);
            m.put("target", c.host + ":" + c.port);
            m.put("profile", p.label);
            m.put("verified", p.verified);
            m.put("feedback", p.playbackFeedback);
            m.put("speedMasterOk", p.speedMasterUsable(app.config().get().speed.master));
            consoles.add(m);
        }
        s.put("consoles", consoles);
        List<?> log = app.hub().recent();
        s.put("commands", log.subList(Math.max(0, log.size() - 80), log.size()));
        long last = app.hub().lastReceiveMs();
        s.put("oscInAgeSec", last == 0 ? -1 : (System.currentTimeMillis() - last) / 1000);
        s.put("sendError", app.hub().lastSendError());
        s.put("dryRun", app.hub().isDryRun());
        s.put("recording", app.recorder().isRecording() ? String.valueOf(app.recorder().file()) : null);
        s.put("audioError", app.audio() == null ? null : app.audio().error());
        s.put("configPath", app.config().path().toAbsolutePath().toString());
        return s;
    }

    private List<Map<String, Object>> profiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Ma3Profile p : Ma3Profile.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("label", p.label);
            m.put("verified", p.verified);
            m.put("playbackFeedback", p.playbackFeedback);
            m.put("speedMasterCount", p.speedMasterCount);
            m.put("reservedSpeedMasters", p.reservedSpeedMasters);
            m.put("templates", p.templates);
            m.put("notes", p.notes);
            out.add(m);
        }
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private void staticFile(HttpExchange ex, String resource, String type) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                json(ex, 404, Map.of("error", "missing " + resource));
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void json(HttpExchange ex, int code, Object value) throws IOException {
        byte[] body = ConfigStore.JSON.writeValueAsBytes(value);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
