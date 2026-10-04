package automa3.config;

import automa3.model.Role;
import automa3.model.Section;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps docs/looks-format.md and docs/looks.schema.json in step with the code. */
class LookFormatDocsTest {

    private static final Path DOC = Path.of("docs/looks-format.md");
    private static final Path SCHEMA = Path.of("docs/looks.schema.json");

    /** JSON code blocks of the format document that are look files. */
    private static List<JsonNode> lookFilesInDoc() throws Exception {
        String md = Files.readString(DOC);
        Matcher m = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL).matcher(md);
        List<JsonNode> files = new ArrayList<>();
        while (m.find()) {
            JsonNode n = ConfigStore.JSON.readTree(m.group(1));
            if ("automa3-looks".equals(n.path("format").asText())) files.add(n);
        }
        return files;
    }

    @Test
    void documentedExamplesImportCleanly() throws Exception {
        List<JsonNode> files = lookFilesInDoc();
        assertTrue(files.size() >= 2, "full and minimal example");
        for (JsonNode f : files) {
            LookTransfer.Result r = LookTransfer.importLooks(List.of(), f, LookTransfer.Mode.MERGE, null);
            assertEquals(0, r.skipped(), "example imports without errors: " + r.entries());
            assertEquals(f.get("looks").size(), r.added());
        }
    }

    @Test
    void schemaDescribesEverythingTheAppExports() throws Exception {
        JsonNode schema = ConfigStore.JSON.readTree(Files.readString(SCHEMA));
        JsonNode lookProps = schema.at("/$defs/look/properties");
        JsonNode export = LookTransfer.export(ConfigStore.normalize(ConfigStore.defaultConfig()));

        for (Iterator<String> it = export.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(schema.at("/properties").has(field), "file field in schema: " + field);
        }
        for (Iterator<String> it = export.get("context").fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(schema.at("/$defs/context/properties").has(field), "context field in schema: " + field);
        }
        JsonNode look = export.get("looks").get(0);
        for (Iterator<String> it = look.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(lookProps.has(field), "look field in schema: " + field);
        }
        for (Iterator<String> it = look.get("ma3").fieldNames(); it.hasNext(); ) {
            assertTrue(lookProps.at("/ma3/properties").has(it.next()));
        }
        assertEquals(enumNames(Role.values()), values(schema.at("/$defs/role/enum")));
        assertEquals(enumNames(Role.Mode.values()), values(schema.at("/$defs/mode/enum")));
        assertEquals(enumNames(Section.values()), values(schema.at("/$defs/section/enum")));
    }

    @Test
    void documentMentionsEveryRoleAndSection() throws Exception {
        String md = Files.readString(DOC);
        for (Role r : Role.values()) assertTrue(md.contains("`" + r.name() + "`"), "role documented: " + r);
        for (Role.Mode m : Role.Mode.values()) assertTrue(md.contains("`" + m.name() + "`"), "mode documented: " + m);
        for (Section s : Section.values()) assertTrue(md.contains("`" + s.name() + "`"), "section documented: " + s);
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> out = new HashSet<>();
        for (Enum<?> e : values) out.add(e.name());
        return out;
    }

    private static Set<String> values(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
