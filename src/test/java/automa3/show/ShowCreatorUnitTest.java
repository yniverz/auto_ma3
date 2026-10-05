package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The Python show creator's library, brief, profile, Lua writer and plugin tests, ported. */
class ShowCreatorUnitTest {

    @TempDir
    Path tmp;

    JsonNode lib() throws Exception {
        return ParityTest.miniLibrary();
    }

    JsonNode plan() throws Exception {
        return ParityTest.json("fixture.plan.json");
    }

    JsonNode rig() throws Exception {
        return ParityTest.json("fixture.rig.json");
    }

    // ---------------------------------------------------------------- library

    @Test
    void libraryNumberingFollowsFileOrder() throws Exception {
        JsonNode p = lib().get("presets");
        assertEquals(List.of("dim_sinus", "chase", "circle", "zoom_blue_white", "static_red"), ShowFiles.keys(p));
        for (int i = 0; i < 5; i++) assertEquals(i + 1, p.get(ShowFiles.keys(p).get(i)).get("index").asInt());
        p.forEach(v -> assertEquals(21, v.get("pool").asInt()));
    }

    @Test
    void capabilitiesDerivedFromAttributes() throws Exception {
        JsonNode p = lib().get("presets");
        assertEquals(List.of("dimmer"), ShowFiles.texts(p.get("dim_sinus").get("requires")));
        assertEquals(List.of("pan", "tilt"), ShowFiles.texts(p.get("circle").get("requires")));
        assertEquals(List.of("position"), ShowFiles.texts(p.get("circle").get("affects")));
        assertEquals(List.of("color_mix", "zoom"), ShowFiles.texts(p.get("zoom_blue_white").get("requires")));
        assertEquals("static", p.get("static_red").get("kind").asText());
    }

    @Test
    void annotationsOverride() throws Exception {
        JsonNode ann = ShowFiles.JSON.readTree("{\"presets\": {\"Zoom - Blue White\": {\"requires\": [\"color_mix\"], \"energy\": 0.5}}}");
        JsonNode lib = EffectLibrary.build(ParityTest.golden("predefined_phaser_mini.xml"), ann, "");
        assertEquals(List.of("color_mix"), ShowFiles.texts(lib.get("presets").get("zoom_blue_white").get("requires")));
        assertEquals(0.5, lib.get("presets").get("zoom_blue_white").get("energy").doubleValue());
        assertTrue(lib.get("presets").get("chase").get("energy").isNull());
    }

    @Test
    void libraryMatchesSchemaAndSharesTheCapVocabulary() throws Exception {
        assertEquals(List.of(), SchemaCheck.validate(lib(), SchemaCheck.load("library")));
        assertEquals(List.of(), SchemaCheck.validate(EffectLibrary.bundled(), SchemaCheck.load("library")));
        assertEquals(SchemaCheck.load("rig").get("$defs").get("cap"), SchemaCheck.load("library").get("$defs").get("cap"));
        assertEquals(List.of(), SchemaCheck.validate(rig(), SchemaCheck.load("rig")));
    }

    @Test
    void slug() {
        assertEquals("zoom_blue_white", EffectLibrary.slug("Zoom - Blue White"));
        assertEquals("p_21_thing", EffectLibrary.slug("21 thing"));
    }

    // ---------------------------------------------------------------- brief

    @Test
    void briefListsRigLibraryAndDefaultLooks() throws Exception {
        String text = Brief.build("t", ParityTest.settings(), rig(), lib(), ParityTest.defaultLooks(), "defaults");
        assertTrue(text.contains("| 101 | Demo Spot |") || text.contains("| 101 | Stairville MH 360 |"));
        assertTrue(text.contains("### `class:Spots`: 4 fixture(s)"));
        assertTrue(text.contains("| `chase` | 21.2 Chase | dimmer |"));
        assertTrue(text.contains("| 6 | - | Dimmer chase | EFFECT | 107 |"));
        assertTrue(text.contains("Haze (HAZE: no fixture with haze)"));
        assertTrue(text.contains("**Write the plan to:**"));
    }

