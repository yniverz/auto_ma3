package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Long show creator steps (read the patch, plan, build) run one at a time in the background (they share the
 * console and the show files), with a log and a live status line the page shows.
 */
public final class ShowTasks {

    public interface Work {
        JsonNode run(Task task) throws Exception;
    }

    public static final class Task {
        public final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        public final String kind, show;
        volatile String state = "running"; // running | ok | error | cancelled
        private final List<ObjectNode> lines = new ArrayList<>();
        volatile JsonNode result;
        volatile String error;
        final long started = System.currentTimeMillis();
        volatile long ended;
        volatile boolean cancelRequested;
        volatile Runnable onCancel;
        private ObjectNode activity;
        private long activitySince;

        public Task(String kind, String show) {
            this.kind = kind;
            this.show = show;
        }

        public synchronized void log(String text, String level) {
            ObjectNode l = ShowFiles.object().put("t", Math.round((System.currentTimeMillis() - started) / 100.0) / 10.0)
                    .put("level", level).put("text", text);
            lines.add(l);
        }

        public void log(String text) {
            log(text, "info");
        }

        /** A line with the command to paste into grandMA3 when OSC does not reach it. */
        public synchronized void hint(String text, String command) {
            log(text, "hint");
            lines.get(lines.size() - 1).put("command", command);
        }

        /** phase: waiting | thinking | replying | writing | running | console. */
        public synchronized void activity(String phase, String label, String detail, Double progress) {
            boolean same = activity != null && phase.equals(activity.path("phase").asText()) && label.equals(activity.path("label").asText());
            if (!same) activitySince = System.currentTimeMillis();
            activity = ShowFiles.object().put("phase", phase).put("label", label).put("detail", detail == null ? "" : detail);
            if (progress != null) activity.put("progress", progress);
            else activity.putNull("progress");
        }

        public boolean running() {
            return state.equals("running");
        }

        public boolean cancelled() {
            return cancelRequested;
        }

        public synchronized ObjectNode view(int since) {
            ObjectNode o = ShowFiles.object().put("id", id).put("kind", kind).put("show", show).put("state", state);
            o.put("error", error);
            o.set("result", result);
            ArrayNode a = o.putArray("lines");
            for (int i = Math.max(0, since); i < lines.size(); i++) a.add(lines.get(i));
            o.put("next", lines.size());
            o.put("elapsed", Math.round(((ended > 0 ? ended : System.currentTimeMillis()) - started) / 100.0) / 10.0);
            if (activity != null && state.equals("running")) {
                ObjectNode act = activity.deepCopy();
                act.put("for", Math.round((System.currentTimeMillis() - activitySince) / 100.0) / 10.0);
                o.set("activity", act);
            } else {
                o.putNull("activity");
            }
            return o;
        }
    }

    public static final class BusyException extends Exception {
        BusyException(String message) {
            super(message);
        }
    }

    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private volatile Task current;

    public synchronized Task start(String kind, String show, Work work) throws BusyException {
        if (current != null && current.state.equals("running")) throw new BusyException("'" + current.kind + "' is still running");
        Task task = new Task(kind, show);
        tasks.put(task.id, task);
        while (tasks.size() > 50) tasks.remove(tasks.keySet().iterator().next());
        current = task;
        Thread t = new Thread(() -> {
            try {
                task.result = work.run(task);
                task.state = task.cancelRequested ? "cancelled" : "ok";
            } catch (ShowException e) {
                task.error = e.getMessage();
                task.state = task.cancelRequested ? "cancelled" : "error";
                task.log(e.getMessage(), "error");
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                task.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                task.state = "error";
                task.log(sw.toString(), "error");
            } finally {
                task.ended = System.currentTimeMillis();
            }
        }, "show-" + kind);
        t.setDaemon(true);
        t.start();
        return task;
    }

    public synchronized Task get(String id) {
        return tasks.get(id);
    }

    public Task current() {
        return current;
    }

    public synchronized Task cancel(String id) {
        Task t = tasks.get(id);
        if (t == null) return null;
        t.cancelRequested = true;
        Runnable r = t.onCancel;
        if (r != null) r.run();
        return t;
    }
}
