package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real strobes and the speed master only where it makes sense. Sources: grandMA3's predefined "Strobe" phaser
 * runs at its own 10 Hz (predefined_phaser.xml, Speed 167772160 = 10 x 2^24). Strobe channel functions cannot be
 * set on the command line (`Attribute "Shutter1Strobe" At 12` fails on onPC 2.3.2), so the plugin's "strobe" step
 * sets each selected fixture's own strobe function in Hz.
 */
class StrobeAndSpeedTest {

    @TempDir
    Path tmp;

    /** Two moving heads with a shutter strobe, two pars with a strobe-frequency channel, two plain pars. */
    static ObjectNode rig() {
        ObjectNode rig = ShowFiles.object().put("format", "ma3sc-rig").put("version", 1);
        ArrayNode fx = rig.putArray("fixtures");
        Object[][] rows = {{101, "Spot", "Shutter1Strobe"}, {102, "Spot", "Shutter1Strobe"}, {301, "QCL", "StrobeFrequency"},
                {302, "QCL", "StrobeFrequency"}, {201, "Par", null}, {202, "Par", null}};
        int x = 0;
        for (Object[] r : rows) {
            ObjectNode f = fx.addObject().put("fid", (Integer) r[0]).put("name", "F").put("type", (String) r[1]);
            f.putArray("pos").add(x).add(1000.0).add(3000.0);
            x += 1000;
            ArrayNode caps = f.putArray("caps").add("dimmer").add("color_mix");
            if (r[0].equals(101) || r[0].equals(102)) caps.add("pan").add("tilt");
            if (r[2] != null) {
                caps.add("strobe");
                f.putArray("functions").add("Dimmer").add((String) r[2]);
            }
        }
        ObjectNode sets = rig.putObject("sets");
        sets.putArray("all").add(101).add(102).add(301).add(302).add(201).add(202);
        ObjectNode o = rig.putObject("orderings");
        ObjectNode all = o.putObject("all");
        all.set("left_to_right", sets.get("all"));
        return rig;
    }

    static ObjectNode group(ArrayNode groups, String key, int... fids) {
        ObjectNode g = groups.addObject().put("key", key).put("name", "SC " + key);
        ArrayNode a = g.putObject("fixtures").putArray("fids");
        for (int f : fids) a.add(f);
        return g;
    }

    static ObjectNode seq(ArrayNode seqs, String key, String... lineJson) throws Exception {
        ObjectNode s = seqs.addObject().put("key", key).put("name", "SC " + key);
        ArrayNode lines = s.putArray("cues").addObject().put("name", "C").putArray("lines");
        for (String l : lineJson) lines.add(ShowFiles.JSON.readTree(l));
        return s;
    }

    static void look(ArrayNode looks, String key, String role) {
        looks.addObject().put("key", key).put("sequence", key).put("role", role).put("name", key);
    }

    static ObjectNode plan() throws Exception {
        ObjectNode plan = ShowFiles.object().put("format", "ma3sc-plan").put("version", 1);
        ArrayNode groups = plan.putArray("groups"), seqs = plan.putArray("sequences"), looks = plan.putArray("looks");
        group(groups, "heads", 101, 102);
        group(groups, "strobers", 101, 102, 301, 302);
        group(groups, "plain", 201, 202);
        seq(seqs, "base", "{\"group\": \"plain\", \"values\": {\"dimmer\": 60}}").put("speed_scale", "Div2");
        seq(seqs, "move", "{\"group\": \"heads\", \"values\": {\"tilt\": 45}}", "{\"group\": \"heads\", \"preset\": \"circle\"}").put("speed_scale", "Div2");
        seq(seqs, "strobe", "{\"group\": \"strobers\", \"values\": {\"dimmer\": 100, \"strobe\": 12}}", "{\"group\": \"plain\", \"preset\": \"strobe\"}");
        seq(seqs, "mixed", "{\"group\": \"plain\", \"preset\": \"strobe\"}", "{\"group\": \"heads\", \"preset\": \"chase\"}");
        seq(seqs, "move_free", "{\"group\": \"heads\", \"values\": {\"tilt\": 30}}", "{\"group\": \"heads\", \"preset\": \"circle\"}").put("speed_master", false);
        seq(seqs, "dim_strobe", "{\"group\": \"strobers\", \"preset\": \"strobe\"}");
        for (String k : List.of("base", "move", "strobe", "mixed", "move_free", "dim_strobe")) {
            look(looks, k, k.contains("strobe") ? "STROBE" : k.startsWith("move") ? "MOVEMENT" : "BASE");
        }
        return plan;
    }

    static List<String> commands(ShowCompiler.Job job) {
        return job.steps().stream().filter(s -> "cmd".equals(s.get("op"))).map(s -> (String) s.get("cmd")).toList();
    }

    @Test
    void speedMasterOnlyOnSequencesWithABeatEffect() throws Exception {
        ShowCompiler.Compiled c = ShowCompiler.compileBuild(plan(), rig(), EffectLibrary.bundled(), ParityTest.settings(), List.of());
        List<String> cmds = commands(c.job());
        Map<String, Integer> seq = c.allocation().sequences();
        java.util.function.Function<String, Boolean> linked = k -> cmds.contains("Set Sequence " + seq.get(k) + " Property \"SpeedMaster\" \"Speed1\"");
        assertFalse(linked.apply("base"), "static look: nothing to time");
        assertTrue(linked.apply("move"), "movement follows the beat");
        assertFalse(linked.apply("strobe"), "strobes keep their own speed");
        assertTrue(linked.apply("mixed"), "a beat effect in the sequence links it (the check warns)");
        assertFalse(linked.apply("move_free"), "the plan said no");
        assertFalse(linked.apply("dim_strobe"));
        assertTrue(cmds.contains("Set Sequence " + seq.get("move") + " Property \"SpeedScale\" \"Div2\""));
        assertFalse(cmds.contains("Set Sequence " + seq.get("base") + " Property \"SpeedScale\" \"Div2\""), "no speed scale without the speed master");
    }

