package automa3.desktop;

import automa3.App;
import automa3.config.ConfigStore;
import automa3.music.MusicSource;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The Mac window: shows the web UI in a WebView and owns the backend. Closing the window (or Cmd+Q)
 * stops everything; opening the app starts everything.
 */
public class DesktopApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(DesktopApp.class);

    private App app;
    private WebView webView;
    private String url;
    private Stage stage;
    private final ToggleGroup sourceGroup = new ToggleGroup();
    /** Called from the page's JavaScript; must stay strongly referenced. */
    private final Bridge bridge = new Bridge();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("AutoMA3");
        Label loading = new Label("Starting AutoMA3…");
        loading.setStyle("-fx-text-fill: #8b95a3; -fx-font-size: 16px;");
        StackPane splash = new StackPane(loading);
        splash.setAlignment(Pos.CENTER);
        splash.setStyle("-fx-background-color: #0d0f12;");
        stage.setScene(new Scene(splash, 1400, 900));
        stage.show();

        Thread starter = new Thread(() -> {
            try {
                Path dataDir = DesktopMain.dataDir();
                ConfigStore store = new ConfigStore(dataDir.resolve("config.json"));
                app = new App(store, App.liveSource(store), dataDir.resolve("recordings"));
                app.start(store.get().webPort);
                url = "http://127.0.0.1:" + app.webPort() + "/";
                Platform.runLater(this::showWebUi);
            } catch (Exception e) {
                log.error("Startup failed", e);
                Platform.runLater(() -> {
                    error("AutoMA3 could not start", e.getMessage() + "\n\nLog: " + logFile());
                    Platform.exit();
                });
            }
        }, "startup");
        starter.setDaemon(true);
        starter.start();
    }

    private void showWebUi() {
        webView = new WebView();
        WebEngine engine = webView.getEngine();
        engine.setConfirmHandler(message -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            a.initOwner(stage);
            a.setHeaderText(null);
            return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        });
        engine.setOnAlert(e -> info(e.getData()));
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                ((JSObject) engine.executeScript("window")).setMember("automa3Desktop", bridge);
            } else if (state == Worker.State.FAILED) {
                log.warn("Page failed to load: {}", engine.getLoadWorker().getException());
            }
        });
        engine.load(url);

        BorderPane root = new BorderPane(webView);
        MenuBar menu = buildMenu();
        menu.setUseSystemMenuBar(true);
        root.setTop(menu);
        stage.setScene(new Scene(root, stage.getScene().getWidth(), stage.getScene().getHeight()));
    }

    private MenuBar buildMenu() {
        MenuItem reload = new MenuItem("Reload");
        reload.setAccelerator(KeyCombination.keyCombination("Shortcut+R"));
        reload.setOnAction(e -> reload());
        MenuItem browser = new MenuItem("Open in Browser");
        browser.setOnAction(e -> open(URI.create(url)));
        MenuItem data = new MenuItem("Open Data Folder");
        data.setOnAction(e -> open(DesktopMain.dataDir().toFile()));
        MenuItem logItem = new MenuItem("Open Log");
        logItem.setOnAction(e -> open(logFile().toFile()));
        Menu view = new Menu("View", null, reload, new SeparatorMenuItem(), browser, data, logItem);

        Menu source = new Menu("Source");
        RadioMenuItem live = sourceItem("Live CDJs (Pro DJ Link)", () -> App.liveSource(app.config()));
        RadioMenuItem sim = sourceItem("Simulator", () -> App.simulator(app.config(), 1));
        RadioMenuItem simFast = sourceItem("Simulator (8× fast)", () -> App.simulator(app.config(), 8));
        live.setSelected(true);
        MenuItem replay = new MenuItem("Replay Recording…");
        replay.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Replay a recorded session");
            File dir = DesktopMain.dataDir().resolve("recordings").toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recordings", "*.jsonl"));
            File f = fc.showOpenDialog(stage);
            if (f == null) return;
            sourceGroup.selectToggle(null);
            switchSource(() -> App.replay(app.config(), f.toPath(), 1));
        });
        source.getItems().addAll(live, sim, simFast, new SeparatorMenuItem(), replay);
        return new MenuBar(view, source);
    }

    private RadioMenuItem sourceItem(String label, Supplier<MusicSource> factory) {
        RadioMenuItem item = new RadioMenuItem(label);
        item.setToggleGroup(sourceGroup);
        item.setOnAction(e -> switchSource(factory));
        return item;
    }

    private void switchSource(Supplier<MusicSource> factory) {
        Thread t = new Thread(() -> {
            try {
                app.switchSource(factory.get());
            } catch (Exception ex) {
                log.error("Switching source failed", ex);
                Platform.runLater(() -> error("Switching source failed", ex.getMessage()));
            }
        }, "switch-source");
        t.setDaemon(true);
        t.start();
    }

    private void reload() {
        if (webView != null) webView.getEngine().load(url);
    }

    private void open(Object target) {
        String uri = target instanceof File f ? f.toURI().toString() : target.toString();
        getHostServices().showDocument(uri);
    }

    private static Path logFile() {
        return DesktopMain.dataDir().resolve("logs").resolve("automa3.log");
    }

    private void info(String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void error(String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        a.setHeaderText(title);
        a.showAndWait();
    }

    @Override
    public void stop() {
        log.info("Window closed, stopping");
        try {
            if (app != null) app.stop();
        } finally {
            System.exit(0);
        }
    }

    /** Methods the web page can call (window.automa3Desktop) when it runs inside the app. */
    public class Bridge {

        /** Save a file the page offers for download (WebView has no download support). */
        public void download(String path, String suggestedName) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save");
            fc.setInitialFileName(suggestedName);
            File target = fc.showSaveDialog(stage);
            if (target == null) return;
            Thread t = new Thread(() -> {
                try {
                    HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(url).resolve(path)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    Files.write(target.toPath(), r.body());
                } catch (Exception ex) {
                    log.warn("Download failed: {}", ex.toString());
                    Platform.runLater(() -> error("Saving failed", ex.getMessage()));
                }
            }, "download");
            t.setDaemon(true);
            t.start();
        }

        public void reload() {
            Platform.runLater(DesktopApp.this::reload);
        }

        public void log(String message) {
            log.info("page: {}", message);
        }
    }
}
