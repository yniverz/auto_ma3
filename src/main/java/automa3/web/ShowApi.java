package automa3.web;

import automa3.show.Planner;
import automa3.show.PlanValidator;
import automa3.show.ShowException;
import automa3.show.ShowFiles;
import automa3.show.ShowService;
import automa3.show.ShowTasks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Show tab's API (/api/show/...). Changes need the header X-AutoMA3: 1 (a foreign web page cannot send it
 * cross-origin without a preflight this server never answers) and come only from this Mac: building a show deletes
 * and rewrites objects in grandMA3 and the planner runs Claude Code, so a tablet on the show network cannot start them.
 */
final class ShowApi {

    private static final int MAX_UPLOAD = 50 * 1024 * 1024;

    private final ShowService service;
    private final Planner planner;
    private final ShowTasks tasks = new ShowTasks();

    ShowApi(ShowService service) {
        this.service = service;
        this.planner = new Planner(service);
    }

    interface Json {
        void send(HttpExchange ex, int code, Object value) throws IOException;
    }

    void handle(HttpExchange ex, String path, String method, Map<String, String> query, Json json) throws IOException {
        try {
            if (method.equals("POST")) {
                if (!allowed(ex)) {
                    json.send(ex, 403, Map.of("error", "show changes only from this Mac"));
                    return;
                }
                post(ex, path, query, json);
            } else {
                get(ex, path, query, json);
            }
        } catch (ShowException e) {
            json.send(ex, 400, Map.of("error", e.getMessage()));
        } catch (ShowTasks.BusyException e) {
            json.send(ex, 409, Map.of("error", e.getMessage()));
        }
    }

    private static boolean allowed(HttpExchange ex) {
        InetAddress remote = ex.getRemoteAddress().getAddress();
        String host = String.valueOf(ex.getRequestHeaders().getFirst("Host")).replaceFirst(":\\d+$", "");
        return "1".equals(ex.getRequestHeaders().getFirst("X-AutoMA3")) && remote != null && remote.isLoopbackAddress()
                && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
    }

    private String show(Map<String, String> query) throws ShowException {
        String name = query.get("show");
        if (name == null || name.isBlank()) name = service.current();
        if (name == null) throw new ShowException("no show yet: create one");
        service.dir(name);
        return name;
    }

