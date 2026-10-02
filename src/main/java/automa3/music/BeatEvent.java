package automa3.music;

/**
 * A beat played by a player.
 *
 * @param beatNumber beat number in the track's grid (1-based), -1 if unknown
 * @param beatWithinBar 1..4
 * @param bpm effective tempo
 * @param timeNanos {@link System#nanoTime()} when the beat happened
 */
public record BeatEvent(int player, int beatNumber, int beatWithinBar, double bpm, long timeNanos) {

    public double beatPeriodMs() {
        return bpm > 0 ? 60000.0 / bpm : 500;
    }
}