    @Test
    void briefFlagsOutOfRangeExecutors() throws Exception {
        List<JsonNode> looks = List.of(ShowFiles.JSON.readTree("{\"id\": \"ab12\", \"name\": \"Spots\", \"role\": \"MOVEMENT\", \"ma3\": {\"page\": 2, \"exec\": 201}}"));
        assertTrue(Brief.build("t", ParityTest.settings(), rig(), lib(), looks, "x").contains("| 0 | ab12 | Spots | MOVEMENT | 2.201 (outside range: omit) |"));
        ShowSettings s = ParityTest.settings();
        ShowSettings s2 = new ShowSettings(s.ma3Version(), s.ma3Root(), s.groups(), s.sequences(), s.matricks(), 2,
                new ShowSettings.Range(200, 299), s.cueMethod(), s.audience(), s.speedMaster(), s.commandOverrides());
        assertTrue(Brief.build("t", s2, rig(), lib(), looks, "x").contains("| 0 | ab12 | Spots | MOVEMENT | 201 |"));
    }

    @Test
    void unfilledLooksWarn() throws Exception {
        List<JsonNode> looksIn = List.of(ShowFiles.JSON.readTree("{\"id\": \"bd94297e\", \"role\": \"EFFECT\"}"),
                ShowFiles.JSON.readTree("{\"name\": \"Move\", \"role\": \"MOVEMENT\", \"ma3\": {\"exec\": 105}}"),
                ShowFiles.JSON.readTree("{\"name\": \"Haze\", \"role\": \"HAZE\", \"ma3\": {\"exec\": 113}}"));
        List<PlanValidator.Issue> issues = PlanValidator.validate(plan(), rig(), lib(), ParityTest.settings(), looksIn);
        assertEquals(1, issues.size());
        assertEquals("look_not_filled", issues.get(0).code());
        assertTrue(issues.get(0).message().contains("'Move'")); // EFFECT matched by id; HAZE impossible with this rig
    }

    // ---------------------------------------------------------------- profiles, Lua

    @Test
    void profileResolution() throws Exception {
        Object[][] cases = {{"2.3.2", "2.3", false}, {"2.3.1.1", "2.3", false}, {"2.5.0", "2.5", false},
                {"2.7.1", "2.5", true}, {"1.9.7", "2.0", true}};
        for (Object[] c : cases) {
            BuildProfile p = BuildProfile.resolve((String) c[0], Map.of());
            assertEquals(c[1], p.name, (String) c[0]);
            assertEquals(c[2], !p.warnings.isEmpty(), (String) c[0]);
            if (!c[1].equals("2.3")) p.templates.values().forEach(t -> assertEquals("assumed", t.status()));
        }
    }

