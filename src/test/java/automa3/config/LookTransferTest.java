package automa3.config;

import automa3.model.Role;
import automa3.model.Section;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LookTransferTest {

    private static Config config() {
        Config c = ConfigStore.normalize(ConfigStore.defaultConfig());
        Config.Look l = c.looks.get(5); // "Move fast", MOVEMENT 1.106
        l.description = "fast circle on the spots";
        l.tags = List.of("spots");
        l.meta = Map.of("generator", Map.of("preset", "4.12"));
        l.sequence = 42;
        return c;
    }

    private static JsonNode json(String s) throws Exception {
        return ConfigStore.JSON.readTree(s);
    }

    @Test
    void exportThenReplaceImportGivesTheSameLooks() throws Exception {
        Config c = config();
        ObjectNode file = LookTransfer.export(c);
        assertEquals("automa3-looks", file.get("format").asText());
        JsonNode move = file.get("looks").get(5);
        assertEquals(106, move.get("ma3").get("exec").asInt());
        assertEquals(42, move.get("ma3").get("sequence").asInt());
        assertEquals("fast circle on the spots", move.get("description").asText());
        assertEquals("4.12", move.get("meta").get("generator").get("preset").asText());

        // through text, like a real file
        JsonNode reread = json(ConfigStore.JSON.writeValueAsString(file));
        LookTransfer.Result r = LookTransfer.importLooks(List.of(), reread, LookTransfer.Mode.REPLACE, null);
        assertEquals(c.looks.size(), r.added());
        assertEquals(ConfigStore.JSON.valueToTree(c.looks), ConfigStore.JSON.valueToTree(r.looks()), "round trip keeps everything");
    }

    @Test
    void mergeUpdatesOnlyTheGivenFields() throws Exception {
        Config c = config();
        // same executor as "Move fast", only a new description and sections
        JsonNode file = json("""
                {"format": "automa3-looks", "version": 1, "looks": [
                  {"ma3": {"page": 1, "exec": 106}, "description": "new text", "sections": ["DROP", "PEAK"]}]}""");
        LookTransfer.Result r = LookTransfer.importLooks(c.looks, file, LookTransfer.Mode.MERGE, null);
        assertEquals(1, r.updated());
        assertEquals(0, r.added());
        Config.Look l = r.looks().get(5);
        assertEquals("new text", l.description);
        assertEquals(List.of(Section.DROP, Section.PEAK), l.sections);
        assertEquals("Move fast", l.name, "name not in the file: unchanged");
        assertEquals(Role.MOVEMENT, l.role);
        assertEquals(0.5, l.energyMin, "energy unchanged");
        assertEquals(c.looks.size(), r.looks().size());
        assertEquals("fast circle on the spots", c.looks.get(5).description, "input list not modified");
    }

    @Test
    void partialNewLooksGetDefaultsAndNotes() throws Exception {
        JsonNode file = json("""
                {"format": "automa3-looks", "looks": [
                  {"role": "STROBE", "ma3": {"page": 3, "exec": 201}},
                  {"ma3": {"exec": 202}},
                  {"name": "no location"},
                  {"role": "LASERS", "ma3": {"exec": 203}},
                  {"role": "COLOR", "page": 2, "exec": 204, "sections": ["DROP", "BANGER"], "mode": "flash"}]}""");
        LookTransfer.Result r = LookTransfer.importLooks(List.of(), file, LookTransfer.Mode.MERGE, null);
        assertEquals(3, r.added());
        assertEquals(2, r.skipped());
        Config.Look strobe = r.looks().get(0);
        assertEquals(Role.STROBE, strobe.role);
        assertEquals(3, strobe.page);
        assertEquals(201, strobe.exec);
        assertEquals(Role.Mode.FLASH, strobe.modeOrDefault(), "role default mode");
        assertTrue(r.entries().get(0).messages().contains("no name"));
        assertEquals(Role.EFFECT, r.looks().get(1).role, "missing role -> EFFECT");
        assertEquals("error", r.entries().get(2).action(), "no executor and nothing to match");
        assertEquals("error", r.entries().get(3).action(), "unknown role");
        Config.Look color = r.looks().get(2);
        assertEquals(2, color.page, "flat page/exec keys accepted");
        assertEquals(List.of(Section.DROP), color.sections, "unknown section dropped");
        assertEquals(Role.Mode.FLASH, color.mode);
        assertTrue(r.entries().get(4).messages().stream().anyMatch(m -> m.contains("BANGER")));
        assertTrue(r.looks().stream().allMatch(l -> l.id != null && !l.id.isBlank()), "ids assigned");
    }

    @Test
    void selectionAndAddMode() throws Exception {
        Config c = config();
        JsonNode file = LookTransfer.export(c);
        LookTransfer.Result r = LookTransfer.importLooks(c.looks, file, LookTransfer.Mode.ADD, Set.of(0, 2));
        assertEquals(2, r.added());
        assertEquals(c.looks.size() + 2, r.looks().size());
        assertEquals(c.looks.size() - 2, r.skipped());
        assertEquals(r.looks().stream().map(l -> l.id).distinct().count(), r.looks().size(), "added copies get new ids");
    }

    @Test
    void matchesById() throws Exception {
        Config c = config();
        String id = c.looks.get(0).id;
        JsonNode file = json("{\"format\": \"automa3-looks\", \"looks\": [{\"id\": \"" + id + "\", \"name\": \"Renamed\", \"ma3\": {\"exec\": 150}}]}");
        LookTransfer.Result r = LookTransfer.importLooks(c.looks, file, LookTransfer.Mode.MERGE, null);
        assertEquals(1, r.updated());
        assertEquals("Renamed", r.looks().get(0).name);
        assertEquals(150, r.looks().get(0).exec, "moved to another executor");
    }

    @Test
    void rejectsOtherFiles() {
        assertThrows(IllegalArgumentException.class, () ->
                LookTransfer.importLooks(List.of(), json("{\"format\": \"something-else\", \"looks\": []}"), LookTransfer.Mode.MERGE, null));
        assertThrows(IllegalArgumentException.class, () ->
                LookTransfer.importLooks(List.of(), json("{\"format\": \"automa3-looks\"}"), LookTransfer.Mode.MERGE, null));
    }

    @Test
    void contextIsAppliedOnlyWhenAsked() throws Exception {
        Config c = config();
        JsonNode ctx = json("{\"speedMaster\": 4, \"controlPrefix\": \"/show\", \"controlSequences\": {\"900\": \"auto\"}}");
        LookTransfer.applyContext(c, ctx);
        assertEquals(4, c.speed.master);
        assertEquals("/show", c.oscIn.controlPrefix);
        assertEquals("auto", c.oscIn.controlSequences.get("900"));
    }
}