    private static JsonNode body(HttpExchange ex) throws IOException, ShowException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_UPLOAD + 1);
        if (b.length > MAX_UPLOAD) throw new ShowException("upload too large");
        if (b.length == 0) return ShowFiles.object();
        try {
            return ShowFiles.JSON.readTree(b);
        } catch (IOException e) {
            throw new ShowException("the file is not JSON");
        }
    }

    // ---------------------------------------------------------------- reading

    private void get(HttpExchange ex, String path, Map<String, String> query, Json json) throws IOException, ShowException {
        switch (path) {
            case "/api/show/state" -> json.send(ex, 200, state(query.get("show")));
            case "/api/show/rig" -> {
                Path p = service.dir(show(query)).resolve("rig.json");
                if (!Files.exists(p)) {
                    json.send(ex, 200, null);
                    return;
                }
                JsonNode rig = ShowFiles.read(p, "rig");
                ObjectNode o = ShowFiles.object();
                o.set("fixtures", rig.get("fixtures"));
                o.set("sets", rig.get("sets"));
                o.set("source", rig.path("source"));
                json.send(ex, 200, o);
            }
            case "/api/show/looks" -> {
                ShowService.LooksIn l = service.looksIn(show(query));
                ObjectNode o = ShowFiles.object().put("source", l.source()).put("custom", l.custom());
                o.set("looks", ShowFiles.JSON.valueToTree(l.looks()));
                json.send(ex, 200, o);
            }
            case "/api/show/plan" -> json.send(ex, 200, planView(show(query)));
            case "/api/show/brief" -> {
                Path p = service.dir(show(query)).resolve("build/brief.md");
                send(ex, Files.exists(p) ? Files.readAllBytes(p) : new byte[0], "text/plain; charset=utf-8", null);
            }
            case "/api/show/looks-out" -> {
                String name = show(query);
                Path p = service.dir(name).resolve("build/looks.out.json");
                if (!Files.exists(p)) throw new ShowException("not built yet");
                JsonNode looks = ShowFiles.read(p, "built looks");
                // files built before "disableOnly" existed: their left-out looks carry only the build's note
                for (JsonNode l : looks.path("looks")) {
                    if (l.isObject() && !l.path("enabled").asBoolean(true)
                            && l.path("meta").path("generator").path("note").asText("").startsWith("not built by the plan")) {
                        ((ObjectNode) l).put("disableOnly", true);
                    }
                }
                send(ex, ShowFiles.pretty(looks, 2).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json",
                        query.containsKey("download") ? "attachment; filename=\"" + name + "-looks.json\"" : null);
            }
            default -> {
                if (path.startsWith("/api/show/task/")) {
                    ShowTasks.Task t = tasks.get(path.substring("/api/show/task/".length()));
                    if (t == null) {
                        json.send(ex, 404, Map.of("error", "no such task"));
                        return;
                    }
                    json.send(ex, 200, t.view(Integer.parseInt(query.getOrDefault("since", "0"))));
                } else {
                    json.send(ex, 404, Map.of("error", "not found"));
                }
            }
        }
    }

    private ObjectNode state(String requested) {
        ObjectNode o = ShowFiles.object();
        o.set("status", service.status());
        o.set("shows", ShowFiles.JSON.valueToTree(service.shows()));
        String show = requested != null && service.shows().contains(requested) ? requested : service.current();
        o.put("show", show);
        try {
            if (show != null) o.set("files", service.files(show));
        } catch (ShowException ignored) {
            // just deleted
        }
        ShowTasks.Task cur = tasks.current();
        if (cur != null) o.set("task", cur.view(Integer.MAX_VALUE));
        o.put("plannerSession", show != null && planner.lastSession(show) != null);
        return o;
    }

    /** The plan in words, for the page: per look the groups, effects and spreads, plus the check's issues. */
    private ObjectNode planView(String name) throws ShowException {
        if (!Files.exists(service.dir(name).resolve("plan.json"))) return null;
        ShowService.Validated v = service.validate(name);
        JsonNode plan = v.plan();
        Path allocPath = service.dir(name).resolve("build/allocation.json");
        JsonNode alloc = Files.exists(allocPath) ? ShowFiles.read(allocPath, "allocation") : ShowFiles.object();
        Map<String, JsonNode> groups = new HashMap<>();
        plan.get("groups").forEach(g -> groups.put(g.get("key").asText(), g));
        Map<String, JsonNode> seqs = new HashMap<>();
        plan.get("sequences").forEach(s -> seqs.put(s.get("key").asText(), s));
        ArrayNode looks = ShowFiles.array();
        for (JsonNode lk : plan.get("looks")) {
            JsonNode s = seqs.getOrDefault(lk.get("sequence").asText(), ShowFiles.object());
            ObjectNode o = looks.addObject().put("key", lk.get("key").asText()).put("name", lk.get("name").asText())
                    .put("role", lk.get("role").asText()).put("layer", lk.path("layer").asText(""));
            JsonNode ex = alloc.path("executors").get(lk.get("key").asText());
            o.set("exec", ex != null ? ex : lk.get("exec"));
            o.set("sections", lk.has("sections") ? lk.get("sections") : ShowFiles.array());
            o.set("energy", lk.get("energy"));
            o.put("speed", s.path("speed_scale").asText("One")).put("priority", s.path("priority").asText(""));
            ArrayNode lines = o.putArray("lines");
            for (JsonNode c : s.path("cues")) for (JsonNode l : c.path("lines")) lines.add(describeLine(l, groups));
            o.put("description", lk.path("description").asText(""));
        }
        ObjectNode o = ShowFiles.object().put("notes", plan.path("notes").asText(""));
        o.set("looks", looks);
        o.put("groups", plan.get("groups").size()).put("sequences", plan.get("sequences").size());
        ArrayNode issues = o.putArray("issues");
        for (PlanValidator.Issue i : v.issues()) {
            issues.addObject().put("level", i.level()).put("path", i.path()).put("code", i.code()).put("message", i.message()).put("hint", i.hint());
        }
        return o;
    }

    private static String describeLine(JsonNode l, Map<String, JsonNode> groups) {
        String gk = l.get("group").asText();
        JsonNode g = groups.get(gk);
        String group = gk;
        if (g != null) {
            JsonNode f = g.get("fixtures");
            group = g.get("name").asText() + (f.has("set") ? " (" + f.get("set").asText() + ", " + f.get("order").asText() + ")"
                    : " (" + f.get("fids").size() + " fixtures)");
        }
        StringBuilder what = new StringBuilder();
        if (l.has("preset")) {
            what.append(l.get("preset").asText());
        } else {
            for (String k : ShowFiles.keys(l.get("values"))) {
                if (!what.isEmpty()) what.append(", ");
                what.append(k).append(' ').append(numberText(l.get("values").get(k)));
            }
        }
        List<String> extra = new java.util.ArrayList<>();
        if (l.has("phase")) extra.add("phase " + numberText(l.get("phase").get("from")) + "→" + numberText(l.get("phase").get("to")));
        for (String k : List.of("wings", "blocks", "groups")) if (l.has(k)) extra.add(k + " " + l.get(k).asText());
        return group + ": " + what + (extra.isEmpty() ? "" : " [" + String.join(", ", extra) + "]");
    }

    private static String numberText(JsonNode n) {
        if (n == null) return "";
        if (!n.isNumber()) return n.asText();
        double d = n.doubleValue();
        return d == Math.rint(d) ? Long.toString((long) d) : n.decimalValue().stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- changing

    private void post(HttpExchange ex, String path, Map<String, String> query, Json json) throws IOException, ShowException, ShowTasks.BusyException {
        switch (path) {
            case "/api/show/select" -> {
                service.select(body(ex).path("name").asText());
                json.send(ex, 200, state(null));
            }
            case "/api/show/create" -> {
                service.create(body(ex).path("name").asText().strip());
                json.send(ex, 200, state(null));
            }
            case "/api/show/copy" -> {
                JsonNode b = body(ex);
                service.copy(b.path("from").asText(), b.path("to").asText().strip());
                json.send(ex, 200, state(null));
            }
            case "/api/show/rename" -> {
                JsonNode b = body(ex);
                busyCheck(b.path("from").asText());
                service.rename(b.path("from").asText(), b.path("to").asText().strip());
                json.send(ex, 200, state(null));
            }
            case "/api/show/delete" -> {
                String name = body(ex).path("name").asText();
                busyCheck(name);
                service.delete(name);
                json.send(ex, 200, state(null));
            }
            case "/api/show/patch" -> {
                String name = show(query);
                json.send(ex, 200, tasks.start("patch", name, t -> taskPatch(t, name)).view(0));
            }
            case "/api/show/plan/run" -> {
                String name = show(query);
                JsonNode b = body(ex);
                String prompt = b.path("prompt").asText("").strip();
                boolean resume = b.path("resume").asBoolean(false);
                json.send(ex, 200, tasks.start("plan", name, t -> taskPlan(t, name, prompt.isEmpty() ? "Plan the show." : prompt, resume)).view(0));
            }
            case "/api/show/build" -> {
                String name = show(query);
                json.send(ex, 200, tasks.start("build", name, t -> consoleJob(t, name, "build")).view(0));
            }
            case "/api/show/looks/automa3" -> json.send(ex, 200, Map.of("count", service.looksFromAutoMA3(show(query))));
            case "/api/show/looks/upload" -> {
                JsonNode data = body(ex);
                service.saveLooksIn(show(query), data);
                json.send(ex, 200, Map.of("count", data.get("looks").size()));
            }
            case "/api/show/looks/defaults" -> {
                service.useDefaultLooks(show(query));
                json.send(ex, 200, Map.of("ok", true));
            }
            case "/api/show/mvr" -> {
                String name = show(query);
                byte[] data = ex.getRequestBody().readNBytes(MAX_UPLOAD + 1);
                if (data.length > MAX_UPLOAD) throw new ShowException("upload too large");
                if (data.length < 2 || data[0] != 'P' || data[1] != 'K') throw new ShowException("not an MVR file (zip)");
                Files.write(service.dir(name).resolve("patch.mvr"), data);
                boolean rebuilt = false;
                if (Files.exists(service.dir(name).resolve("build/ma3/log_patch.jsonl")) || Files.exists(service.dir(name).resolve("rig.json"))) {
                    service.buildRig(name);
                    rebuilt = true;
                }
                json.send(ex, 200, Map.of("ok", true, "rigRebuilt", rebuilt));
            }
            default -> {
                if (path.startsWith("/api/show/task/") && path.endsWith("/cancel")) {
                    ShowTasks.Task t = tasks.cancel(path.split("/")[4]);
                    if (t == null) json.send(ex, 404, Map.of("error", "no such task"));
                    else json.send(ex, 200, t.view(0));
                } else {
                    json.send(ex, 404, Map.of("error", "not found"));
                }
            }
        }
    }

    private void busyCheck(String show) throws ShowException {
        ShowTasks.Task cur = tasks.current();
        if (cur != null && cur.show.equals(show) && cur.running()) {
            throw new ShowException("'" + cur.kind + "' is still running for this show");
        }
    }

    // ---------------------------------------------------------------- background tasks

    private JsonNode consoleJob(ShowTasks.Task task, String show, String kind) throws ShowException {
        ShowService.Deployed d = service.deploy(show, kind);
        task.log("Job compiled: " + d.compiled().steps() + " steps"
                + (d.compiled().unverified() > 0 ? ", " + d.compiled().unverified() + " with unverified syntax" : ""));
        for (String w : d.compiled().warnings()) task.log(w, "warn");
        List<String> sent = service.trigger(d);
        task.log("Sent to grandMA3: " + String.join(" / ", sent));
        task.hint("If nothing happens, paste this into the grandMA3 command line:", d.command());
        task.activity("console", "Waiting for grandMA3 to start the job…", "", null);
        int[] last = {0};
        ShowService.LogSummary s = service.waitLog(show, kind, d.compiled().jobId(), 300_000, 30_000, p -> {
            int total = p.total();
            task.activity("console", "grandMA3: step " + p.done() + " of " + total, "", total > 0 ? (double) p.done() / total : null);
            if (p.done() - last[0] >= 25 || p.done() == total) {
                task.log(p.done() + "/" + total + " steps");
                last[0] = p.done();
            }
        }, task::cancelled);
        for (JsonNode f : s.failed()) {
            String what = f.hasNonNull("cmd") ? f.get("cmd").asText() : f.path("op").asText();
            String result = f.hasNonNull("result") ? f.get("result").asText() : f.path("error").asText();
            task.log("step " + f.path("i").asText() + " failed: " + what + " → " + result, "error");
        }
        JsonNode end = s.end();
        if (end.hasNonNull("error")) throw new ShowException(end.get("error").asText());
        if (!end.path("ok").asBoolean(false)) {
            throw new ShowException(end.path("failed").asInt() + " step(s) failed" + (end.path("aborted").asBoolean(false) ? ", aborted" : ""));
        }
        task.log("Done in grandMA3: " + end.path("done").asInt() + "/" + end.path("steps").asInt() + " steps", "ok");
        return ShowFiles.object().put("done", end.path("done").asInt()).put("steps", end.path("steps").asInt());
    }

    private JsonNode taskPatch(ShowTasks.Task task, String show) throws ShowException {
        consoleJob(task, show, "patch");
        task.activity("running", "Building the rig…", "", null);
        var b = service.buildRig(show);
        for (String w : b.warnings()) task.log(w, "warn");
        task.log("Rig: " + b.rig().get("fixtures").size() + " fixtures, " + b.rig().get("sets").size() + " sets", "ok");
        return ShowFiles.object().put("fixtures", b.rig().get("fixtures").size());
    }

    private JsonNode taskPlan(ShowTasks.Task task, String show, String prompt, boolean resume) throws ShowException {
        task.activity("running", "Writing the brief…", "", null);
        service.writeBrief(show);
        task.log("Brief written for the planner");
        Planner.Result res = planner.run(show, task, prompt, resume);
        task.activity("running", "Checking the plan…", "", null);
        List<PlanValidator.Issue> issues = service.validate(show).issues();
        long errors = issues.stream().filter(i -> i.level().equals("error")).count();
        task.log("Check: " + errors + " error(s), " + (issues.size() - errors) + " warning(s)", errors > 0 ? "error" : "ok");
        if (res.costUsd() != null) task.log("Planner: " + res.turns() + " turns, $" + String.format(java.util.Locale.ROOT, "%.2f", res.costUsd()), "debug");
        return ShowFiles.object().put("text", res.text()).put("errors", errors);
    }

    private static void send(HttpExchange ex, byte[] body, String type, String disposition) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (disposition != null) ex.getResponseHeaders().set("Content-Disposition", disposition);
        ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
