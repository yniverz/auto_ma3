package automa3.music;

/**
 * Snapshot of one player as reported over Pro DJ Link (or by the simulator / a replay).
 *
 * @param bpm effective tempo (track BPM with pitch applied)
 * @param beatNumber current beat in the track's beat grid (1-based), or -1 if unknown
 * @param onAir true if the mixer reports this channel audible
 * @param onAirKnown true if any mixer is reporting on-air state at all
 */
public record DeckState(int player, String deviceName, boolean playing, boolean onAir, boolean onAirKnown,
                        boolean tempoMaster, double bpm, int beatNumber, int beatWithinBar,
                        String trackKey, String title, String artist, long updatedMs) {

    public boolean hasTrack() {
        return trackKey != null && !trackKey.isEmpty();
    }
}
