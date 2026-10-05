package automa3.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the lighting operator asked for, from the console (OSC) or the web UI.
 * Written from any thread, read by the engine thread.
 */
public class OperatorState {

    /** Engine drives the show. */
    public volatile boolean auto = true;
    /** Freeze the current looks (BPM sync keeps running). */
    public volatile boolean hold = false;
    public volatile boolean strobeAllowed = true;
    /** Specials (CO2, sparkular...) only fire when armed. */
    public volatile boolean specialsArmed = false;
    /** -0.5 (calmer) .. +0.5 (harder). */
    public volatile double energyBias = 0;
    /** Request: trigger a drop on the next downbeat. */
    public volatile boolean dropRequested = false;
    /** Request: change a look on the next beat. */
    public volatile boolean nextRequested = false;
    /** Player the operator chose to drive the lights; 0 = automatic. */
    public volatile int followPlayer = 0;
    /** layer -> time (ms) until which the engine leaves that layer alone. */
    public final Map<String, Long> layerLocks = new ConcurrentHashMap<>();

    public void setEnergyBias(double v) {
        energyBias = Math.max(-0.5, Math.min(0.5, v));
    }
}
