package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java show creator against reference results of the Python version it replaces (src/test/resources/
 * showcreator/golden, generated from the real show "auto_ma3_test" confirmed on onPC 2.3.2, the demo show and the
 * Python test fixtures). Job files must match byte for byte (except the job id); JSON must be equal as data.
 */
class ParityTest {

    static Path golden(String name) throws Exception {
        return Path.of(ParityTest.class.getResource("/showcreator/golden/" + name).toURI());
    }

    static JsonNode json(String name) throws Exception {
        return ShowFiles.JSON.readTree(Files.readString(golden(name)));
    }

    static String text(String name) throws Exception {
        return Files.readString(golden(name));
    }

    static ShowSettings settings(String version, String cueMethod, Map<String, String> overrides) {
        ShowSettings.Range r = new ShowSettings.Range(501, 599);
        return new ShowSettings(version, Path.of("/tmp/none"), r, r, r, 1, new ShowSettings.Range(101, 199), cueMethod, "-y", 1, overrides);
    }

    static ShowSettings settings() {
        return settings("2.3.2", "recipe", Map.of());
    }

    static JsonNode miniLibrary() throws Exception {
        return EffectLibrary.build(golden("predefined_phaser_mini.xml"), null, "2.3.2");
    }

    static String normJob(String lua) {
        return lua.replaceAll("job_id = \"[^\"]+\"", "job_id = \"JOB_ID\"");
    }

