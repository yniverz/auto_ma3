package automa3.show;

import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.config.LookTransfer;
import automa3.ma3.ConsoleHub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The show creator's operations, shared by the web UI and the planner's command line. Shows live in
 * {@code <data folder>/shows/<name>/} (rig.json, plan.json, looks.in.json, patch.mvr, build/), separate from the
 * setups, and can be copied to try a different version without touching the original.
 */
public final class ShowService {

    static final Pattern SHOW_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    static final List<String> PLUGIN_FILES = List.of("ShowBuilder.xml", "ShowBuilder.lua");
    public static final List<String> KINDS = List.of("inspect", "patch", "probe", "build");
    /** The inputs of a show; build/ holds what is generated from them. */
    static final List<String> INPUTS = List.of("rig.json", "plan.json", "looks.in.json", "patch.mvr");

    private final ConfigStore store;
    private final Path dataDir;
    private final ConsoleHub hub; // null on the command line
    private volatile JsonNode library;
    private volatile String libraryVersion;

    public ShowService(ConfigStore store, Path dataDir, ConsoleHub hub) {
        this.store = store;
        this.dataDir = dataDir;
        this.hub = hub;
    }

    // ---------------------------------------------------------------- shows

    public Path showsDir() {
        return dataDir.resolve("shows");
    }

