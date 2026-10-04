package automa3.config;

import automa3.model.Role;
import automa3.model.Section;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Loads, validates and saves the configuration. Holds the current config; the engine
 * reads {@link #get()} on every decision so edits from the web UI apply live.
 */
public class ConfigStore {

    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path path;
    private volatile Config config;
    private final List<Consumer<Config>> listeners = new CopyOnWriteArrayList<>();

    public ConfigStore(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) {
            Config loaded = JSON.readValue(path.toFile(), Config.class);
            int version = loaded.configVersion;
            config = normalize(loaded);
            if (version < Config.CURRENT_CONFIG_VERSION) save(); // write the migrated file once
        } else {
            config = normalize(defaultConfig());
            save();
        }
    }

    public Config get() {
        return config;
    }

    public Path path() {
        return path;
    }

    public synchronized void replace(Config newConfig) throws IOException {
        config = normalize(newConfig);
        save();
        listeners.forEach(l -> l.accept(config));
    }

    public void onChange(Consumer<Config> listener) {
        listeners.add(listener);
    }

    private void save() throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), config);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Fill in ids and clamp values so the engine never sees invalid data. */
    public static Config normalize(Config c) {
        migrate(c);
        for (Config.Look look : c.looks) {
            if (look.id == null || look.id.isBlank()) look.id = UUID.randomUUID().toString().substring(0, 8);
            if (look.role == null) look.role = Role.BASE;
            if (look.sections == null) look.sections = new java.util.ArrayList<>();
            if (look.description == null) look.description = "";
            if (look.tags == null) look.tags = new java.util.ArrayList<>();
            if (look.meta == null) look.meta = new java.util.LinkedHashMap<>();
            look.energyMin = clamp(look.energyMin, 0, 1);
            look.energyMax = clamp(look.energyMax, 0, 1);
            if (look.energyMax < look.energyMin) look.energyMax = look.energyMin;
            look.weight = Math.max(0.01, look.weight);
            look.level = (int) clamp(look.level, 0, 100);
        }
        c.engine.latencyMs = (int) clamp(c.engine.latencyMs, 0, 500);
        c.engine.rotateBarsGroove = Math.max(4, c.engine.rotateBarsGroove);
        c.engine.rotateBarsPeak = Math.max(4, c.engine.rotateBarsPeak);
        c.engine.dropBars = Math.max(1, c.engine.dropBars);
        c.speed.master = Math.max(1, c.speed.master);
        if (c.speed.multiplier <= 0) c.speed.multiplier = 1;
        return c;
    }

    /**
     * One-time changes for configs written by older versions. Configs without a version are version 1.
     * Version 2: the web UI moved from port 8080 (also used by grandMA3's web interface) to 8081.
     */
    static void migrate(Config c) {
        if (c.configVersion < 2 && c.webPort == 8080) c.webPort = 8081;
        c.configVersion = Config.CURRENT_CONFIG_VERSION;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A minimal working setup: one executor per role on page 1 so a first test runs immediately. */
    public static Config defaultConfig() {
        Config c = new Config();
        Config.ConsoleConfig console = new Config.ConsoleConfig();
        console.name = "onPC (local)";
        c.consoles.add(console);

        int exec = 101;
        c.looks.add(look("Base low", Role.BASE, exec++, List.of(Section.INTRO, Section.BREAKDOWN, Section.OUTRO), 0, 0.5));
        c.looks.add(look("Base full", Role.BASE, exec++, List.of(), 0.4, 1));
        c.looks.add(look("Colour A", Role.COLOR, exec++, List.of(), 0, 1));
        c.looks.add(look("Colour B", Role.COLOR, exec++, List.of(), 0, 1));
        c.looks.add(look("Move slow", Role.MOVEMENT, exec++, List.of(), 0, 0.6));
        c.looks.add(look("Move fast", Role.MOVEMENT, exec++, List.of(), 0.5, 1));
        c.looks.add(look("Dimmer chase", Role.EFFECT, exec++, List.of(), 0.4, 1));
        c.looks.add(look("Riser", Role.RISER, exec++, List.of(), 0, 1));
        c.looks.add(look("Drop hit", Role.ACCENT, exec++, List.of(), 0, 1));
        c.looks.add(look("Strobe", Role.STROBE, exec++, List.of(), 0, 1));
        c.looks.add(look("Blinder", Role.BLINDER, exec++, List.of(), 0, 1));
        c.looks.add(look("Blackout", Role.BLACKOUT, exec++, List.of(), 0, 1));
        c.looks.add(look("Haze", Role.HAZE, exec++, List.of(), 0, 1));
        c.looks.add(look("Fog", Role.FOG, exec++, List.of(), 0, 1));
        return c;
    }

    private static Config.Look look(String name, Role role, int exec, List<Section> sections, double eMin, double eMax) {
        Config.Look l = new Config.Look();
        l.name = name;
        l.role = role;
        l.page = 1;
        l.exec = exec;
        l.sections = new java.util.ArrayList<>(sections);
        l.energyMin = eMin;
        l.energyMax = eMax;
        return l;
    }

    public static Config copy(Config c) {
        return JSON.convertValue(c, Config.class);
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
