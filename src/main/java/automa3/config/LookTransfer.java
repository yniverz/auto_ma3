package automa3.config;

import automa3.Version;
import automa3.model.Role;
import automa3.model.Section;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Export / import of the look list ("automa3-looks" JSON, documented in docs/looks-format.md), e.g. to exchange
 * looks with a tool that builds the grandMA3 show.
 *
 * <p>Import is lenient and partial: a look only needs its MA3 location (or the id of an existing look); every
 * other field is optional. In "merge" mode, looks matching by id or by page + executor get only the fields
 * present in the file updated.</p>
 */
public final class LookTransfer {

    public static final String FORMAT = "automa3-looks";
    public static final int FORMAT_VERSION = 1;

    public enum Mode { MERGE, ADD, REPLACE }

    /** What happened to one entry of the file. */
    public record Entry(int index, String label, String action, List<String> messages) {
    }

    public record Result(List<Config.Look> looks, List<Entry> entries, ObjectNode context, int added, int updated, int skipped) {
    }

    private LookTransfer() {
    }

    // ------------------------------------------------------------------ export

    public static ObjectNode export(Config config) {
        ObjectNode root = ConfigStore.JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", FORMAT_VERSION);
        root.put("exportedBy", "AutoMA3 " + Version.current());
        root.put("exportedAt", Instant.now().toString());
        root.set("context", context(config));
        ArrayNode looks = root.putArray("looks");
        for (Config.Look l : config.looks) looks.add(exportLook(l));
        return root;
    }

    static ObjectNode exportLook(Config.Look l) {
        ObjectNode o = ConfigStore.JSON.createObjectNode();
        o.put("id", l.id);
        o.put("name", l.name == null ? "" : l.name);
        o.put("description", l.description == null ? "" : l.description);
        o.put("role", l.role.name());
        o.put("layer", l.layerName());
        ObjectNode ma3 = o.putObject("ma3");
        ma3.put("page", l.page);
        ma3.put("exec", l.exec);
        if (l.sequence != null) ma3.put("sequence", l.sequence); else ma3.putNull("sequence");
        o.put("mode", l.modeOrDefault().name());
        ArrayNode sections = o.putArray("sections");
        for (Section s : l.sections) sections.add(s.name());
        ObjectNode energy = o.putObject("energy");
        energy.put("min", l.energyMin);
        energy.put("max", l.energyMax);
        o.put("weight", l.weight);
        o.put("flashBeats", l.flashBeats);
        o.put("level", l.level);
        o.put("enabled", l.enabled);
        ArrayNode tags = o.putArray("tags");
        if (l.tags != null) l.tags.forEach(tags::add);
        o.set("meta", ConfigStore.JSON.valueToTree(l.meta == null ? Map.of() : l.meta));
        return o;
    }

    static ObjectNode context(Config c) {
        ObjectNode ctx = ConfigStore.JSON.createObjectNode();
        ctx.put("speedMaster", c.speed.master);
        ctx.put("controlPrefix", c.oscIn.controlPrefix);
        ctx.put("oscInPort", c.oscIn.port);
        ctx.set("controlSequences", ConfigStore.JSON.valueToTree(c.oscIn.controlSequences));
        return ctx;
    }

    // ------------------------------------------------------------------ import

