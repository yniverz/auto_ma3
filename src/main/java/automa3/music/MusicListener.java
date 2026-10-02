package automa3.music;

/**
 * Receives events from a {@link MusicSource}. Called from source threads.
 */
public interface MusicListener {

    void onDeck(DeckState deck);

    void onBeat(BeatEvent beat);

    /** A track's section map became available (or changed). */
    void onStructure(int player, TrackStructure structure);
}
