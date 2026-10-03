package automa3.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Named setups (e.g. one per venue or rented console) stored as JSON files in a folder next to
 * the working config. The working config ({@link ConfigStore}) is what the app runs; saving copies
 * it into the library, loading copies a library entry over it. The name of the last saved or loaded
 * setup is remembered, so the app starts with it and the UI can show unsaved changes.
 */
public class ConfigLibrary {

    public record Entry(String name, long modifiedMs) {
    }

    public record Status(String active, boolean modified, List<Entry> setups) {
    }

    private static final String ACTIVE_FILE = ".active";

    private final ConfigStore store;
    private final Path dir;

    public ConfigLibrary(ConfigStore store, Path dir) throws IOException {
        this.store = store;
        this.dir = dir;
        Files.createDirectories(dir);
    }

    /** Allowed setup names: letters, digits, space, dot, dash, underscore; 1-60 characters. */
    public static String cleanName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("[^\\p{L}\\p{N} ._-]", "").replaceAll("\\s+", " ");
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 60) n = n.substring(0, 60).trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Invalid setup name");
        return n;
    }

    private Path file(String name) {
        return dir.resolve(cleanName(name) + ".json");
    }

    public synchronized Status status() throws IOException {
        List<Entry> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String fn = p.getFileName().toString();
                list.add(new Entry(fn.substring(0, fn.length() - 5), Files.getLastModifiedTime(p).toMillis()));
            }
        }
        list.sort(Comparator.comparing(e -> e.name().toLowerCase()));
        String active = active();
        boolean modified = false;
        if (active != null) {
            Path f = file(active);
            if (Files.exists(f)) {
                JsonNode saved = ConfigStore.JSON.valueToTree(ConfigStore.normalize(read(f)));
                JsonNode current = ConfigStore.JSON.valueToTree(store.get());
                modified = !saved.equals(current);
            } else {
                active = null;
            }
        }
        return new Status(active, modified, list);
    }

    /** Save the running config under a name (overwrites a setup with the same name). */
    public synchronized String save(String name) throws IOException {
        String clean = cleanName(name);
        write(file(clean), ConfigStore.JSON.writeValueAsBytes(store.get()));
        setActive(clean);
        return clean;
    }

    /** Make a saved setup the running config. */
    public synchronized void load(String name) throws IOException {
        Path f = file(name);
        if (!Files.exists(f)) throw new IllegalArgumentException("No setup named " + name);
        store.replace(read(f));
        setActive(cleanName(name));
    }

    public synchronized void delete(String name) throws IOException {
        Files.deleteIfExists(file(name));
        if (cleanName(name).equals(active())) Files.deleteIfExists(dir.resolve(ACTIVE_FILE));
    }

    /** Raw JSON of a setup for download. */
    public synchronized byte[] export(String name) throws IOException {
        Path f = file(name);
        if (!Files.exists(f)) throw new IllegalArgumentException("No setup named " + name);
        return Files.readAllBytes(f);
    }

    /** Store an uploaded setup file in the library (validated, not loaded). */
    public synchronized String importSetup(String name, byte[] json) throws IOException {
        Config parsed;
        try {
            parsed = ConfigStore.normalize(ConfigStore.JSON.readValue(json, Config.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a valid AutoMA3 setup file: " + e.getMessage());
        }
        String clean = cleanName(name);
        write(file(clean), ConfigStore.JSON.writeValueAsBytes(parsed));
        return clean;
    }

    private Config read(Path f) throws IOException {
        return ConfigStore.JSON.readValue(f.toFile(), Config.class);
    }

    private String active() throws IOException {
        Path p = dir.resolve(ACTIVE_FILE);
        if (!Files.exists(p)) return null;
        String s = Files.readString(p, StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }

    private void setActive(String name) throws IOException {
        write(dir.resolve(ACTIVE_FILE), name.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