    /**
     * Merge looks from a file into {@code current}.
     *
     * @param select indexes of the file's looks to import, null = all
     */
    public static Result importLooks(List<Config.Look> current, JsonNode file, Mode mode, Set<Integer> select) {
        if (file == null || !file.isObject()) throw new IllegalArgumentException("Not a JSON object");
        String format = file.path("format").asText(FORMAT);
        if (!FORMAT.equals(format)) throw new IllegalArgumentException("Unknown format \"" + format + "\" (expected " + FORMAT + ")");
        int version = file.path("version").asInt(FORMAT_VERSION);
        JsonNode looksNode = file.path("looks");
        if (!looksNode.isArray()) throw new IllegalArgumentException("No \"looks\" list in the file");

        List<Config.Look> result = new ArrayList<>();
        if (mode != Mode.REPLACE) for (Config.Look l : current) result.add(copy(l));
        Set<String> usedIds = new HashSet<>();
        result.forEach(l -> usedIds.add(l.id));

        List<Entry> entries = new ArrayList<>();
        int added = 0, updated = 0, skipped = 0;
        for (int i = 0; i < looksNode.size(); i++) {
            JsonNode in = looksNode.get(i);
            List<String> msgs = new ArrayList<>();
            if (version > FORMAT_VERSION && i == 0) msgs.add("file format version " + version + " is newer than this app: unknown fields ignored");
            String label = describe(in);
            if (select != null && !select.contains(i)) {
                entries.add(new Entry(i, label, "skipped", List.of("not selected")));
                skipped++;
                continue;
            }
            if (!in.isObject()) {
                entries.add(new Entry(i, "#" + (i + 1), "error", List.of("not an object")));
                skipped++;
                continue;
            }
            Config.Look target = mode == Mode.MERGE ? findMatch(result, in) : null;
            if (in.path("disableOnly").asBoolean(false)) {
                // a look the sender no longer has (e.g. a show build left its executor empty): only switch off a
                // matching look that is already here, never add one
                if (target == null) {
                    entries.add(new Entry(i, label, "skipped", List.of(mode == Mode.MERGE
                            ? "not in this show (no such look here to switch off)" : "not in this show")));
                    skipped++;
                } else {
                    target.enabled = false;
                    entries.add(new Entry(i, target.label(), "updated", List.of("not in this show: switched off")));
                    updated++;
                }
                continue;
            }
            boolean isNew = target == null;
            if (isNew) {
                if (location(in, "exec") == null) {
                    entries.add(new Entry(i, label, "error", List.of("no MA3 executor (ma3.exec) and no matching existing look")));
                    skipped++;
                    continue;
                }
                target = new Config.Look();
                target.role = null; // detect missing role below
            }
            String error = apply(target, in, msgs, isNew);
            if (error != null) {
                entries.add(new Entry(i, label, "error", List.of(error)));
                skipped++;
                continue;
            }
            if (isNew) {
                String id = text(in, "id");
                target.id = id == null || id.isBlank() || usedIds.contains(id) ? ConfigStore.newId() : id;
                usedIds.add(target.id);
                result.add(target);
                added++;
            } else {
                updated++;
            }
            entries.add(new Entry(i, target.label(), isNew ? "added" : "updated", msgs));
        }
        return new Result(result, entries, file.path("context").isObject() ? (ObjectNode) file.path("context") : null,
                added, updated, skipped);
    }

    /** Apply the context block (speed master, control addresses) to a config. */
    public static void applyContext(Config c, JsonNode ctx) {
        if (ctx == null || !ctx.isObject()) return;
        if (ctx.hasNonNull("speedMaster")) c.speed.master = ctx.get("speedMaster").asInt(c.speed.master);
        if (ctx.hasNonNull("controlPrefix")) c.oscIn.controlPrefix = ctx.get("controlPrefix").asText();
        if (ctx.hasNonNull("oscInPort")) c.oscIn.port = ctx.get("oscInPort").asInt(c.oscIn.port);
        if (ctx.path("controlSequences").isObject()) {
            Map<String, String> m = new LinkedHashMap<>();
            ctx.get("controlSequences").fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
            c.oscIn.controlSequences = m;
        }
    }

    private static Config.Look findMatch(List<Config.Look> looks, JsonNode in) {
        String id = text(in, "id");
        if (id != null) {
            for (Config.Look l : looks) if (id.equals(l.id)) return l;
        }
        Integer page = location(in, "page"), exec = location(in, "exec");
        if (exec != null) {
            int p = page == null ? 1 : page;
            for (Config.Look l : looks) if (l.page == p && l.exec == exec) return l;
        }
        return null;
    }

