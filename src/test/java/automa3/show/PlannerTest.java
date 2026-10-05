package automa3.show;

import automa3.config.Config;
import automa3.config.ConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The planner run with a stand-in for Claude Code: a script that answers in Claude Code's stream-json format and
 * runs the planner's ./sc commands, as the real planner does.
 */
class PlannerTest {

    @TempDir
    Path tmp;
    ConfigStore store;
    ShowService service;
    Planner planner;

    @BeforeEach
    void setUp() throws Exception {
        Path ma3 = Files.createDirectories(tmp.resolve("MALightingTechnology/gma3_2.3.2"));
        Path data = Files.createDirectories(tmp.resolve("data"));
        store = new ConfigStore(data.resolve("config.json"));
        Config c = ConfigStore.copy(store.get());
        c.consoles.get(0).version = "2.3";
        c.showCreator.ma3Folder = ma3.getParent().toString();
        store.replace(c);
        service = new ShowService(store, data, null);
        planner = new Planner(service);
        service.create("club");
        Files.copy(ParityTest.golden("auto_ma3_test.rig.json"), service.dir("club").resolve("rig.json"));
    }

    /** A fake claude: prints a session, a reply, a tool call, runs ./sc validate and copies a plan into place. */
    Path fakeClaude(String planSource, int exit) throws Exception {
        Path script = tmp.resolve("claude");
        String result = "{\"type\":\"result\",\"result\":\"Plan written.\",\"total_cost_usd\":0.12,\"num_turns\":3,\"is_error\":false}";
        Files.writeString(script, "#!/bin/sh\n"
                + "echo \"$@\" > args.txt\n"
                + "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-1\"}'\n"
                + "echo '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}}'\n"
                + "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Looking at the rig.\"}]}}'\n"
                + "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"./sc validate\"}}]}}'\n"
                + "cp " + sh(planSource.toString()) + " ../shows/$SHOWCREATOR_SHOW/plan.json\n"
                + "./sc validate > validate.out 2>&1; echo $? > validate.exit\n"
                + "echo 'not json'\n"
                + "echo '" + result + "'\n"
                + "exit " + exit + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Config c = ConfigStore.copy(store.get());
        c.showCreator.claude = script.toString();
        store.replace(c);
        return script;
    }

    static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    ShowTasks.Task task() {
        return new ShowTasks.Task("plan", "club");
    }

    List<String> lines(ShowTasks.Task t) {
        List<String> out = new ArrayList<>();
        t.view(0).get("lines").forEach(l -> out.add(l.get("level").asText() + ": " + l.get("text").asText()));
        return out;
    }

    @Test
    void plannerRunsWithItsOwnScAndKeepsTheSession() throws Exception {
        fakeClaude(ParityTest.golden("auto_ma3_test.plan.json").toString(), 0);
        ShowTasks.Task t = task();
        Planner.Result r = planner.run("club", t, "Plan the show.", false);
        assertEquals("Plan written.", r.text());
        assertEquals(0.12, r.costUsd());
        assertEquals("sess-1", planner.lastSession("club"));
        List<String> l = lines(t);
        assertTrue(l.contains("say: Looking at the rig."), l.toString());
        assertTrue(l.contains("tool: $ ./sc validate"), l.toString());
        assertTrue(l.contains("debug: not json"), l.toString());

        Path dir = service.dataDir().resolve("planner");
        assertTrue(Files.exists(dir.resolve("CLAUDE.md")));
        assertTrue(Files.exists(dir.resolve(".claude/settings.json")));
        String args = Files.readString(dir.resolve("args.txt"));
        assertTrue(args.contains("--permission-mode acceptEdits"));
        assertTrue(args.contains("--allowedTools Bash(./sc brief) Bash(./sc validate) Bash(./sc rig) --disallowedTools"), args);
        assertTrue(args.contains("--add-dir " + service.showsDir()));
        assertFalse(args.contains("--resume"));
        assertEquals("0", Files.readString(dir.resolve("validate.exit")).strip(), Files.readString(dir.resolve("validate.out")));
        assertTrue(Files.readString(dir.resolve("validate.out")).contains("OK: 0 error(s)"));

        // a follow-up continues the conversation
        planner.run("club", task(), "Make the movement slower.", true);
        assertTrue(Files.readString(dir.resolve("args.txt")).contains("--resume sess-1"));
    }

    @Test
    void scReportsPlanErrorsWithExitCode1() throws Exception {
        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, Files.readString(ParityTest.golden("auto_ma3_test.plan.json")).replaceFirst("\"preset\": \"[a-z_]+\"", "\"preset\": \"laser\""));
        fakeClaude(bad.toString(), 0);
        planner.run("club", task(), "Plan.", false);
        Path dir = service.dataDir().resolve("planner");
        assertEquals("1", Files.readString(dir.resolve("validate.exit")).strip());
        assertTrue(Files.readString(dir.resolve("validate.out")).contains("unknown_preset"));
    }

