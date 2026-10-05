package automa3.show;

import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.ma3.ConsoleHub;
import automa3.ma3.OscCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows on disk and the whole console round trip without grandMA3: a fake console receives the OSC command and
 * runs the real ShowBuilder plugin in Lua against the console mock, which writes the log AutoMA3 waits for.
 */
class ShowServiceTest {

    @TempDir
    Path tmp;
    ConfigStore store;
    ConsoleHub hub;
    ShowService service;
    DatagramSocket console;
    final List<String> received = new CopyOnWriteArrayList<>();
    Thread consoleThread;
    Path ma3Root;

    @BeforeEach
    void setUp() throws Exception {
        console = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        ma3Root = Files.createDirectories(tmp.resolve("MALightingTechnology"));
        Files.createDirectories(ma3Root.resolve("gma3_library"));
        Files.createDirectories(ma3Root.resolve("gma3_2.3.2"));
        Path data = Files.createDirectories(tmp.resolve("data"));
        store = new ConfigStore(data.resolve("config.json"));
        Config c = ConfigStore.copy(store.get());
        c.consoles.get(0).host = "127.0.0.1";
        c.consoles.get(0).port = console.getLocalPort();
        c.consoles.get(0).version = "2.3";
        c.showCreator.ma3Folder = ma3Root.toString();
        store.replace(c);
        hub = new ConsoleHub(store);
        service = new ShowService(store, data, hub);
    }

    @AfterEach
    void tearDown() {
        console.close();
    }

    /** The fake grandMA3: runs `Plugin "ShowBuilder" "<job>"` with the Lua mock, like the console would. */
    void startConsole() {
        String lua = ShowCreatorUnitTest.lua();
        Assumptions.assumeTrue(lua != null, "no lua interpreter installed");
        Pattern plugin = Pattern.compile("^Plugin \"ShowBuilder\" \"(.+)\"$");
        consoleThread = new Thread(() -> {
            byte[] buf = new byte[65536];
            while (!console.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    console.receive(p);
                    for (OscCodec.Message m : OscCodec.decode(p.getData(), p.getLength())) {
                        String cmd = m.str(0);
                        received.add(m.address() + " " + cmd);
                        Matcher mm = plugin.matcher(cmd);
                        if (mm.matches()) {
                            Path mock = Path.of(getClass().getResource("/showcreator/lua/mock_ma3.lua").toURI());
                            Path pluginLua = ma3Root.resolve("gma3_library/datapools/plugins/ShowBuilder.lua");
                            new ProcessBuilder(lua, mock.toString(), pluginLua.toString(), mm.group(1)).redirectErrorStream(true)
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                        }
                    }
                } catch (Exception e) {
                    return;
                }
            }
        }, "fake-console");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    void putShowFiles(String name) throws Exception {
        Files.copy(ParityTest.golden("auto_ma3_test.plan.json"), service.dir(name).resolve("plan.json"));
        Files.copy(ParityTest.golden("auto_ma3_test.rig.json"), service.dir(name).resolve("rig.json"));
    }

    @Test
    void showsAreCreatedCopiedRenamedAndDeletedWithoutLosingAnything() throws Exception {
        assertNull(service.current());
        service.create("club");
        putShowFiles("club");
        Files.createDirectories(service.dir("club").resolve("build"));
        Files.writeString(service.dir("club").resolve("build/planner_session.json"), "{\"session_id\": \"abc\"}");
        assertEquals("club", service.current());

        service.copy("club", "club-hard");
        assertEquals("club-hard", service.current(), "the copy is selected");
        assertTrue(Files.exists(service.dir("club-hard").resolve("plan.json")));
        assertTrue(Files.exists(service.dir("club-hard").resolve("rig.json")));
        assertFalse(Files.exists(service.dir("club-hard").resolve("build")), "build results and the planner conversation stay with the original");
        assertEquals(Files.readString(service.dir("club").resolve("plan.json")), Files.readString(service.dir("club-hard").resolve("plan.json")));
        Files.writeString(service.dir("club-hard").resolve("plan.json"), "{}");
        assertNotEquals("{}", Files.readString(service.dir("club").resolve("plan.json")), "changing the copy leaves the original");

        assertThrows(ShowException.class, () -> service.copy("club", "club-hard"), "names are unique");
        assertThrows(ShowException.class, () -> service.create("no spaces"), "names are safe file names");
        assertThrows(ShowException.class, () -> service.create("../evil"));
        assertThrows(ShowException.class, () -> service.dir("../data"));

        service.rename("club-hard", "warehouse");
        assertEquals(List.of("club", "warehouse"), service.shows());
        assertEquals("warehouse", service.current());
        service.delete("warehouse");
        assertEquals(List.of("club"), service.shows());
        assertEquals("club", service.current());
        try (var s = Files.list(service.showsDir().resolve(".deleted"))) {
            assertEquals(1, s.count(), "deleted shows are kept in shows/.deleted");
        }
    }

