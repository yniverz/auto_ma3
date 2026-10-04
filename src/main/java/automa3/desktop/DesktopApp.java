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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ProgressBar;
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
import javafx.scene.layout.VBox;
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
    private final Updater updater = new Updater();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("AutoMA3 " + automa3.Version.current());
        Label loading = new Label("Starting AutoMA3…");
        loading.setStyle("-fx-text-fill: #8b95a3; -fx-font-size: 16px;");
        StackPane splash = new StackPane(loading);
        splash.setAlignment(Pos.CENTER);
        splash.setStyle("-fx-background-color: #0d0f12;");
        stage.setScene(new Scene(splash, 1400, 900));
        stage.show();

        log.info("AutoMA3 {} starting ({})", automa3.Version.current(),
                Updater.appBundle() == null ? "not an installed app" : "app: " + Updater.appBundle());
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
        if (Updater.appBundle() != null) checkForUpdates(false); // only the installed app updates itself
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
        MenuItem updates = new MenuItem("Check for Updates…");
        updates.setOnAction(e -> checkForUpdates(true));
        Menu view = new Menu("View", null, reload, new SeparatorMenuItem(), browser, data, logItem,
                new SeparatorMenuItem(), updates);

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

    // ------------------------------------------------------------------ updates

    /** Look for a newer release on GitHub; {@code manual} also reports "up to date" and errors. */
    private void checkForUpdates(boolean manual) {
        Thread t = new Thread(() -> {
            try {
                var latest = updater.latest();
                Platform.runLater(() -> {
                    if (latest.isPresent() && Updater.isNewer(latest.get())) {
                        offerUpdate(latest.get());
                    } else if (manual) {
                        info(Updater.appBundle() == null
                                ? "This is version " + automa3.Version.current() + " started from the project, not the installed app. "
                                + "Updates are installed by the app itself (Build: Mac app)."
                                : "AutoMA3 " + automa3.Version.current() + " is the latest version.");
                    }
                });
            } catch (Exception e) {
                log.info("Update check failed: {}", e.toString());
                if (manual) Platform.runLater(() -> error("Could not check for updates", e.getMessage()));
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void offerUpdate(Updater.Release r) {
        Path app = Updater.appBundle();
        boolean canInstall = Updater.appBundle() != null && Updater.canReplace(app);
        String notes = r.notes() == null ? "" : r.notes().strip();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        ButtonType update = new ButtonType(canInstall ? "Update and restart" : "Open download page", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", update, later);
        a.initOwner(stage);
        a.setTitle("Update available");
        a.setHeaderText("AutoMA3 " + r.version() + " is available (you have " + automa3.Version.current() + ")");
        a.setContentText((notes.isEmpty() ? "" : notes + "\n\n") + (canInstall
                ? "The app downloads the new version, quits and starts again. The show stops for a moment, so do it between sets."
                : "This copy cannot replace itself (e.g. it runs from the disk image). Download the new version and copy it to Applications."));
        if (a.showAndWait().orElse(later) != update) return;
        if (!canInstall) {
            open(URI.create(r.pageUrl()));
            return;
        }
        installUpdate(r, app);
    }

    private void installUpdate(Updater.Release r, Path app) {
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(320);
        Label label = new Label("Downloading AutoMA3 " + r.version() + "…");
        VBox box = new VBox(12, label, bar);
        box.setStyle("-fx-padding: 20; -fx-background-color: #191919;");
        label.setStyle("-fx-text-fill: #ededed;");
        Stage progress = new Stage();
        progress.initOwner(stage);
        progress.initModality(javafx.stage.Modality.WINDOW_MODAL);
        progress.setTitle("Updating AutoMA3");
        progress.setScene(new Scene(box));
        progress.setOnCloseRequest(e -> e.consume());
        progress.show();
        Thread t = new Thread(() -> {
            try {
                updater.prepareInstall(r, app, DesktopMain.dataDir().resolve("logs").resolve("update.log"),
                        f -> Platform.runLater(() -> bar.setProgress(f)));
                Platform.runLater(() -> {
                    label.setText("Restarting…");
                    Platform.exit(); // stop() shuts the backend down; the helper then swaps the app and restarts it
                });
            } catch (Exception e) {
                log.warn("Update failed: {}", e.toString());
                Platform.runLater(() -> {
                    progress.close();
                    error("Update failed", e.getMessage() + "\n\nThe current version keeps running. You can also download the "
                            + "new version from " + r.pageUrl());
                });
            }
        }, "update");
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


    }
}
