package automa3.show;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON reading and writing for show files, and the show creator's bundled resources. */
public final class ShowFiles {

    public static final ObjectMapper JSON = new ObjectMapper();

    private ShowFiles() {
    }

    public static ObjectNode object() {
        return JSON.createObjectNode();
    }

    public static ArrayNode array() {
        return JSON.createArrayNode();
    }

    /** A bundled file under src/main/resources/showcreator/, parsed as JSON. */
    static JsonNode resource(String path) {
        try {
            return JSON.readTree(resourceText(path));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad bundled file " + path, e);
        }
    }

    static String resourceText(String path) {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    static byte[] resourceBytes(String path) {
        try (InputStream in = ShowFiles.class.getResourceAsStream("/showcreator/" + path)) {
            if (in == null) throw new IllegalStateException("missing bundled file " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode read(Path file, String what) throws ShowException {
        if (!Files.exists(file)) throw new ShowException(what + " not found: " + file);
        try {
            return JSON.readTree(Files.readString(file));
        } catch (JsonProcessingException e) {
            throw new ShowException(what + " is not valid JSON (" + file + "): " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ShowException("cannot read " + what + " (" + file + "): " + e.getMessage());
        }
    }

    /** Pretty JSON with {@code indent} spaces per level and a final newline, as the Python tool wrote it. */
    public static String pretty(JsonNode node, int indent) {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter(" ".repeat(indent), "\n"))
                .withArrayIndenter(new DefaultIndenter(" ".repeat(indent), "\n"));
        try {
            return JSON.writer(pp).without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path write(Path file, String text) throws ShowException {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return file;
        } catch (IOException e) {
            throw new ShowException("cannot write " + file + ": " + e.getMessage());
        }
    }

    /** Sorted-key compact form, for comparing values (Python json.dumps(sort_keys=True)). */
    static String canonical(JsonNode v) {
        try {
            return JSON.writeValueAsString(sorted(v));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sorted(JsonNode v) {
        if (v.isObject()) {
            Map<String, Object> m = new TreeMap<>();
            v.fields().forEachRemaining(e -> m.put(e.getKey(), sorted(e.getValue())));
            return m;
        }
        if (v.isArray()) {
            List<Object> l = new ArrayList<>();
            v.forEach(x -> l.add(sorted(x)));
            return l;
        }
        return v;
    }

    /** Field names of an object in their order. */
    public static List<String> keys(JsonNode obj) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = obj.fieldNames();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) array.forEach(x -> out.add(x.asText()));
        return out;
    }

    static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        if (array != null) array.forEach(x -> out.add(x.asInt()));
        return out;
    }
}