    public List<String> shows() {
        if (!Files.isDirectory(showsDir())) return List.of();
        try (Stream<Path> s = Files.list(showsDir())) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".")).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path dir(String name) throws ShowException {
        if (name == null || !SHOW_NAME.matcher(name).matches() || !Files.isDirectory(showsDir().resolve(name))) {
            throw new ShowException("no show '" + name + "'");
        }
        return showsDir().resolve(name);
    }

    /** The show selected in the Show tab (remembered across restarts), or null when there is none. */
    public String current() {
        try {
            String name = Files.readString(showsDir().resolve(".current")).strip();
            if (shows().contains(name)) return name;
        } catch (IOException ignored) {
            // none selected yet
        }
        List<String> all = shows();
        return all.isEmpty() ? null : all.get(0);
    }

    public void select(String name) throws ShowException {
        dir(name);
        ShowFiles.write(showsDir().resolve(".current"), name + "\n");
    }

    private void checkNewName(String name) throws ShowException {
        if (name == null || !SHOW_NAME.matcher(name).matches()) {
            throw new ShowException("show names use letters, digits, - and _ (max 64, no spaces)");
        }
        if (Files.exists(showsDir().resolve(name))) throw new ShowException("a show '" + name + "' exists already");
    }

    public void create(String name) throws ShowException {
        checkNewName(name);
        try {
            Files.createDirectories(showsDir().resolve(name));
        } catch (IOException e) {
            throw new ShowException("cannot create the show folder: " + e.getMessage());
        }
        select(name);
    }

    /**
     * Copy a show's inputs (rig, plan, look list, MVR) to a new show, e.g. to make another version of it during a
     * show. The build results and the planner conversation stay with the original; the copy's planner starts fresh
     * from the copied plan.
     */
    public void copy(String from, String to) throws ShowException {
        Path src = dir(from);
        checkNewName(to);
        Path dst = showsDir().resolve(to);
        try {
            Files.createDirectories(dst);
            for (String f : INPUTS) {
                if (Files.exists(src.resolve(f))) Files.copy(src.resolve(f), dst.resolve(f), StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException e) {
            throw new ShowException("cannot copy the show: " + e.getMessage());
        }
        select(to);
    }

    public void rename(String from, String to) throws ShowException {
        Path src = dir(from);
        checkNewName(to);
        boolean wasCurrent = from.equals(current());
        try {
            Files.move(src, showsDir().resolve(to));
        } catch (IOException e) {
            throw new ShowException("cannot rename the show: " + e.getMessage());
        }
        if (wasCurrent) select(to);
    }

    /** Moves the show to shows/.deleted/ (not gone: it can be moved back in the Finder). */
    public void delete(String name) throws ShowException {
        Path src = dir(name);
        try {
            Path trash = Files.createDirectories(showsDir().resolve(".deleted"));
            Files.move(src, trash.resolve(name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
        } catch (IOException e) {
            throw new ShowException("cannot delete the show: " + e.getMessage());
        }
    }

    public ObjectNode files(String name) throws ShowException {
        Path d = dir(name);
        ObjectNode o = ShowFiles.object();
        o.put("rig", Files.exists(d.resolve("rig.json"))).put("plan", Files.exists(d.resolve("plan.json")))
                .put("looks_in", Files.exists(d.resolve("looks.in.json"))).put("mvr", Files.exists(d.resolve("patch.mvr")))
                .put("looks_out", Files.exists(d.resolve("build/looks.out.json")));
        return o;
    }

    Path buildDir(String name) throws ShowException {
        Path d = dir(name).resolve("build");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new ShowException("cannot create " + d + ": " + e.getMessage());
        }
        return d;
    }

    // ---------------------------------------------------------------- settings

    /** The console shows are built on: chosen in the settings, else the first enabled one on this Mac. */
    public Config.ConsoleConfig targetConsole() throws ShowException {
        Config c = store.get();
        String wanted = c.showCreator.console;
        if (wanted != null && !wanted.isBlank()) {
            for (Config.ConsoleConfig con : c.consoles) if (con.name.equals(wanted)) return con;
            throw new ShowException("the console '" + wanted + "' set for show creation is not in the Consoles tab");
        }
        for (Config.ConsoleConfig con : c.consoles) {
            String h = con.host == null ? "" : con.host.trim();
            if (con.enabled && (h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("::1"))) return con;
        }
        throw new ShowException("no console on this Mac (127.0.0.1) in the Consoles tab: shows are built in grandMA3 onPC on this Mac");
    }

    public Path ma3Root() {
        String f = store.get().showCreator.ma3Folder;
        if (f != null && !f.isBlank()) return Path.of(f.replaceFirst("^~", System.getProperty("user.home")));
        return Path.of(System.getProperty("user.home"), "MALightingTechnology");
    }

    /** The console's version, e.g. "2.3" from the Consoles tab, completed from the installed onPC (e.g. "2.3.2"). */
    String ma3Version(Config.ConsoleConfig console) {
        String v = console.version == null || console.version.isBlank() ? "2.5" : console.version.trim();
        if (v.split("\\.").length >= 3) return v;
        String best = null;
        for (String installed : installedVersions()) {
            if (installed.equals(v) || installed.startsWith(v + ".")) best = installed; // sorted: the newest wins
        }
        return best != null ? best : v;
    }

    /** onPC versions installed on this Mac, oldest first. */
    public List<String> installedVersions() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(ma3Root())) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(ma3Root(), "gma3_[0-9]*")) {
            for (Path p : ds) if (Files.isDirectory(p)) out.add(p.getFileName().toString().substring(5));
        } catch (IOException ignored) {
            // none
        }
        out.sort((a, b) -> compareVersions(a, b));
        return out;
    }

    static int compareVersions(String a, String b) {
        int[] x = Arrays.stream(a.split("\\.")).mapToInt(ShowService::intOr0).toArray();
        int[] y = Arrays.stream(b.split("\\.")).mapToInt(ShowService::intOr0).toArray();
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int intOr0(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public ShowSettings settings() throws ShowException {
        Config c = store.get();
        Config.ShowCreatorConfig sc = c.showCreator;
        Config.ConsoleConfig console = targetConsole();
        if (!List.of("recipe", "programmer").contains(sc.cueMethod)) throw new ShowException("cue method must be 'recipe' or 'programmer'");
        if (!Rig.AUDIENCE.contains(sc.audience)) throw new ShowException("FOH side must be -y, +y, -x or +x");
        if (sc.page < 1) throw new ShowException("the executor page must be 1 or higher");
        return new ShowSettings(ma3Version(console), ma3Root(),
                ShowSettings.Range.of(new int[]{sc.groupsFrom, sc.groupsTo}, "groups"),
                ShowSettings.Range.of(new int[]{sc.sequencesFrom, sc.sequencesTo}, "sequences"),
                ShowSettings.Range.of(new int[]{sc.matricksFrom, sc.matricksTo}, "MAtricks"), sc.page,
                ShowSettings.Range.of(new int[]{sc.executorsFrom, sc.executorsTo}, "executors"),
                sc.cueMethod, sc.audience, c.speed.master, console.commandOverrides);
    }

    /** Overview for the Show tab: what is set up and what is missing. Never throws. */
    public ObjectNode status() {
        ObjectNode o = ShowFiles.object();
        ObjectNode ma3 = o.putObject("ma3");
        Path root = ma3Root();
        ma3.put("root", root.toString()).put("rootOk", Files.isDirectory(root));
        ma3.set("installed", ShowFiles.JSON.valueToTree(installedVersions()));
        try {
            Config.ConsoleConfig con = targetConsole();
            ShowSettings s = settings();
            BuildProfile p = BuildProfile.resolve(s.ma3Version(), s.commandOverrides());
            ma3.put("console", con.name).put("target", con.host + ":" + con.port).put("version", s.ma3Version())
                    .put("profile", p.name).put("libraryOk", Files.isDirectory(s.libraryDir()))
                    .put("pluginInstalled", PLUGIN_FILES.stream().allMatch(f -> Files.exists(s.pluginsDir().resolve(f))));
            ma3.set("warnings", ShowFiles.JSON.valueToTree(p.warnings));
            ObjectNode r = o.putObject("ranges");
            r.put("groups", s.groups().start() + "–" + s.groups().end()).put("sequences", s.sequences().start() + "–" + s.sequences().end())
                    .put("matricks", s.matricks().start() + "–" + s.matricks().end()).put("page", s.page())
                    .put("executors", s.executors().start() + "–" + s.executors().end());
        } catch (ShowException e) {
            ma3.put("error", e.getMessage());
        }
        o.put("dryRun", hub != null && hub.isDryRun());
        o.put("claude", Planner.findClaude(store.get().showCreator.claude) != null);
        return o;
    }

    // ---------------------------------------------------------------- library, rig, looks, brief

    /** The effect library of the target console's version (from its installed onPC), else the bundled one (2.3.2). */
    public JsonNode library() {
        String version;
        try {
            version = settings().ma3Version();
        } catch (ShowException e) {
            return EffectLibrary.bundled();
        }
        if (library != null && version.equals(libraryVersion)) return library;
        JsonNode lib = EffectLibrary.bundled();
        Path xml = EffectLibrary.predefinedPhaserXml(ma3Root(), version);
        if (xml != null) {
            try {
                lib = EffectLibrary.build(xml, EffectLibrary.annotations(), version);
            } catch (ShowException e) {
                // keep the bundled one
            }
        }
        library = lib;
        libraryVersion = version;
        return lib;
    }

    public Rig.Built buildRig(String name) throws ShowException {
        ShowSettings s = settings();
        Path show = dir(name);
        Path log = s.exchangeDir().resolve("log_patch.jsonl");
        Path copied = show.resolve("build/ma3/log_patch.jsonl");
        Path mvr = show.resolve("patch.mvr");
        Rig.Patch patch = null;
        ObjectNode source = ShowFiles.object().put("generator", "AutoMA3 show creator");
        Path use = Files.exists(copied) ? copied : Files.exists(log) ? log : null;
        if (use != null) {
            patch = Rig.readPatchLog(use);
            source.putObject("console").put("job_id", patch.jobId()).put("ma3", patch.ma3());
        } else if (!Files.exists(mvr)) {
            throw new ShowException("no patch yet: read the patch from grandMA3 first (or add an MVR export)");
        }
        List<Rig.MvrFixture> mvrFx = null;
        if (Files.exists(mvr)) {
            mvrFx = Rig.readMvr(mvr);
            source.putObject("mvr").put("file", "patch.mvr").put("sha256", Rig.fileDigest(mvr));
        }
        Rig.Built b = Rig.build(patch, mvrFx, s.audience(), source);
        List<SchemaCheck.Error> errs = SchemaCheck.validate(b.rig(), SchemaCheck.load("rig"));
        if (!errs.isEmpty()) {
            throw new ShowException("generated rig does not match the schema: "
                    + String.join("; ", errs.stream().map(e -> e.path() + ": " + e.message()).toList()));
        }
        ShowFiles.write(show.resolve("rig.json"), ShowFiles.pretty(b.rig(), 1));
        return b;
    }

    public record LooksIn(List<JsonNode> looks, String source, boolean custom) {
    }

    public LooksIn looksIn(String name) throws ShowException {
        Path p = dir(name).resolve("looks.in.json");
        if (Files.exists(p)) return new LooksIn(ShowCompiler.list(ShowFiles.read(p, "look list").get("looks")), "looks.in.json (AutoMA3 export)", true);
        return new LooksIn(ShowCompiler.list(ShowFiles.resource("library/default_looks.json").get("looks")),
                "AutoMA3 default looks (no looks.in.json in the show folder)", false);
    }

    /** Use AutoMA3's current looks (Looks tab) as the show's look list. */
    public int looksFromAutoMA3(String name) throws ShowException {
        ObjectNode data = LookTransfer.export(store.get());
        saveLooksIn(name, data);
        return data.get("looks").size();
    }

    public void saveLooksIn(String name, JsonNode data) throws ShowException {
        if (data == null || !LookTransfer.FORMAT.equals(data.path("format").asText()) || !data.path("looks").isArray()) {
            throw new ShowException("not an AutoMA3 look file (format 'automa3-looks' with a 'looks' list)");
        }
        ShowFiles.write(dir(name).resolve("looks.in.json"), ShowFiles.pretty(data, 2));
    }

    public void useDefaultLooks(String name) throws ShowException {
        try {
            Files.deleteIfExists(dir(name).resolve("looks.in.json"));
        } catch (IOException e) {
            throw new ShowException("cannot remove looks.in.json: " + e.getMessage());
        }
    }

    public Path writeBrief(String name) throws ShowException {
        JsonNode rig = ShowFiles.read(dir(name).resolve("rig.json"), "rig (read the patch first)");
        LooksIn looks = looksIn(name);
        return ShowFiles.write(buildDir(name).resolve("brief.md"), Brief.build(name, settings(), rig, library(), looks.looks(), looks.source()));
    }

    // ---------------------------------------------------------------- validate, compile, deploy

    public record Validated(JsonNode plan, JsonNode rig, JsonNode library, List<PlanValidator.Issue> issues) {
    }

    public Validated validate(String name) throws ShowException {
        Path d = dir(name);
        JsonNode plan = ShowFiles.read(d.resolve("plan.json"), "plan");
        JsonNode rig = ShowFiles.read(d.resolve("rig.json"), "rig");
        JsonNode lib = library();
        List<PlanValidator.Issue> issues = PlanValidator.validate(plan, rig, lib, settings(), looksIn(name).looks());
        ShowFiles.write(buildDir(name).resolve("validation.json"), ShowFiles.pretty(PlanValidator.report(issues), 2));
        return new Validated(plan, rig, lib, issues);
    }

    public record CompiledJob(String kind, String lua, String jobId, int steps, int unverified, List<String> warnings,
                              List<PlanValidator.Issue> issues, ShowCompiler.Compiled build) {
    }

    private static final Pattern JOB_ID = Pattern.compile("job_id = \"([^\"]+)\"");

    public CompiledJob compile(String name, String kind) throws ShowException {
        if (!KINDS.contains(kind)) throw new ShowException("unknown job kind " + kind);
        ShowSettings s = settings();
        Path out = buildDir(name);
        ShowCompiler.Job job;
        List<PlanValidator.Issue> issues = List.of();
        ShowCompiler.Compiled built = null;
        switch (kind) {
            case "inspect" -> job = ShowCompiler.compileInspect(s);
            case "patch" -> job = ShowCompiler.compilePatch(s);
            default -> {
                Validated v = validate(name);
                issues = v.issues();
                if (PlanValidator.hasErrors(issues)) throw new ShowException("the plan has errors; fix them first (see the check)");
                if (kind.equals("probe")) {
                    job = ShowCompiler.compileProbe(Rig.resolveGroup(v.rig(), v.plan().get("groups").get(0).get("fixtures")), v.library(), s);
                } else {
                    built = ShowCompiler.compileBuild(v.plan(), v.rig(), v.library(), s, looksIn(name).looks());
                    job = built.job();
                    ShowFiles.write(out.resolve("allocation.json"), ShowFiles.pretty(built.allocation().toJson(), 2));
                    ShowFiles.write(out.resolve("looks.out.json"), ShowFiles.pretty(built.looks(), 2));
                }
            }
        }
        String lua = job.toLua(s.ma3Version());
        ShowFiles.write(out.resolve("job_" + kind + ".lua"), lua);
        Matcher m = JOB_ID.matcher(lua);
        return new CompiledJob(kind, lua, m.find() ? m.group(1) : "", job.stepCount(), job.unverifiedCount(), job.warnings, issues, built);
    }

    /** Copy the plugin into grandMA3's plugin folder if it changed. True when an installed plugin's code changed. */
    public boolean syncPlugin() throws ShowException {
        ShowSettings s = settings();
        boolean luaChanged = false;
        try {
            Files.createDirectories(s.pluginsDir());
            for (String f : PLUGIN_FILES) {
                byte[] src = ShowFiles.resourceBytes("plugin/" + f);
                Path dst = s.pluginsDir().resolve(f);
                if (!Files.exists(dst) || !Arrays.equals(Files.readAllBytes(dst), src)) {
                    luaChanged |= Files.exists(dst) && f.endsWith(".lua");
                    Files.write(dst, src);
                }
            }
        } catch (IOException e) {
            throw new ShowException("cannot install the ShowBuilder plugin in " + s.pluginsDir() + ": " + e.getMessage());
        }
        return luaChanged;
    }

    public record Deployed(CompiledJob compiled, Path jobPath, String command, boolean reloadNeeded) {
    }

    public Deployed deploy(String name, String kind) throws ShowException {
        ShowSettings s = settings();
        if (!Files.isDirectory(s.libraryDir())) {
            throw new ShowException("grandMA3's library folder not found: " + s.libraryDir() + " (is onPC installed on this Mac?)");
        }
        CompiledJob c = compile(name, kind);
        boolean reload = syncPlugin();
        try {
            Files.createDirectories(s.exchangeDir());
            Path jobPath = s.exchangeDir().resolve("job_" + kind + ".lua");
            Files.deleteIfExists(s.exchangeDir().resolve("log_" + kind + ".jsonl"));
            Files.writeString(jobPath, c.lua());
            Files.writeString(s.exchangeDir().resolve("job.lua"), c.lua());
            return new Deployed(c, jobPath, "Plugin \"ShowBuilder\" \"" + jobPath + "\"", reload);
        } catch (IOException e) {
            throw new ShowException("cannot write the job for grandMA3: " + e.getMessage());
        }
    }

    /** Start the job in grandMA3 over OSC (the target console's connection). Returns the commands sent. */
    public List<String> trigger(Deployed d) throws ShowException {
        if (hub == null) throw new ShowException("no console connection here");
        if (hub.isDryRun()) {
            throw new ShowException("dry run is on: nothing is sent to grandMA3. Switch to Go live, or paste this into "
                    + "the grandMA3 command line: " + d.command());
        }
        Config.ConsoleConfig console = targetConsole();
        List<String> cmds = new ArrayList<>();
        if (d.reloadNeeded()) cmds.add("ReloadAllPlugins");
        cmds.add(d.command());
        for (int i = 0; i < cmds.size(); i++) {
            hub.sendTo(console, cmds.get(i), "show creator: " + d.compiled().kind());
            if (i < cmds.size() - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ShowException("interrupted");
                }
            }
        }
        return cmds;
    }

    // ---------------------------------------------------------------- the plugin's log

    public record LogSummary(Path path, String jobId, String ma3, String plugin, int total, int done, String state, JsonNode end,
                             List<JsonNode> steps, List<JsonNode> failed, JsonNode inProgress, int unreadable) {
    }

    /** Summary of the plugin's last log of this job kind, or null when there is none yet. */
    public LogSummary readLog(String name, String kind, boolean copy) throws ShowException {
        ShowSettings s = settings();
        Path path = s.exchangeDir().resolve("log_" + kind + ".jsonl");
        if (!Files.exists(path)) return null;
        List<JsonNode> records = new ArrayList<>();
        int unreadable = 0;
        try {
            for (String line : Files.readString(path, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                try {
                    records.add(ShowFiles.JSON.readTree(line));
                } catch (IOException e) {
                    unreadable++;
                }
            }
        } catch (IOException e) {
            return null; // being written right now
        }
        JsonNode start = records.stream().filter(r -> "start".equals(r.path("ev").asText())).findFirst().orElse(ShowFiles.object());
        JsonNode end = records.stream().filter(r -> "end".equals(r.path("ev").asText())).findFirst().orElse(null);
        List<JsonNode> steps = records.stream().filter(r -> "step".equals(r.path("ev").asText())).toList();
        java.util.Set<String> finished = new java.util.HashSet<>();
        steps.forEach(r -> finished.add(r.path("i").asText()));
        List<JsonNode> crashed = records.stream().filter(r -> "begin".equals(r.path("ev").asText()) && !finished.contains(r.path("i").asText())).toList();
        if (copy && end != null) { // keep a copy (and the exports) with the show for later analysis
            try {
                Path dest = Files.createDirectories(buildDir(name).resolve("ma3"));
                Files.copy(path, dest.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(s.exchangeDir(), "export_" + kind + "_*.xml")) {
                    for (Path x : ds) Files.copy(x, dest.resolve(x.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // the copy is only for later analysis
            }
        }
        String state = end == null ? "running" : end.path("ok").asBoolean(false) ? "ok" : "failed";
        List<JsonNode> failed = steps.stream().filter(r -> !r.path("ok").asBoolean(false) && !r.path("tolerated").asBoolean(false)).toList();
        return new LogSummary(path, start.path("job_id").asText(null), start.path("ma3").asText(null), start.path("plugin").asText(null),
                start.path("steps").asInt(0), steps.size(), state, end, steps, failed, crashed.isEmpty() ? null : crashed.get(crashed.size() - 1), unreadable);
    }

    /** Waits until the job with this id ends. Throws on timeout, cancel or a stalled (crashed) job. */
    public LogSummary waitLog(String name, String kind, String jobId, long timeoutMs, long stalledAfterMs,
                              Consumer<LogSummary> progress, BooleanSupplier cancelled) throws ShowException {
        long t0 = System.currentTimeMillis(), lastChange = t0;
        int lastDone = -1;
        while (true) {
            if (cancelled.getAsBoolean()) throw new ShowException("cancelled");
            LogSummary s = readLog(name, kind, false);
            if (s != null && jobId.equals(s.jobId())) {
                if (s.end() != null) return readLog(name, kind, true);
                if (s.done() != lastDone) {
                    lastDone = s.done();
                    lastChange = System.currentTimeMillis();
                    if (progress != null) progress.accept(s);
                } else if (System.currentTimeMillis() - lastChange > stalledAfterMs) {
                    JsonNode st = s.inProgress();
                    String where = st == null ? "" : " in step " + st.path("i").asText() + " ("
                            + (st.hasNonNull("cmd") ? st.get("cmd").asText() : st.hasNonNull("addr") ? st.get("addr").asText() : st.path("op").asText()) + ")";
                    throw new ShowException("the plugin stopped" + where + ": the grandMA3 Lua engine may have crashed");
                }
            }
            if (System.currentTimeMillis() - t0 > timeoutMs) {
                throw new ShowException("no result from grandMA3 in time: did the command arrive? (OSC input on, or paste it)");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ShowException("interrupted");
            }
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public ConfigStore store() {
        return store;
    }
}
