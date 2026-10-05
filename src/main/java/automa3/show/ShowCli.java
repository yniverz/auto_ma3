package automa3.show;

import automa3.config.ConfigStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java -jar auto-ma3.jar show <command>}: the show creator commands the planner runs through ./sc
 * (brief, validate, rig), plus status. The show comes from --show, else SHOWCREATOR_SHOW (set for the planner),
 * else the show selected in the Show tab. Reads the config, never writes it.
 */
public final class ShowCli {

    private ShowCli() {
    }

    /** Returns the exit code: 0 ok, 1 the plan has errors, 2 usage or other failure. */
    public static int run(String[] args, Path defaultConfig) {
        Path config = defaultConfig;
        String show = System.getenv("SHOWCREATOR_SHOW");
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(args[++i]);
                case "--show" -> show = args[++i];
                default -> rest.add(args[i]);
            }
        }
        String cmd = rest.isEmpty() ? "help" : rest.get(0);
        try {
            ShowService svc = new ShowService(new ConfigStore(config), config.toAbsolutePath().getParent(), null);
            if (show == null || show.isBlank()) show = svc.current();
            switch (cmd) {
                case "brief" -> {
                    System.out.println("  wrote " + svc.writeBrief(need(show)));
                    return 0;
                }
                case "validate" -> {
                    List<PlanValidator.Issue> issues = svc.validate(need(show)).issues();
                    issues.forEach(System.out::println);
                    long errors = issues.stream().filter(i -> i.level().equals("error")).count();
                    System.out.println((errors == 0 ? "OK" : "FAILED") + ": " + errors + " error(s), " + (issues.size() - errors)
                            + " warning(s). Report: " + svc.dir(show).resolve("build/validation.json"));
                    return errors == 0 ? 0 : 1;
                }
                case "rig" -> {
                    Rig.Built b = svc.buildRig(need(show));
                    b.warnings().forEach(w -> System.out.println("  warning: " + w));
                    System.out.println("  wrote " + svc.dir(show).resolve("rig.json"));
                    for (var f : b.rig().get("fixtures")) {
                        System.out.printf("  %5d  %-28s %-40s %s%n", f.get("fid").asInt(), f.get("type").asText(),
                                String.join(",", ShowFiles.texts(f.get("caps"))), f.has("pos") ? f.get("pos") : "-");
                    }
                    System.out.println("\n  sets: " + String.join(", ", ShowFiles.keys(b.rig().get("sets"))));
                    return 0;
                }
                case "deploy" -> { // console tests: write the job and the plugin, print the command to paste
                    String kind = rest.size() > 1 ? rest.get(1) : "";
                    ShowService.Deployed d = svc.deploy(need(show), kind);
                    d.compiled().warnings().forEach(w -> System.out.println("  warning: " + w));
                    System.out.println("  " + d.compiled().steps() + " steps (" + d.compiled().unverified() + " use unverified syntax, see docs/showcreator/MA3_NOTES.md)");
                    System.out.println("\nJob written: " + d.jobPath() + "\nRun this in the grandMA3 command line:\n");
                    if (d.reloadNeeded()) System.out.println("  ReloadAllPlugins        (the plugin code changed; needed once)");
                    System.out.println("  " + d.command() + "\n");
                    System.out.println("Then: show log " + kind);
                    return 0;
                }
                case "log" -> { // the plugin's last log of a job kind
                    String kind = rest.size() > 1 ? rest.get(1) : "build";
                    ShowService.LogSummary l = svc.readLog(need(show), kind, true);
                    if (l == null) {
                        System.out.println("no log for '" + kind + "' yet");
                        return 2;
                    }
                    System.out.println(kind + " " + l.jobId() + " on grandMA3 " + l.ma3() + " (plugin " + l.plugin() + "): " + l.state()
                            + ", " + l.done() + "/" + l.total() + " steps");
                    for (var f : l.failed()) {
                        System.out.println("  step " + f.path("i").asText() + " failed: " + (f.hasNonNull("cmd") ? f.get("cmd").asText() : f.path("op").asText())
                                + " -> " + (f.hasNonNull("result") ? f.get("result").asText() : f.path("error").asText()));
                    }
                    if (l.inProgress() != null) System.out.println("  stopped in step " + l.inProgress().path("i").asText() + ": " + l.inProgress());
                    if (l.end() != null && l.end().hasNonNull("error")) System.out.println("  error: " + l.end().get("error").asText());
                    return "ok".equals(l.state()) ? 0 : 1;
                }
                case "status" -> {
                    System.out.println(ShowFiles.pretty(svc.status(), 2));
                    System.out.println("shows: " + svc.shows() + ", selected: " + show);
                    return 0;
                }
                default -> {
                    System.out.println("""
                            AutoMA3 show creator commands (used by the planner through ./sc):
                              show brief      write build/brief.md for the planner
                              show validate   check plan.json (exit code 1 when it has errors)
                              show rig        rebuild rig.json from the patch read from grandMA3
                              show status     what is set up
                              show deploy K   console tests: write job K (inspect, patch, probe, build), print the command
                              show log K      the plugin's last log of job K
                            options: --show NAME (default: SHOWCREATOR_SHOW, else the selected show), --config FILE""");
                    return cmd.equals("help") ? 0 : 2;
                }
            }
        } catch (ShowException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("error: " + e);
            return 2;
        }
    }

    private static String need(String show) throws ShowException {
        if (show == null || show.isBlank()) throw new ShowException("no show selected (create one in the Show tab)");
        return show;
    }
}