    @Test
    void realStrobeUsesEachFixturesOwnStrobeFunction() throws Exception {
        ShowSettings s = ParityTest.settings("2.3.2", "recipe", Map.of());
        List<Map<String, Object>> steps = ShowCompiler.compileBuild(plan(), rig(), EffectLibrary.bundled(), s, List.of()).job().steps();
        int i = -1;
        for (int k = 0; k < steps.size(); k++) if ("Attribute \"Dimmer\" At 100".equals(steps.get(k).get("cmd"))) i = k;
        assertTrue(i > 0, steps.toString());
        Map<String, Object> strobe = steps.get(i + 1);
        assertEquals("strobe", strobe.get("op"));
        assertEquals(List.of("Shutter1Strobe", "StrobeFrequency"), strobe.get("functions"), "only functions the group has, in library order");
        assertEquals(12.0, strobe.get("hz"));
        assertEquals(true, strobe.get("fatal"));
        assertTrue(commands(ShowCompiler.compileBuild(plan(), rig(), EffectLibrary.bundled(), s, List.of()).job()).stream()
                .noneMatch(c -> c.contains("Strobe")), "no strobe function on the command line: the console rejects it");
    }

    @Test
    void theCheckWarnsAboutSlowStrobesUselessSpeedScaleAndUnusedStrobeChannels() throws Exception {
        List<PlanValidator.Issue> issues = PlanValidator.validate(plan(), rig(), EffectLibrary.bundled(), ParityTest.settings(), null);
        assertFalse(PlanValidator.hasErrors(issues), issues.toString());
        List<String> codes = issues.stream().map(i -> i.code() + " " + i.path()).toList();
        assertTrue(codes.contains("speed_scale_unused $.sequences[0].speed_scale"), codes.toString());
        assertTrue(codes.contains("strobe_on_speed_master $.sequences[3]"), codes.toString());
        assertTrue(codes.contains("strobe_channel $.looks[5]"), codes.toString());
        assertEquals(3, issues.size(), "nothing about the proper strobe look: " + codes);
        PlanValidator.Issue ch = issues.stream().filter(i -> i.code().equals("strobe_channel")).findFirst().orElseThrow();
        assertTrue(ch.message().contains("[101, 102, 301, 302]"), ch.message());

        // the strobe value needs a strobe channel
        ObjectNode bad = plan();
        ((ObjectNode) bad.get("sequences").get(2).get("cues").get(0).get("lines").get(0)).put("group", "plain");
        List<PlanValidator.Issue> e = PlanValidator.validate(bad, rig(), EffectLibrary.bundled(), ParityTest.settings(), null);
        assertTrue(e.stream().anyMatch(x -> x.code().equals("capability") && x.message().contains("have no strobe")), e.toString());
    }

    @Test
    void rigKnowsWhichFixturesCanStrobeFromTheirChannelFunctions() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("p"));
        ArrayNode fixtures = ShowFiles.array().add(RigTest.fx(101, -1, 2, 3, "Spot")).add(RigTest.fx(102, 1, 2, 3, "Spot"))
                .add(RigTest.fx(301, 0, 4, 3, "QCL"));
        ArrayNode types = ShowFiles.array();
        ObjectNode spot = types.addObject().put("name", "Spot");
        ObjectNode mode = spot.putArray("modes").addObject().put("name", "Std");
        mode.putArray("attributes").add("Dimmer").add("Shutter1");
        mode.putArray("functions").add("Dimmer").add("Shutter1").add("Shutter1Strobe");
        // the QCL type: functions only in its exported XML (an older plugin, or an unprotected type)
        types.addObject().put("name", "QCL").put("file", "qcl.xml").putArray("modes").addObject().put("name", "Std")
                .putArray("attributes").add("Dimmer").add("StrobeRate");
        Files.writeString(dir.resolve("qcl.xml"), "<GMA3><FixtureType><DMXModes><DMXMode Name=\"Std\"><DMXChannels>"
                + "<DMXChannel><LogicalChannel Attribute=\"StrobeRate\"><ChannelFunction Attribute=\"NoFeature\"/>"
                + "<ChannelFunction Attribute=\"StrobeFrequency\"/></LogicalChannel></DMXChannel></DMXChannels></DMXMode></DMXModes></FixtureType></GMA3>");
        Path log = new RigTest().patchLogIn(dir, fixtures, types);
        JsonNode rig = Rig.build(Rig.readPatchLog(log), null, "-y", null).rig();
        Map<Integer, JsonNode> fx = Rig.fixturesById(rig);
        assertTrue(ShowFiles.texts(fx.get(101).get("caps")).contains("strobe"));
        assertEquals(List.of("Dimmer", "Shutter1", "Shutter1Strobe"), ShowFiles.texts(fx.get(101).get("functions")));
        assertEquals(List.of("StrobeFrequency"), ShowFiles.texts(fx.get(301).get("functions")), "from the XML, without NoFeature");
        assertTrue(ShowFiles.texts(fx.get(301).get("caps")).contains("strobe"));
        assertEquals(List.of(101, 301, 102), ShowFiles.ints(rig.get("sets").get("cap:strobe")), "left to right");
        assertEquals(List.of(), SchemaCheck.validate(rig, SchemaCheck.load("rig")));
    }
}
