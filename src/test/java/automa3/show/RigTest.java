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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The Python show creator's rig tests (tests/test_rig.py), ported. */
class RigTest {

    @TempDir
    Path tmp;

    static final String SCENE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <GeneralSceneDescription verMajor="1" verMinor="5">
             <Scene><Layers>
              <Layer uuid="L1" name="Rig">
               <ChildList>
                <SceneObject uuid="S1" name="Truss"><Matrix>{1,0,0}{0,1,0}{0,0,1}{0,0,0}</Matrix></SceneObject>
                <GroupObject uuid="G1" name="Fixtures">
                 <Matrix>{1,0,0}{0,1,0}{0,0,1}{100,200,3000}</Matrix>
                 <ChildList>
                  <Fixture uuid="F1" name="Spot"><Matrix>{0,-1,0}{1,0,0}{0,0,1}{1000,0,0}</Matrix>
                    <Classing>C1</Classing><GDTFSpec>X@Spot</GDTFSpec><GDTFMode>Std</GDTFMode><FixtureID>101</FixtureID></Fixture>
                  <Fixture uuid="F2" name="Spot"><Matrix>{1,0,0}{0,1,0}{0,0,1}{-1000,0,0}</Matrix>
                    <GDTFSpec>X@Spot</GDTFSpec><GDTFMode>Std</GDTFMode><FixtureID>102</FixtureID></Fixture>
                  <Fixture uuid="F3" name="NoId"><FixtureID></FixtureID></Fixture>
                 </ChildList>
                </GroupObject>
               </ChildList>
              </Layer>
             </Layers></Scene>
             <AUXData><Class uuid="C1" name="Spots"/></AUXData>
            </GeneralSceneDescription>""".strip();

    Path mvrFile() throws Exception {
        Path p = tmp.resolve("patch.mvr");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            z.putNextEntry(new ZipEntry("GeneralSceneDescription.xml"));
            z.write(SCENE.getBytes());
            z.closeEntry();
        }
        return p;
    }

    @Test
    void mvrComposesParentMatrices() throws Exception {
        Map<Integer, Rig.MvrFixture> fx = new java.util.HashMap<>();
        for (Rig.MvrFixture f : Rig.readMvr(mvrFile())) fx.put(f.fid(), f);
        assertEquals(java.util.Set.of(101, 102), fx.keySet()); // fixture without id is skipped
        assertArrayEquals(new double[]{1100.0, 200.0, 3000.0}, fx.get(101).pos());
        assertArrayEquals(new double[]{-900.0, 200.0, 3000.0}, fx.get(102).pos());
        assertEquals("Spots", fx.get(101).cls());
        assertEquals("Rig", fx.get(101).layer());
        assertEquals(List.of("Fixtures"), fx.get(101).groups());
        assertArrayEquals(new double[]{0.0, -1.0, 0.0}, fx.get(101).matrix()[0]);
    }

    @Test
    void composeRotation() {
        double[][] parent = {{0, 1, 0}, {-1, 0, 0}, {0, 0, 1}, {10, 0, 0}}; // rotated 90 deg about z
        double[][] child = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {5, 0, 0}};
        assertArrayEquals(new double[]{10, 5, 0}, Rig.compose(parent, child)[3]);
    }

    /** A patch log in any folder (used by other tests too). */
    Path patchLogIn(Path dir, ArrayNode fixtures, ArrayNode types) throws Exception {
        return patchLog(dir, fixtures, types);
    }

    Path patchLog(Path dir, ArrayNode fixtures, ArrayNode types) throws Exception {
        String start = ShowFiles.object().put("ev", "start").put("job_id", "patch-1").put("ma3", "2.3.2.0").toString();
        ObjectNode step = ShowFiles.object().put("ev", "step").put("op", "patch").put("ok", true);
        step.set("types", types);
        step.set("fixtures", fixtures);
        String end = ShowFiles.object().put("ev", "end").put("ok", true).toString();
        Path p = dir.resolve("log_patch.jsonl");
        Files.writeString(p, String.join("\n", start, step.toString(), end));
        return p;
    }

    static ObjectNode fx(int fid, double x, double y, double z, String t) {
        return ShowFiles.object().put("FID", String.valueOf(fid)).put("Name", "F" + fid).put("fixturetype", t).put("mode", "Std")
                .put("PosX", Py.repr(x)).put("PosY", Py.repr(y)).put("PosZ", Py.repr(z)).put("RotX", "0").put("RotY", "0").put("RotZ", "0")
                .put("Layer", "FixtureLayer 1").put("Class", "FixtureClass 1");
    }

    static ObjectNode type(String name, List<String> attributes, String file) {
        ObjectNode t = ShowFiles.object().put("name", name);
        if (file != null) t.put("file", file);
        ArrayNode attrs = t.putArray("modes").addObject().put("name", "Std").putArray("attributes");
        attributes.forEach(attrs::add);
        return t;
    }

    Path patchLog() throws Exception {
        ArrayNode fixtures = ShowFiles.array();
        fixtures.addObject().put("FID", "1").put("Name", "Fixtures").put("fixturetype", "Grouping").put("mode", "Default");
        fixtures.add(fx(101, -3, 2, 3, "Spot")).add(fx(102, -1, 2, 3, "Spot")).add(fx(103, 1, 2, 3, "Spot")).add(fx(104, 3, 2, 3, "Spot"));
        fixtures.add(fx(201, -2, 0, 4, "Par")).add(fx(202, 2, 0, 4, "Par"));
        ArrayNode types = ShowFiles.array();
        types.add(type("Spot", List.of("Dimmer", "Pan", "Tilt", "ColorRGB_R", "Zoom"), null));
        types.add(type("Par", List.of(), "export_patch_fixturetype_2.xml"));
        Files.writeString(tmp.resolve("export_patch_fixturetype_2.xml"),
                "<GMA3><FixtureType Name=\"Par\"><DMXModes><DMXMode Name=\"Std\"><DMXChannels><DMXChannel>"
                        + "<LogicalChannel Attribute=\"Dimmer\"/></DMXChannel><DMXChannel><LogicalChannel Attribute=\"ColorAdd_R\"/>"
                        + "</DMXChannel></DMXChannels></DMXMode></DMXModes></FixtureType></GMA3>");
        return patchLog(tmp, fixtures, types);
    }

    @Test
    void patchLogReading() throws Exception {
        Rig.Patch p = Rig.readPatchLog(patchLog());
        assertEquals(List.of(101, 102, 103, 104, 201, 202), p.fixtures().stream().map(Rig.PatchFixture::fid).toList()); // grouping dropped
        assertArrayEquals(new double[]{-3000.0, 2000.0, 3000.0}, p.fixtures().get(0).pos()); // m -> mm
        assertEquals(List.of("Dimmer", "ColorAdd_R"), p.types().get("Par").get("Std")); // from the XML export when the walk found none
    }

    static List<Integer> ints(JsonNode n) {
        return ShowFiles.ints(n);
    }

    @Test
    void rigFromConsole() throws Exception {
        Rig.Built b = Rig.build(Rig.readPatchLog(patchLog()), null, "-y", null);
        JsonNode rig = b.rig();
        assertEquals(List.of(), b.warnings());
        assertTrue(SchemaCheck.validate(rig, SchemaCheck.load("rig")).isEmpty());
        Map<Integer, JsonNode> fx = Rig.fixturesById(rig);
        assertEquals(List.of("dimmer", "pan", "tilt", "color_mix", "zoom"), ShowFiles.texts(fx.get(101).get("caps")));
        assertEquals(List.of("dimmer", "color_mix"), ShowFiles.texts(fx.get(201).get("caps")));
        assertEquals(List.of(101, 102, 103, 104), ints(rig.get("sets").get("type:Spot")));
        assertEquals(List.of(101, 102, 103, 104), ints(rig.get("sets").get("cap:pan")));
        JsonNode o = rig.get("orderings").get("type:Spot");
        assertEquals(List.of(104, 103, 102, 101), ints(o.get("right_to_left")));
        assertEquals(List.of(102, 103, 101, 104), ints(o.get("center_out")));
        assertEquals(List.of(101, 104, 102, 103), ints(o.get("outside_in")));
        assertEquals(List.of(201, 202), ints(rig.get("orderings").get("all").get("foh_to_stage")).subList(0, 2));
        assertEquals(List.of(201, 202), ints(rig.get("orderings").get("all").get("top_to_bottom")).subList(0, 2));
        assertEquals("[[101,104],[102,103]]", rig.get("pairs").get("type:Spot").toString());
        assertEquals(List.of(101, 102, 103, 104), ints(o.get("mirror")));
        assertEquals(List.of(101, 201, 102, 103, 202, 104), ints(rig.get("orderings").get("all").get("mirror"))); // pars pair up
        assertFalse(rig.get("sets").has("layer:FixtureLayer 1")); // identical to "all"
    }

    @Test
    void orderingsIgnoreSubGridNoiseAndPairUpInAGrid() throws Exception {
        // 2x2 grid like auto_ma3_test: 103/104 front, 101/102 back, y differs by 0.5 mm
        ArrayNode fixtures = ShowFiles.array().add(fx(101, 1.0201, 2.5173, 3, "Spot")).add(fx(102, 3.8137, 2.5168, 3, "Spot"))
                .add(fx(103, 1.0201, 0.8103, 3, "Spot")).add(fx(104, 3.8137, 0.8098, 3, "Spot"));
        Path log = patchLog(tmp, fixtures, ShowFiles.array().add(type("Spot", List.of("Dimmer"), null)));
        JsonNode o = Rig.build(Rig.readPatchLog(log), null, "-y", null).rig().get("orderings").get("all");
        assertEquals(List.of(103, 101, 104, 102), ints(o.get("left_to_right")));
        assertEquals(List.of(103, 104, 101, 102), ints(o.get("foh_to_stage")));
        assertEquals(List.of(103, 104, 101, 102), ints(o.get("center_out")));
        assertEquals(List.of(103, 101, 102, 104), ints(o.get("mirror"))); // 103/104 and 101/102 are the mirror pairs
    }

    @Test
    void audienceSideFlipsLeftRight() throws Exception {
        assertEquals(List.of(101, 102, 103, 104), ints(Rig.build(Rig.readPatchLog(patchLog()), null, "-y", null).rig()
                .get("orderings").get("type:Spot").get("left_to_right")));
        assertEquals(List.of(104, 103, 102, 101), ints(Rig.build(Rig.readPatchLog(patchLog()), null, "+y", null).rig()
                .get("orderings").get("type:Spot").get("left_to_right")));
    }

    @Test
    void consoleAndMvrMerge() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("merge"));
        Path log = patchLog(dir, ShowFiles.array().add(fx(101, 1.1, 0.2, 3, "Spot")).add(fx(102, -0.5, 0.2, 3, "Spot")),
                ShowFiles.array().add(type("Spot", List.of("Dimmer"), null)));
        Rig.Built b = Rig.build(Rig.readPatchLog(log), Rig.readMvr(mvrFile()), "-y", null);
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("102") && w.contains("differ")));
        for (JsonNode f : b.rig().get("fixtures")) assertEquals("FixtureClass 1", f.get("class").asText());
    }

    @Test
    void mvrOnlyWarnsAboutCapabilities() throws Exception {
        Rig.Built b = Rig.build(null, Rig.readMvr(mvrFile()), "-y", null);
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("capabilities unknown")));
        assertEquals(List.of(102, 101), ints(b.rig().get("sets").get("all")));
    }
}