    @Test
    void settingsComeFromTheConsolesAndTheInstalledOnPC() throws Exception {
        ShowSettings s = service.settings();
        assertEquals("2.3.2", s.ma3Version(), "2.3 completed from the installed onPC");
        assertEquals(ma3Root.resolve("gma3_library/datapools/plugins/showcreator"), s.exchangeDir());
        assertEquals(new ShowSettings.Range(101, 199), s.executors());

        Config c = ConfigStore.copy(store.get());
        c.consoles.get(0).host = "192.168.1.20"; // a real console, not this Mac
        store.replace(c);
        ShowException e = assertThrows(ShowException.class, service::settings);
        assertTrue(e.getMessage().contains("onPC on this Mac"));
        JsonNode status = service.status();
        assertTrue(status.path("ma3").has("error"), "status reports instead of failing");
    }

    @Test
    void readPatchPlanCheckAndBuildThroughTheConsole() throws Exception {
        startConsole();
        service.create("club");

        // read the patch: job -> OSC -> plugin -> log -> rig
        ShowService.Deployed patch = service.deploy("club", "patch");
        List<String> sent = service.trigger(patch);
        assertEquals(patch.command(), sent.get(sent.size() - 1));
        ShowService.LogSummary log = service.waitLog("club", "patch", patch.compiled().jobId(), 20_000, 10_000, s -> { }, () -> false);
        assertEquals("ok", log.state());
        assertTrue(Files.exists(service.dir("club").resolve("build/ma3/log_patch.jsonl")), "log kept with the show");
        assertEquals("Mock Spot", log.steps().get(0).get("types").get(0).get("name").asText(), "the mock's patch arrived");
        service.buildRig("club"); // the mock's fixtures have no numeric ids: an empty rig, but no failure
        assertTrue(Files.exists(service.dir("club").resolve("rig.json")));
        assertTrue(received.stream().anyMatch(r -> r.startsWith("/cmd Plugin \"ShowBuilder\"")));

        // build a real plan
        putShowFilesReplacingRig("club");
        ShowService.Deployed build = service.deploy("club", "build");
        assertTrue(build.compiled().steps() > 500);
        service.trigger(build);
        ShowService.LogSummary built = service.waitLog("club", "build", build.compiled().jobId(), 60_000, 20_000, s -> { }, () -> false);
        assertEquals("ok", built.state(), String.valueOf(built.failed()));
        assertEquals(build.compiled().steps(), built.done());
        assertTrue(Files.exists(service.dir("club").resolve("build/looks.out.json")));
        assertTrue(Files.exists(service.dir("club").resolve("build/ma3/export_build_sequence_501.xml")));
        assertTrue(Files.exists(ma3Root.resolve("gma3_library/datapools/plugins/ShowBuilder.lua")), "plugin installed");
    }

    void putShowFilesReplacingRig(String name) throws Exception {
        Files.copy(ParityTest.golden("auto_ma3_test.plan.json"), service.dir(name).resolve("plan.json"));
        Files.copy(ParityTest.golden("auto_ma3_test.rig.json"), service.dir(name).resolve("rig.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void dryRunSendsNothing() throws Exception {
        service.create("club");
        ShowService.Deployed d = service.deploy("club", "patch");
        hub.setDryRun(true);
        ShowException e = assertThrows(ShowException.class, () -> service.trigger(d));
        assertTrue(e.getMessage().contains("dry run"));
        assertTrue(e.getMessage().contains(d.command()), "with the command to paste instead");
    }

    @Test
    void aPlanWithErrorsIsNotBuilt() throws Exception {
        service.create("club");
        putShowFiles("club");
        Files.writeString(service.dir("club").resolve("plan.json"),
                Files.readString(service.dir("club").resolve("plan.json")).replaceFirst("\"preset\": \"[a-z_]+\"", "\"preset\": \"laser_show\""));
        ShowException e = assertThrows(ShowException.class, () -> service.compile("club", "build"));
        assertTrue(e.getMessage().contains("errors"));
        assertTrue(PlanValidator.hasErrors(service.validate("club").issues()));
        assertTrue(Files.exists(service.dir("club").resolve("build/validation.json")));
    }

    @Test
    void briefAndLookListsForThePlanner() throws Exception {
        service.create("club");
        putShowFiles("club");
        assertFalse(service.looksIn("club").custom());
        String brief = Files.readString(service.writeBrief("club"));
        assertTrue(brief.startsWith("# Planning brief: club"));
        assertTrue(brief.contains("`shows/club/plan.json`"));
        assertEquals(store.get().looks.size(), service.looksFromAutoMA3("club"));
        assertTrue(service.looksIn("club").custom());
        service.useDefaultLooks("club");
        assertFalse(service.looksIn("club").custom());
        assertThrows(ShowException.class, () -> service.saveLooksIn("club", ShowFiles.object().put("format", "x")));
    }
}
