package automa3.engine;

import java.util.PriorityQueue;

/** Deterministic virtual-time scheduler for engine tests. */
class VirtualScheduler implements TaskScheduler {

    private record Task(long at, long seq, Runnable run, long period) {
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) ->
            a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
    private long now = 1_000_000_000L;
    private long seq;

    @Override
    public long nanoTime() {
        return now;
    }

    @Override
    public long currentTimeMillis() {
        return now / 1_000_000L;
    }

    @Override
    public void execute(Runnable task) {
        queue.add(new Task(now, seq++, task, 0));
    }

    @Override
    public void schedule(Runnable task, long delayNanos) {
        queue.add(new Task(now + Math.max(0, delayNanos), seq++, task, 0));
    }

    @Override
    public void scheduleRepeating(Runnable task, long periodNanos) {
        queue.add(new Task(now + periodNanos, seq++, task, periodNanos));
    }

    @Override
    public void shutdown() {
        queue.clear();
    }

    /** Run all tasks up to (and including) the given absolute time. */
    void runUntil(long timeNanos) {
        while (!queue.isEmpty() && queue.peek().at <= timeNanos) {
            Task t = queue.poll();
            now = Math.max(now, t.at);
            t.run.run();
            if (t.period > 0) queue.add(new Task(t.at + t.period, seq++, t.run, t.period));
        }
        now = Math.max(now, timeNanos);
    }
}
