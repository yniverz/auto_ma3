package automa3.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Limits how long and how often a hazardous effect (strobe, blinder) may run:
 * maximum on-time per burst, minimum gap between bursts and maximum duty cycle per minute.
 */
public class SafetyLimiter {

    private record Burst(long startMs, long endMs) {
    }

    private final Deque<Burst> history = new ArrayDeque<>();

    /**
     * Ask to run a burst of {@code wantedMs} starting at {@code nowMs}.
     *
     * @return the allowed duration in ms (possibly shortened), or 0 if not allowed now
     */
    public long request(long nowMs, long wantedMs, double maxOnSec, double minGapSec, double maxDutyPerMinute) {
        while (!history.isEmpty() && history.peekFirst().endMs < nowMs - 60_000) history.removeFirst();
        Burst last = history.peekLast();
        if (last != null && nowMs < last.endMs + (long) (minGapSec * 1000)) return 0;
        long allowed = Math.min(wantedMs, (long) (maxOnSec * 1000));
        long usedMs = 0;
        for (Burst b : history) {
            long s = Math.max(b.startMs, nowMs - 60_000);
            usedMs += Math.max(0, Math.min(b.endMs, nowMs) - s);
        }
        long budget = (long) (maxDutyPerMinute * 60_000) - usedMs;
        allowed = Math.min(allowed, budget);
        if (allowed < 100) return 0;
        history.addLast(new Burst(nowMs, nowMs + allowed));
        return allowed;
    }
}
