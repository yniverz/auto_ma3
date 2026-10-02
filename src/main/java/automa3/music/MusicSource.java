package automa3.music;

/**
 * Where music information comes from: real CDJs, the simulator or a recorded session.
 */
public interface MusicSource {

    String name();

    void start(MusicListener listener) throws Exception;

    void stop();

    /** Short human readable status for the UI. */
    String status();
}
