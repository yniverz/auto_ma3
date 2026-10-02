package automa3.engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded scheduling used by the engine. Everything the engine does runs on this one
 * thread, so engine state needs no locking. Tests use a virtual-time implementation.
 */
public interface TaskScheduler {

    long nanoTime();

    default long currentTimeMillis() {
        return nanoTime() / 1_000_000L;
    }

    void execute(Runnable task);

    void schedule(Runnable task, long delayNanos);

    void scheduleRepeating(Runnable task, long periodNanos);

    void shutdown();

    /** Real-time implementation backed by one daemon thread. */
    final class Real implements TaskScheduler {
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void execute(Runnable task) {
            exec.execute(safe(task));
        }

        @Override
        public void schedule(Runnable task, long delayNanos) {
            exec.schedule(safe(task), Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
        }

        @Override
        public void scheduleRepeating(Runnable task, long periodNanos) {
            exec.scheduleAtFixedRate(safe(task), periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void shutdown() {
            exec.shutdownNow();
        }

        private static Runnable safe(Runnable r) {
            return () -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    org.slf4j.LoggerFactory.getLogger(TaskScheduler.class).error("Engine task failed", t);
                }
            };
        }
    }
}