    @Test
    void failuresAndMissingClaudeAreReported() throws Exception {
        fakeClaude(ParityTest.golden("auto_ma3_test.plan.json").toString(), 3);
        ShowException e = assertThrows(ShowException.class, () -> planner.run("club", task(), "Plan.", false));
        assertTrue(e.getMessage().contains("exit 3"));

        Config c = ConfigStore.copy(store.get());
        c.showCreator.claude = tmp.resolve("nowhere/claude").toString();
        store.replace(c);
        e = assertThrows(ShowException.class, () -> planner.run("club", task(), "Plan.", false));
        assertTrue(e.getMessage().contains("Claude Code not found"));
        assertNull(Planner.findClaude(tmp.resolve("nowhere/claude").toString()));
    }

    @Test
    void debuggerHooksAndIdeVariablesDoNotReachClaudeCode() {
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of(
                "NODE_OPTIONS", " --require \"/Applications/Visual Studio Code.app/.../bootloader.js\"",
                "VSCODE_INSPECTOR_OPTIONS", "x", "CLAUDECODE", "1", "CLAUDE_CODE_SSE_PORT", "64667", "CLAUDE_CODE_ENTRYPOINT", "cli",
                "PATH", "/usr/bin", "ANTHROPIC_API_KEY", "k", "CLAUDE_CODE_USE_BEDROCK", "1", "HTTPS_PROXY", "p"));
        Planner.cleanEnvironment(env);
        assertEquals(java.util.Set.of("PATH", "ANTHROPIC_API_KEY", "CLAUDE_CODE_USE_BEDROCK", "HTTPS_PROXY"), env.keySet(),
                "login, proxy and PATH stay");
    }

    @Test
    void aSilentFailureSaysWhatToCheck() throws Exception {
        Path script = tmp.resolve("claude");
        Files.writeString(script, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Config c = ConfigStore.copy(store.get());
        c.showCreator.claude = script.toString();
        store.replace(c);
        ShowException e = assertThrows(ShowException.class, () -> planner.run("club", task(), "Plan.", false));
        assertTrue(e.getMessage().contains("stopped without a message"), e.getMessage());
    }

    @Test
    void streamTrackerShowsWhatThePlannerDoes() throws Exception {
        ShowTasks.Task t = task();
        Planner.StreamTracker tr = new Planner.StreamTracker(t);
        tr.event(json("{\"type\":\"system\",\"subtype\":\"status\",\"status\":\"requesting\"}"));
        assertEquals("Waiting for Claude…", activity(t).get("label").asText());
        tr.event(json("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"name\":\"Write\"}}}"));
        tr.event(json("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"file_path\\\": \\\"../shows/club/plan.json\\\", \\\"content\\\": \\\"{\\\\\\\"cues\\\\\\\": [], \\\\\\\"role\\\\\\\": 1\"}}}"));
        JsonNode a = activity(t);
        assertEquals("Writing plan.json", a.get("label").asText());
        assertTrue(a.get("detail").asText().contains("1 sequences · 1 looks so far"), a.toString());
        tr.event(json("{\"type\":\"user\"}"));
        assertEquals("Looking at the result…", activity(t).get("label").asText());
    }

    static JsonNode json(String s) throws Exception {
        return ShowFiles.JSON.readTree(s);
    }

    static JsonNode activity(ShowTasks.Task t) {
        return t.view(0).get("activity");
    }
}
