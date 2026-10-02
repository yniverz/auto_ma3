package automa3.engine;

import automa3.config.Config.Look;
import automa3.model.Section;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks looks with variety: weighted random, penalising recently used looks so the 20th drop
 * does not look like the first. Falls back step by step (ignore energy, then section) so a
 * minimal setup with one look per role always works.
 */
public class LookSelector {

    private final Random random;
    /** look id -> pick counter value when it was last used. */
    private final Map<String, Long> lastUsed = new HashMap<>();
    private long counter;

    public LookSelector(Random random) {
        this.random = random;
    }

    public static boolean fits(Look l, Section section, double energy) {
        return fitsSection(l, section) && energy >= l.energyMin - 1e-9 && energy <= l.energyMax + 1e-9;
    }

    public static boolean fitsSection(Look l, Section section) {
        return l.sections == null || l.sections.isEmpty() || l.sections.contains(section);
    }

    /** Candidates for a section/energy with fallbacks; empty only if the list is empty. */
    public static List<Look> candidates(List<Look> pool, Section section, double energy) {
        List<Look> exact = pool.stream().filter(l -> fits(l, section, energy)).toList();
        if (!exact.isEmpty()) return exact;
        List<Look> bySection = pool.stream().filter(l -> fitsSection(l, section)).toList();
        if (!bySection.isEmpty()) {
            // closest energy range
            double best = Double.MAX_VALUE;
            List<Look> closest = new ArrayList<>();
            for (Look l : bySection) {
                double d = energy < l.energyMin ? l.energyMin - energy : energy - l.energyMax;
                if (d < best - 1e-9) {
                    best = d;
                    closest.clear();
                }
                if (Math.abs(d - best) < 1e-9) closest.add(l);
            }
            return closest;
        }
        return List.of();
    }

    /**
     * Choose one look.
     *
     * @param avoid look to avoid if there is any alternative (e.g. the current one when rotating)
     */
    public Look pick(List<Look> candidates, Look avoid) {
        if (candidates.isEmpty()) return null;
        List<Look> pool = candidates;
        if (avoid != null && candidates.size() > 1) {
            pool = candidates.stream().filter(l -> !l.id.equals(avoid.id)).toList();
        }
        double[] w = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < pool.size(); i++) {
            Look l = pool.get(i);
            Long used = lastUsed.get(l.id);
            double age = used == null ? 100 : counter - used;
            double recency = Math.min(1.0, (age + 1) / (pool.size() + 1.0));
            w[i] = l.weight * recency;
            total += w[i];
        }
        double r = random.nextDouble() * total;
        Look chosen = pool.get(pool.size() - 1);
        for (int i = 0; i < pool.size(); i++) {
            r -= w[i];
            if (r <= 0) {
                chosen = pool.get(i);
                break;
            }
        }
        lastUsed.put(chosen.id, ++counter);
        return chosen;
    }
}
