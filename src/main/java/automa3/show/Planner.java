package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the planner: Claude Code, headless, in {@code <data folder>/planner/}, turning its stream-json output into
 * task log lines and a live status. The session is kept per show, so a follow-up request ("make the movement
 * slower") continues the same conversation. The planner may only edit files in its folder and in shows/
 * (--permission-mode acceptEdits) and run ./sc brief, ./sc validate and ./sc rig (planner/.claude/settings.json).
 * Optional: without Claude Code everything else in AutoMA3 works.
 */
public final class Planner {

    private final ShowService service;

    public Planner(ShowService service) {
        this.service = service;
    }

    /** The Claude Code executable: the configured command on PATH or at a usual install location, or null. */
    public static Path findClaude(String cmd) {
        if (cmd == null || cmd.isBlank()) cmd = "claude";
        if (cmd.contains("/")) {
            Path p = Path.of(cmd.replaceFirst("^~", System.getProperty("user.home")));
            return Files.isExecutable(p) ? p : null;
        }
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) dirs.addAll(List.of(path.split(File.pathSeparator)));
        String home = System.getProperty("user.home");
        // a double-clicked AutoMA3.command may not have the shell's PATH
        dirs.addAll(List.of(home + "/.claude/local", home + "/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", home + "/.npm-global/bin"));
        for (String d : dirs) {
            if (d.isBlank()) continue;
            Path p = Path.of(d, cmd);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    Path plannerDir() {
        return service.dataDir().resolve("planner");
    }

    /**
     * Writes the planner's folder: its instructions, permissions and the ./sc script that runs AutoMA3's show
     * commands with this very Java and AutoMA3 (so the planner always matches the running version).
     */
    void prepareFolder() throws ShowException {
        Path dir = plannerDir();
        try {
            Files.createDirectories(dir.resolve(".claude"));
            Files.write(dir.resolve("CLAUDE.md"), ShowFiles.resourceBytes("planner/CLAUDE.md"));
            Files.write(dir.resolve(".claude/settings.json"), ShowFiles.resourceBytes("planner/settings.json"));
            String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            List<String> cp = new ArrayList<>();
            for (String part : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (!part.isBlank()) cp.add(Path.of(part).toAbsolutePath().toString());
            }
            String script = "#!/bin/sh\n"
                    + "# AutoMA3's show creator commands for the planner: ./sc brief, ./sc validate, ./sc rig\n"
                    + "# (the show is taken from SHOWCREATOR_SHOW). Written by AutoMA3 on every planner start.\n"
                    + "exec " + sh(java) + " -Djava.awt.headless=true -cp " + sh(String.join(File.pathSeparator, cp))
                    + " automa3.Main show \"$@\" --config " + sh(service.store().path().toAbsolutePath().toString()) + "\n";
            Path sc = dir.resolve("sc");
            Files.writeString(sc, script);
            try {
                Files.setPosixFilePermissions(sc, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        } catch (IOException e) {
            throw new ShowException("cannot prepare the planner folder " + dir + ": " + e.getMessage());
        }
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Path sessionFile(String show) throws ShowException {
        return service.buildDir(show).resolve("planner_session.json");
    }

    public String lastSession(String show) {
        try {
            Path p = sessionFile(show);
            return Files.exists(p) ? ShowFiles.read(p, "planner session").path("session_id").asText(null) : null;
        } catch (ShowException e) {
            return null;
        }
    }

    /**
     * The planner's permissions are passed on the command line: Claude Code ignores a folder's .claude/settings.json
     * until someone has opened that folder interactively and trusted it.
     */
    static final List<String> ALLOWED_TOOLS = List.of("Bash(./sc brief)", "Bash(./sc validate)", "Bash(./sc rig)");

    List<String> command(Path claude, String prompt, String sessionId) {
        List<String> cmd = new ArrayList<>(List.of(claude.toString(), "-p", prompt, "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--permission-mode", "acceptEdits", "--add-dir", service.showsDir().toString()));
        cmd.add("--allowedTools");
        cmd.addAll(ALLOWED_TOOLS);
        cmd.addAll(List.of("--disallowedTools", "WebFetch", "WebSearch"));
        if (sessionId != null) cmd.addAll(List.of("--resume", sessionId));
        return cmd;
    }

    /**
     * Variables that must not reach Claude Code: a debugger hook in NODE_OPTIONS (VS Code's run configurations add
     * one) makes it quit at once without a message, and the IDE / nested-session variables of a Claude Code running
     * around AutoMA3 would tie the planner to that session. Everything else (login, proxy, PATH) is kept.
     */
    static final List<String> REMOVED_ENV = List.of("NODE_OPTIONS", "VSCODE_INSPECTOR_OPTIONS", "CLAUDECODE",
            "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_ENTRYPOINT");

    static void cleanEnvironment(Map<String, String> env) {
        REMOVED_ENV.forEach(env::remove);
    }

    private static final Pattern FILE_PATH = Pattern.compile("\"file_path\"\\s*:\\s*\"([^\"]+)\"");

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String toolSummary(String name, JsonNode inp) {
        return switch (name) {
            case "Bash" -> "$ " + inp.path("command").asText("");
            case "Read", "Write", "Edit" -> name + " " + inp.path("file_path").asText("");
            default -> name;
        };
    }

    private static String runningLabel(String name, JsonNode inp) {
        return switch (name) {
            case "Bash" -> "Running " + inp.path("command").asText("");
            case "Write", "Edit" -> "Saving " + fileName(inp.path("file_path").asText(""));
            case "Read" -> "Reading " + fileName(inp.path("file_path").asText(""));
            default -> "Using " + name;
        };
    }

    /**
     * Turns Claude Code's partial stream events into the task's live activity. The thinking text itself is not in
     * the stream (only its start), so the planner's thinking shows as a phase, not as text.
     */
    static final class StreamTracker {
        private final ShowTasks.Task task;
        private final Map<Integer, String[]> blocks = new HashMap<>(); // index -> {type, name, buffer}
        private String summary = "";

        StreamTracker(ShowTasks.Task task) {
            this.task = task;
        }

        private void set(String phase, String label, String detail) {
            task.activity(phase, label, detail == null || detail.isEmpty() ? summary : detail, null);
        }

        void streamEvent(JsonNode ev) {
            String kind = ev.path("type").asText();
            int index = ev.path("index").asInt(0);
            if (kind.equals("content_block_start")) {
                JsonNode cb = ev.path("content_block");
                String type = cb.path("type").asText();
                blocks.put(index, new String[]{type, cb.path("name").asText(""), ""});
                switch (type) {
                    case "thinking" -> set("thinking", "Thinking…", "");
                    case "text" -> set("replying", "Writing a reply…", "");
                    case "tool_use" -> set("writing", "Preparing " + cb.path("name").asText("a tool call") + "…", "");
                    default -> {
                    }
                }
            } else if (kind.equals("content_block_delta")) {
                String[] b = blocks.get(index);
                JsonNode d = ev.path("delta");
                if (b == null) return;
                if ("text_delta".equals(d.path("type").asText())) {
                    b[2] += d.path("text").asText("");
                    set("replying", "Writing a reply…", b[2].trim().split("\\s+").length + " words");
                } else if ("input_json_delta".equals(d.path("type").asText())) {
                    b[2] += d.path("partial_json").asText("");
                    Matcher m = FILE_PATH.matcher(b[2]);
                    if ((b[1].equals("Write") || b[1].equals("Edit")) && m.find()) {
                        String name = fileName(m.group(1));
                        String detail = String.format(java.util.Locale.ROOT, "%.1f KB", b[2].length() / 1024.0);
                        if (name.equals("plan.json")) {
                            detail += " · " + count(b[2], "\\\"cues\\\"") + " sequences · " + count(b[2], "\\\"role\\\"") + " looks so far";
                        }
                        set("writing", "Writing " + name, detail);
                    }
                }
            }
        }

        private static int count(String s, String needle) {
            int n = 0;
            for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
            return n;
        }

        void event(JsonNode ev) {
            String t = ev.path("type").asText();
            switch (t) {
                case "stream_event" -> streamEvent(ev.path("event"));
                case "system" -> {
                    if ("status".equals(ev.path("subtype").asText()) && "requesting".equals(ev.path("status").asText())) {
                        set("waiting", "Waiting for Claude…", "");
                    } else if ("task_summary".equals(ev.path("subtype").asText()) && ev.hasNonNull("detail")) {
                        summary = ev.get("detail").asText();
                    }
                }
                case "assistant" -> {
                    for (JsonNode c : ev.path("message").path("content")) {
                        if ("tool_use".equals(c.path("type").asText())) set("running", runningLabel(c.path("name").asText(), c.path("input")), "");
                    }
                }
                case "user" -> set("waiting", "Looking at the result…", "");
                default -> {
                }
            }
        }
    }

    public record Result(String text, Double costUsd, Integer turns) {
    }

    /** Runs the planner for a show. resume: continue the show's last planner conversation. */
    public Result run(String show, ShowTasks.Task task, String prompt, boolean resume) throws ShowException {
        Path claude = findClaude(service.store().get().showCreator.claude);
        if (claude == null) {
            throw new ShowException("Claude Code not found ('" + service.store().get().showCreator.claude
                    + "'): install it and log in, or set its path in Settings → Show creation");
        }
        service.dir(show);
        prepareFolder();
        String sessionId = resume ? lastSession(show) : null;
        task.log((sessionId != null ? "Continuing the planner session" : "Starting a new planner session") + " for '" + show + "'");
        ProcessBuilder pb = new ProcessBuilder(command(claude, prompt, sessionId)).directory(plannerDir().toFile());
        cleanEnvironment(pb.environment());
        pb.environment().put("SHOWCREATOR_SHOW", show); // ./sc works on this show
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new ShowException("cannot start Claude Code: " + e.getMessage());
        }
        task.onCancel = proc::destroy;
        task.activity("waiting", "Starting Claude Code…", "", null);
        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) synchronized (err) {
                    err.append(l).append('\n');
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "planner-stderr");
        errReader.setDaemon(true);
        errReader.start();

        StreamTracker tracker = new StreamTracker(task);
        String text = "";
        Double cost = null;
        Integer turns = null;
        boolean isError = false;
        List<JsonNode> denials = new ArrayList<>();
        List<String> plain = new ArrayList<>(); // output that is not stream-json, for the error message
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (String raw; (raw = r.readLine()) != null; ) {
                JsonNode ev;
                try {
                    ev = ShowFiles.JSON.readTree(raw);
                } catch (IOException e) {
                    if (!raw.isBlank()) {
                        task.log(raw.strip(), "debug");
                        plain.add(raw.strip());
                    }
                    continue;
                }
                if (ev == null || !ev.isObject()) continue;
                tracker.event(ev);
                String t = ev.path("type").asText();
                if (t.equals("system") && "init".equals(ev.path("subtype").asText())) {
                    ObjectNode s = ShowFiles.object().put("session_id", ev.path("session_id").asText());
                    ShowFiles.write(sessionFile(show), s + "\n");
                } else if (t.equals("assistant")) {
                    for (JsonNode c : ev.path("message").path("content")) {
                        if ("text".equals(c.path("type").asText()) && !c.path("text").asText("").isBlank()) task.log(c.get("text").asText().strip(), "say");
                        else if ("tool_use".equals(c.path("type").asText())) task.log(toolSummary(c.path("name").asText(""), c.path("input")), "tool");
                    }
                } else if (t.equals("result")) {
                    text = ev.path("result").asText("");
                    cost = ev.hasNonNull("total_cost_usd") ? ev.get("total_cost_usd").asDouble() : null;
                    turns = ev.hasNonNull("num_turns") ? ev.get("num_turns").asInt() : null;
                    isError = ev.path("is_error").asBoolean(false);
                    if (text.isEmpty() && ev.path("errors").isArray()) text = ev.get("errors").toString();
                    ev.path("permission_denials").forEach(denials::add);
                }
            }
        } catch (IOException e) {
            // the process was stopped
        }
        int exit;
        try {
            exit = proc.waitFor();
            errReader.join(2000);
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
            throw new ShowException("interrupted");
        }
        if (task.cancelled()) throw new ShowException("planner stopped");
        if (exit != 0 || isError) {
            String e;
            synchronized (err) {
                e = err.toString().strip();
            }
            String detail = !e.isEmpty() ? e.substring(Math.max(0, e.length() - 500))
                    : !text.isEmpty() ? text.substring(0, Math.min(500, text.length()))
                    : !plain.isEmpty() ? String.join(" / ", plain.subList(Math.max(0, plain.size() - 3), plain.size()))
                    : "Claude Code stopped without a message. Run `claude` once in a terminal to check that it works and is logged in.";
            throw new ShowException("planner failed (exit " + exit + "): " + detail);
        }
        for (JsonNode d : denials) {
            String inp = d.path("tool_input").toString();
            task.log("permission denied: " + d.path("tool_name").asText() + " " + inp.substring(0, Math.min(200, inp.length())), "warn");
        }
        return new Result(text, cost, turns);
    }
}
