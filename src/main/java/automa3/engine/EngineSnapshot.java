package automa3.engine;

import automa3.audio.AudioAnalyzer.AudioState;
import automa3.music.TrackStructure;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the engine for the web UI, rebuilt every 100 ms.
 */
public record EngineSnapshot(boolean auto, boolean hold, boolean strobeAllowed, boolean specialsArmed,
                             double energyBias, int primaryPlayer, String section, String sectionReason,
                             double energy, double bpm, String nextSection, int beatsToNext,
                             Map<String, String> activeLooks, Map<String, Long> lockedLayersSec,
                             List<Deck> decks, List<String> events, AudioState audio) {

    public record Deck(int player, String device, boolean playing, boolean onAir, boolean master, boolean primary,
                       double bpm, int beat, String title, String artist, String section, String analysis,
                       List<TrackStructure.Segment> segments, int lastBeat) {
    }

    public static final EngineSnapshot EMPTY = new EngineSnapshot(true, false, true, false, 0, -1, null, "", 0, 0,
            null, -1, Map.of(), Map.of(), List.of(), List.of(), AudioState.OFF);
}