    /** Equal as data: numbers by value (Python writes 1500.0 where Java may keep 1500.0 or 1500). */
    static void assertJsonEquals(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path + ": " + expected + " vs " + actual);
            assertEquals(expected.isIntegralNumber(), actual.isIntegralNumber(), path + ": int/float differs, " + expected + " vs " + actual);
            return;
        }
        assertEquals(expected.getNodeType(), actual.getNodeType(), path + ": " + expected + " vs " + actual);
        if (expected.isArray()) {
            assertEquals(expected.size(), actual.size(), path + " size: " + expected + " vs " + actual);
            for (int i = 0; i < expected.size(); i++) assertJsonEquals(expected.get(i), actual.get(i), path + "[" + i + "]");
        } else if (expected.isObject()) {
            assertEquals(ShowFiles.keys(expected), ShowFiles.keys(actual), path + " keys");
            for (String k : ShowFiles.keys(expected)) assertJsonEquals(expected.get(k), actual.get(k), path + "." + k);
        } else {
            assertEquals(expected, actual, path);
        }
    }

    // ---- intended differences to the Python version (each tested on its own in StrobeAndSpeedTest)

    /** The speed master link and speed scale are set only on sequences with a beat effect. */
    static boolean speedStep(JsonNode step) {
        String key = step.path("key").asText();
        return key.equals("speed_master") || key.equals("seq_prop") && step.path("cmd").asText().contains("\"SpeedScale\"");
    }

    static JsonNode withoutSpeedSteps(JsonNode steps) {
        com.fasterxml.jackson.databind.node.ArrayNode out = ShowFiles.array();
        steps.forEach(st -> { if (!speedStep(st)) out.add(st); });
        return out;
    }

    /** The same for job files: drops the step blocks that set the speed master or the speed scale. */
    static String withoutSpeedSteps(String lua) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^    \\{\n(?:      .*\n)*?    \\},\n").matcher(lua);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            boolean speed = block.contains("key = \"speed_master\"") || block.contains("key = \"seq_prop\"") && block.contains("\\\"SpeedScale\\\"");
            m.appendReplacement(sb, speed ? "" : java.util.regex.Matcher.quoteReplacement(block));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The brief's speed rule and the strobe value line are new. */
    static List<String> briefWithoutNewLines(List<String> lines) {
        return lines.stream().filter(l -> !l.startsWith("- Every sequence runs on speed master") && !l.startsWith("- Sequences with an effect")
                && !l.startsWith("- `strobe`:")).toList();
    }

    /** The real strobe value (Hz) is new. */
    static JsonNode withoutStrobeValue(JsonNode library) {
        ObjectNode copy = library.deepCopy();
        ((ObjectNode) copy.get("static_attributes")).remove("strobe");
        return copy;
    }

    /** The speed and strobe checks are new. */
    static List<PlanValidator.Issue> withoutNewIssues(List<PlanValidator.Issue> issues) {
        List<String> added = List.of("strobe_on_speed_master", "speed_scale_unused", "strobe_channel");
        return issues.stream().filter(i -> !added.contains(i.code())).toList();
    }

    /** The one intended difference to the Python files: left-over looks are marked "disable only". */
    static JsonNode withoutDisableOnly(ObjectNode looks) {
        ObjectNode copy = looks.deepCopy();
        copy.get("looks").forEach(l -> ((ObjectNode) l).remove("disableOnly"));
        return copy;
    }

    static JsonNode issuesJson(List<PlanValidator.Issue> issues) {
        ArrayNode a = ShowFiles.array();
        for (PlanValidator.Issue i : issues) {
            a.addObject().put("level", i.level()).put("path", i.path()).put("code", i.code()).put("message", i.message()).put("hint", i.hint());
        }
        return a;
    }

    static List<JsonNode> defaultLooks() {
        return ShowCompiler.list(ShowFiles.resource("library/default_looks.json").get("looks"));
    }

    @Test
    void libraryFromPredefinedPhasers() throws Exception {
        assertJsonEquals(json("library_mini.json"), withoutStrobeValue(miniLibrary()), "$");
    }

    @Test
    void bundledLibraryMatchesTheInstalledGrandMA3File() throws Exception {
        Path xml = EffectLibrary.predefinedPhaserXml(Path.of(System.getProperty("user.home"), "MALightingTechnology"), "2.3.2");
        org.junit.jupiter.api.Assumptions.assumeTrue(xml != null, "grandMA3 onPC 2.3.2 not installed here");
        assertJsonEquals(EffectLibrary.bundled(), EffectLibrary.build(xml, EffectLibrary.annotations(), "2.3.2"), "$");
    }

    @Test
    void consolePatchLog() throws Exception {
        Rig.Patch p = Rig.readPatchLog(golden("log_patch.jsonl"));
        JsonNode expected = json("patch_read.json");
        assertEquals(expected.get("job_id").asText(), p.jobId());
        assertEquals(expected.get("ma3").asText(), p.ma3());
        assertEquals(expected.get("fixtures").size(), p.fixtures().size());
        for (int i = 0; i < p.fixtures().size(); i++) {
            JsonNode e = expected.get("fixtures").get(i);
            Rig.PatchFixture f = p.fixtures().get(i);
            assertEquals(e.get("fid").asInt(), f.fid());
            assertEquals(e.get("name").asText(), f.name());
            assertEquals(e.get("fixturetype").asText(), f.fixturetype());
            assertEquals(e.get("mode").asText(), f.mode());
            for (int k = 0; k < 3; k++) assertEquals(e.get("pos").get(k).doubleValue(), f.pos()[k], "pos " + f.fid());
            for (int k = 0; k < 3; k++) assertEquals(e.get("rot").get(k).doubleValue(), f.rot()[k], "rot " + f.fid());
        }
        assertEquals(ShowFiles.keys(expected.get("types")), new ArrayList<>(p.types().keySet()));
        for (String t : ShowFiles.keys(expected.get("types"))) {
            for (String m : ShowFiles.keys(expected.get("types").get(t))) {
                assertEquals(ShowFiles.texts(expected.get("types").get(t).get(m)), p.types().get(t).get(m), t + " / " + m);
            }
        }
    }

    @Test
    void rigFromTheConsolePatchInAllFourDirections() throws Exception {
        Rig.Patch p = Rig.readPatchLog(golden("log_patch.jsonl"));
        for (String aud : List.of("-y", "+y", "-x", "+x")) {
            JsonNode expected = json("rig_patch_" + aud.replace("-", "m").replace("+", "p") + ".json");
            ObjectNode source = ShowFiles.object().put("generator", "test");
            Rig.Built b = Rig.build(p, null, aud, source);
            assertJsonEquals(expected.get("rig"), b.rig(), "rig " + aud);
            assertEquals(ShowFiles.texts(expected.get("warnings")), b.warnings(), "warnings " + aud);
            assertTrue(SchemaCheck.validate(b.rig(), SchemaCheck.load("rig")).isEmpty(), "rig matches its schema");
        }
    }

    @Test
    void validationOfRealPlans() throws Exception {
        for (String name : List.of("auto_ma3_test", "demo", "fixture")) {
            JsonNode library = name.equals("fixture") ? miniLibrary() : EffectLibrary.bundled();
            List<PlanValidator.Issue> issues = PlanValidator.validate(json(name + ".plan.json"), json(name + ".rig.json"), library,
                    settings(), defaultLooks());
            assertJsonEquals(json(name + ".validation.json"), issuesJson(withoutNewIssues(issues)), name);
        }
    }

    @Test
    void validatorEdgeCases() throws Exception {
        JsonNode library = miniLibrary();
        for (JsonNode c : json("validate_cases.json")) {
            ShowSettings base = settings();
            JsonNode cfg = c.get("cfg");
            ShowSettings s = new ShowSettings(base.ma3Version(), base.ma3Root(),
                    cfg.has("groups") ? new ShowSettings.Range(cfg.get("groups").get(0).asInt(), cfg.get("groups").get(1).asInt()) : base.groups(),
                    base.sequences(), base.matricks(), base.page(),
                    cfg.has("executors") ? new ShowSettings.Range(cfg.get("executors").get(0).asInt(), cfg.get("executors").get(1).asInt()) : base.executors(),
                    base.cueMethod(), base.audience(), base.speedMaster(), base.commandOverrides());
            List<JsonNode> looksIn = c.get("looks_in").isNull() ? null : ShowCompiler.list(c.get("looks_in"));
            List<PlanValidator.Issue> issues = PlanValidator.validate(c.get("plan"), c.get("rig"), library, s, looksIn);
            assertJsonEquals(c.get("issues"), issuesJson(withoutNewIssues(issues)), c.get("name").asText());
        }
    }

    @Test
    void jobFilesOfRealPlansByteForByte() throws Exception {
        for (String name : List.of("auto_ma3_test", "demo", "fixture")) {
            JsonNode library = name.equals("fixture") ? miniLibrary() : EffectLibrary.bundled();
            for (String method : List.of("recipe", "programmer")) {
                ShowSettings s = settings("2.3.2", method, Map.of());
                ShowCompiler.Compiled c = ShowCompiler.compileBuild(json(name + ".plan.json"), json(name + ".rig.json"), library, s, defaultLooks());
                assertEquals(withoutSpeedSteps(text(name + ".job_build_" + method + ".lua")), withoutSpeedSteps(normJob(c.job().toLua("2.3.2"))),
                        name + " " + method);
                if (method.equals("recipe")) {
                    assertJsonEquals(json(name + ".allocation.json"), c.allocation().toJson(), name + " allocation");
                    c.looks().put("exportedAt", "TIME");
                    assertJsonEquals(json(name + ".looks.out.json"), withoutDisableOnly(c.looks()), name + " looks");
                }
            }
        }
    }

    @Test
    void compilerEdgeCases() throws Exception {
        JsonNode library = miniLibrary();
        JsonNode rig = json("fixture.rig.json");
        for (JsonNode c : json("compile_cases.json")) {
            JsonNode cfg = c.get("cfg");
            ShowSettings base = settings();
            ShowSettings s = new ShowSettings(base.ma3Version(), base.ma3Root(), base.groups(), base.sequences(), base.matricks(),
                    cfg.path("page").asInt(base.page()), base.executors(), cfg.path("cue_method").asText(base.cueMethod()),
                    base.audience(), cfg.path("speed_master").asInt(base.speedMaster()), base.commandOverrides());
            List<JsonNode> looksIn = c.get("looks_in").isNull() ? null : ShowCompiler.list(c.get("looks_in"));
            ShowCompiler.Compiled out = ShowCompiler.compileBuild(c.get("plan"), rig, library, s, looksIn);
            String name = c.get("name").asText();
            assertJsonEquals(withoutSpeedSteps(c.get("steps")), withoutSpeedSteps(ShowFiles.JSON.valueToTree(out.job().steps())), name + " steps");
            assertJsonEquals(c.get("allocation"), out.allocation().toJson(), name + " allocation");
            out.looks().put("exportedAt", "TIME");
            assertJsonEquals(c.get("looks"), withoutDisableOnly(out.looks()), name + " looks");
        }
    }

    @Test
    void otherJobsAndVersions() throws Exception {
        ShowSettings s = settings();
        assertEquals(text("job_inspect.lua"), normJob(ShowCompiler.compileInspect(s).toLua("2.3.2")));
        assertEquals(text("job_patch.lua"), normJob(ShowCompiler.compilePatch(s).toLua("2.3.2")));
        JsonNode plan = json("fixture.plan.json"), rig = json("fixture.rig.json");
        List<Integer> fids = Rig.resolveGroup(rig, plan.get("groups").get(0).get("fixtures"));
        assertEquals(text("job_probe.lua"), normJob(ShowCompiler.compileProbe(fids, miniLibrary(), s).toLua("2.3.2")));
        ShowSettings s25 = settings("2.5.1", "recipe", Map.of("store_cue", "Store Sequence {seq} Cue {cue} /Merge"));
        String lua = ShowCompiler.compileBuild(plan, rig, miniLibrary(), s25, List.of()).job().toLua("2.5.1");
        assertEquals(withoutSpeedSteps(text("fixture.job_build_25_override.lua")), withoutSpeedSteps(normJob(lua)));
    }

    @Test
    void briefOfRealShows() throws Exception {
        for (String name : List.of("auto_ma3_test", "demo")) {
            String expected = text(name + ".brief.md");
            String actual = Brief.build(name, settings(), json(name + ".rig.json"), EffectLibrary.bundled(), defaultLooks(),
                    "AutoMA3 default looks (no looks.in.json in the show folder)");
            // the first lines name the tool and where the plan goes (different in AutoMA3), the rest is the same
            List<String> e = new ArrayList<>(List.of(expected.split("\n", -1)));
            List<String> a = new ArrayList<>(List.of(actual.split("\n", -1)));
            assertEquals(e.get(0), a.get(0));
            assertTrue(a.get(1).startsWith("Generated by AutoMA3"));
            assertTrue(a.get(3).startsWith("**Write the plan to:** `shows/" + name + "/plan.json`"));
            assertEquals(briefWithoutNewLines(e.subList(4, e.size())), briefWithoutNewLines(a.subList(4, a.size())), name);
        }
    }
}