    /** Copy the fields present in {@code in} onto the look. Returns an error message or null. */
    private static String apply(Config.Look l, JsonNode in, List<String> msgs, boolean isNew) {
        if (in.has("role")) {
            Role role = parseEnum(Role.class, text(in, "role"));
            if (role == null) return "unknown role \"" + text(in, "role") + "\"";
            l.role = role;
        } else if (isNew) {
            l.role = Role.EFFECT;
            msgs.add("no role given: set to EFFECT");
        }
        if (in.has("name")) l.name = text(in, "name") == null ? "" : text(in, "name");
        if (in.has("description")) l.description = text(in, "description") == null ? "" : text(in, "description");
        if (in.has("layer")) {
            String layer = text(in, "layer");
            l.layer = layer == null || layer.isBlank() || layer.equalsIgnoreCase(l.role.name()) ? null : layer;
        }
        Integer page = location(in, "page"), exec = location(in, "exec");
        if (page != null) l.page = page;
        if (exec != null) l.exec = exec;
        if (hasLocation(in, "sequence")) l.sequence = location(in, "sequence");
        if (in.has("mode")) {
            String m = text(in, "mode");
            if (m == null || m.isBlank()) {
                l.mode = null;
            } else {
                Role.Mode mode = parseEnum(Role.Mode.class, m);
                if (mode == null) msgs.add("unknown mode \"" + m + "\": role default used");
                l.mode = mode == null || mode == l.role.defaultMode ? null : mode;
            }
        }
        if (in.has("sections")) {
            List<Section> sections = new ArrayList<>();
            for (JsonNode s : in.path("sections")) {
                Section sec = parseEnum(Section.class, s.asText());
                if (sec == null) msgs.add("unknown section \"" + s.asText() + "\" ignored");
                else if (!sections.contains(sec)) sections.add(sec);
            }
            l.sections = sections;
        }
        JsonNode energy = in.path("energy");
        if (energy.has("min")) l.energyMin = energy.get("min").asDouble(l.energyMin);
        if (energy.has("max")) l.energyMax = energy.get("max").asDouble(l.energyMax);
        if (in.has("energyMin")) l.energyMin = in.get("energyMin").asDouble(l.energyMin);
        if (in.has("energyMax")) l.energyMax = in.get("energyMax").asDouble(l.energyMax);
        if (in.has("weight")) l.weight = in.get("weight").asDouble(l.weight);
        if (in.has("flashBeats")) l.flashBeats = in.get("flashBeats").asDouble(l.flashBeats);
        if (in.has("level")) l.level = in.get("level").asInt(l.level);
        if (in.has("enabled")) l.enabled = in.get("enabled").asBoolean(l.enabled);
        if (in.has("tags")) {
            List<String> tags = new ArrayList<>();
            for (JsonNode t : in.path("tags")) tags.add(t.asText());
            l.tags = tags;
        }
        if (in.path("meta").isObject()) {
            l.meta = ConfigStore.JSON.convertValue(in.get("meta"), ConfigStore.JSON.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        if (l.name == null || l.name.isBlank()) msgs.add("no name");
        return null;
    }

    /** ma3.page / ma3.exec / ma3.sequence, or the same keys at the top level. */
    private static Integer location(JsonNode in, String key) {
        JsonNode v = in.path("ma3").path(key);
        if (v.isMissingNode() || v.isNull()) v = in.path(key);
        return v.isNumber() || v.isTextual() && v.asText().matches("\\d+") ? v.asInt() : null;
    }

    private static boolean hasLocation(JsonNode in, String key) {
        return in.path("ma3").has(key) || in.has(key);
    }

    private static String text(JsonNode in, String key) {
        JsonNode v = in.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(JsonNode in) {
        String name = text(in, "name");
        Integer page = location(in, "page"), exec = location(in, "exec");
        String where = exec == null ? "" : " [" + (page == null ? 1 : page) + "." + exec + "]";
        return (name == null || name.isBlank() ? String.valueOf(text(in, "role")) : name) + where;
    }

    private static Config.Look copy(Config.Look l) {
        return ConfigStore.JSON.convertValue(l, Config.Look.class);
    }
}