    @Test
    void profileOverrideAndPlaybackKeysLeftAlone() throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("store_cue", "Store Cue {cue} Sequence {seq}");
        overrides.put("go", "Go+ Page {page}.{exec}"); // a playback template of the same console: not ours
        BuildProfile p = BuildProfile.resolve("2.3.2", overrides);
        assertEquals("Store Cue 2 Sequence 1", p.t("store_cue", "seq", 1, "cue", 2));
        assertFalse(p.templates.containsKey("go"));
        assertThrows(ShowException.class, () -> BuildProfile.resolve("x.y", Map.of()));
    }

    @Test
    void luaSerialiser() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", LuaWriter.str("a\"b\\c\nd"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("end", 1);
        List<Object> x = new ArrayList<>();
        x.add(true);
        x.add(null);
        x.add(1.5);
        m.put("x", x);
        assertEquals("{\n  [\"end\"] = 1,\n  x = {\n    true,\n    nil,\n    1.5,\n  },\n}", LuaWriter.write(m, 0));
        assertThrows(IllegalArgumentException.class, () -> LuaWriter.write(Double.NaN, 0));
    }

    @Test
    void executorAllocationNeverLeavesTheRange() throws Exception {
        ObjectNode plan = (ObjectNode) plan().deepCopy();
        ((ObjectNode) plan.get("looks").get(0)).remove("exec");
        ShowSettings s = ParityTest.settings();
        ShowSettings one = new ShowSettings(s.ma3Version(), s.ma3Root(), s.groups(), s.sequences(), s.matricks(), 1,
                new ShowSettings.Range(101, 101), s.cueMethod(), s.audience(), s.speedMaster(), s.commandOverrides());
        assertEquals(101, ShowCompiler.allocate(plan, one).executors().values().iterator().next());
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.get("looks")).addObject().put("key", "second").put("sequence", "dimmer_wave")
                .put("role", "BASE").put("name", "Second");
        assertThrows(ShowException.class, () -> ShowCompiler.allocate(plan, one));
    }

    // ---------------------------------------------------------------- the plugin itself, in Lua with a console mock

    static String lua() {
        for (String p : List.of("/opt/homebrew/bin/lua", "/usr/local/bin/lua", "/usr/bin/lua", "/opt/homebrew/bin/lua5.4")) {
            if (Files.isExecutable(Path.of(p))) return p;
        }
        return null;
    }

    record Run(List<JsonNode> records, String stderr, int exit) {
    }

    Run run(ShowCompiler.Job job, Map<String, String> env) throws Exception {
        String lua = lua();
        Assumptions.assumeTrue(lua != null, "no lua interpreter installed");
        Path jobPath = tmp.resolve("job_" + job.kind + ".lua");
        Files.writeString(jobPath, job.toLua("2.3.2"));
        Path plugin = tmp.resolve("ShowBuilder.lua");
        Files.write(plugin, ShowFiles.resourceBytes("plugin/ShowBuilder.lua"));
        Path mock = Path.of(getClass().getResource("/showcreator/lua/mock_ma3.lua").toURI());
        ProcessBuilder pb = new ProcessBuilder(lua, mock.toString(), plugin.toString(), jobPath.toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        String err = new String(p.getErrorStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        Path log = tmp.resolve("log_" + job.kind + ".jsonl");
        List<JsonNode> records = new ArrayList<>();
        if (Files.exists(log)) for (String l : Files.readAllLines(log)) if (!l.isBlank()) records.add(ShowFiles.JSON.readTree(l));
        return new Run(records, err, p.exitValue());
    }

    ShowCompiler.Job buildJob() throws Exception {
        return ShowCompiler.compileBuild(plan(), rig(), lib(), ParityTest.settings(), List.of()).job();
    }

    @Test
    void pluginRunsTheBuildToTheEnd() throws Exception {
        ShowCompiler.Job job = buildJob();
        Run r = run(job, Map.of());
        assertEquals("start", r.records().get(0).get("ev").asText());
        assertEquals("2.3.2.0", r.records().get(0).get("ma3").asText());
        JsonNode end = r.records().get(r.records().size() - 1);
        assertEquals("end", end.get("ev").asText());
        assertTrue(end.get("ok").asBoolean());
        assertFalse(end.get("aborted").asBoolean());
        assertEquals(0, end.get("failed").asInt());
        assertEquals(job.stepCount(), end.get("done").asInt());
        assertEquals(job.stepCount(), r.records().stream().filter(x -> x.get("ev").asText().equals("begin")).count());
        assertEquals(job.stepCount(), r.records().stream().filter(x -> x.get("ev").asText().equals("step")).count());
        assertTrue(Files.exists(tmp.resolve("export_build_sequence_501.xml")));
        assertEquals("", r.stderr());
    }

    @Test
    void pluginAbortsOnAFatalFailureAndToleratesDeletes() throws Exception {
        Run r = run(buildJob(), Map.of("MOCK_FAIL", "Store Group"));
        JsonNode end = r.records().get(r.records().size() - 1);
        assertTrue(end.get("aborted").asBoolean());
        assertEquals(1, end.get("failed").asInt());
        assertTrue(r.records().get(r.records().size() - 2).get("cmd").asText().startsWith("Store Group"));
        assertTrue(r.stderr().contains("aborting"));

        Run t = run(buildJob(), Map.of("MOCK_FAIL", "Delete"));
        JsonNode tEnd = t.records().get(t.records().size() - 1);
        assertTrue(tEnd.get("ok").asBoolean());
        assertEquals(3, t.records().stream().filter(x -> x.path("tolerated").asBoolean(false)).count());
    }

    @Test
    void pluginSetsEachSelectedFixturesOwnStrobeFunctionInHz() throws Exception {
        ShowCompiler.Job job = new ShowCompiler.Job("strobe", BuildProfile.resolve("2.3.2", Map.of()));
        job.op("strobe", "key", "strobe_hz", "functions", List.of("StrobeFrequency", "Shutter1Strobe"), "hz", 5.05, "fatal", true);
        job.op("strobe", "key", "strobe_hz", "functions", List.of("Shutter1Strobe"), "hz", 25.0, "fatal", false);
        job.op("strobe", "key", "strobe_hz", "functions", List.of("StrobeFrequency"), "hz", 8.0, "fatal", false);
        Run r = run(job, Map.of());
        assertEquals("", r.stderr());
        List<JsonNode> steps = r.records().stream().filter(x -> x.get("ev").asText().equals("step")).toList();
        JsonNode set = steps.get(0).get("set");
        assertTrue(steps.get(0).get("ok").asBoolean(), steps.get(0).toString());
        assertEquals(1, set.size(), "only the spot has one");
        assertEquals("Shutter1Strobe", set.get(0).get("function").asText());
        assertEquals(1, set.get(0).get("channel_function").asInt(), "the second function of the channel, 0-based");
        assertEquals(5.05, set.get(0).get("hz").asDouble(), 1e-9);
        assertEquals(1, steps.get(0).get("without").asInt());
        assertEquals(10.0, steps.get(1).get("set").get(0).get("hz").asDouble(), 1e-9, "capped at the fixture's fastest");
        assertFalse(steps.get(2).get("ok").asBoolean(), "nobody has it: the step fails");
        assertTrue(steps.get(2).get("error").asText().contains("no selected fixture"));
    }

    @Test
    void pluginRefusesAJobForAnotherVersion() throws Exception {
        Run r = run(buildJob(), Map.of("MOCK_MA3_VERSION", "2.5.1.2"));
        assertEquals(List.of("start", "end"), r.records().stream().map(x -> x.get("ev").asText()).toList());
        assertTrue(r.records().get(1).get("error").asText().contains("compiled for grandMA3 2.3.2"));
    }

    @Test
    void pluginReadsThePatch() throws Exception {
        Run r = run(ShowCompiler.compilePatch(ParityTest.settings()), Map.of());
        JsonNode patch = r.records().stream().filter(x -> "patch".equals(x.path("op").asText()) && "step".equals(x.get("ev").asText()))
                .findFirst().orElseThrow();
        assertTrue(patch.get("ok").asBoolean());
        assertEquals("Mock Spot", patch.get("types").get(0).get("name").asText());
        JsonNode mode = patch.get("types").get(0).get("modes").get(0);
        assertEquals(List.of("Pan", "Tilt", "Shutter1"), ShowFiles.texts(mode.get("attributes")));
        assertEquals(List.of("Shutter1", "Shutter1Strobe"), ShowFiles.texts(mode.get("functions")), "channel functions, without NoFeature");
        assertEquals("Mode 1", patch.get("fixtures").get(0).get("mode").asText());
        assertTrue(Files.exists(tmp.resolve("export_patch_fixturetype_1.xml")));
    }

    @Test
    void pluginCrashIsLocatedByTheBeginRecord() throws Exception {
        Run r = run(ShowCompiler.compileInspect(ParityTest.settings()), Map.of("MOCK_CRASH", "Fixture"));
        assertEquals(3, r.exit());
        assertTrue(r.records().stream().noneMatch(x -> x.get("ev").asText().equals("end")));
        JsonNode last = r.records().get(r.records().size() - 1);
        assertEquals("begin", last.get("ev").asText());
        assertEquals("Fixture 1 Thru 9999", last.get("addr").asText());
    }
}
