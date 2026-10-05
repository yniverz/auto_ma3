package automa3.music;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.data.WaveformDetail;

import java.nio.ByteBuffer;

/**
 * The CDJ-3000 three-band waveform (rekordbox "PWV7" tag): one entry of {@link #entryBytes} bytes per half-frame
 * (1/150 s), holding the low, mid and high band levels.
 *
 * <p>Data from a player's database server arrives as the whole tag inside a message. Instead of skipping a fixed
 * number of bytes (beat-link skips 28 for every colour format), the tag is located by its four-character code
 * and cut using the header length and entry size stored in the tag, so the bands can never be read shifted.</p>
 */
public final class ThreeBandWaveform {

    /**
     * Position of each band within an entry: low, mid, high. (The reverse-engineered format notes, and beat-link,
     * say mid, high, low; comparing with the player's own display shows low, mid, high.)
     */
    static final int BYTE_LOW = 0, BYTE_MID = 1, BYTE_HIGH = 2;

    private static final byte[] FOURCC = {'P', 'W', 'V', '7'};

    private final ByteBuffer entries;
    private final int entryBytes;
    private final String origin;

    ThreeBandWaveform(ByteBuffer entries, int entryBytes, String origin) {
        this.entries = entries;
        this.entryBytes = Math.max(3, entryBytes);
        this.origin = origin;
    }

    /** The three-band data of a waveform detail, or null if it is not a three-band waveform. */
    public static ThreeBandWaveform of(WaveformDetail detail) {
        if (detail == null || detail.style != org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND) {
            return null;
        }
        if (detail.rawMessage != null && detail.rawMessage.arguments.size() > 3
                && detail.rawMessage.arguments.get(3) instanceof BinaryField blob) {
            ThreeBandWaveform parsed = fromTag(blob.getValue());
            if (parsed != null) return parsed;
        }
        // read from an analysis file: beat-link already extracted the entries using the parsed tag
        return new ThreeBandWaveform(detail.getData(), 3, "analysis file");
    }

    /** Locate the PWV7 tag in a raw message blob and cut out its entries using the lengths stored in it. */
    static ThreeBandWaveform fromTag(ByteBuffer blob) {
        ByteBuffer b = blob.duplicate();
        int start = -1;
        for (int i = 0; i + 20 <= b.limit(); i++) {
            if (b.get(i) == FOURCC[0] && b.get(i + 1) == FOURCC[1] && b.get(i + 2) == FOURCC[2] && b.get(i + 3) == FOURCC[3]) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        int lenHeader = b.getInt(start + 4);
        int entryBytes = b.getInt(start + 12);
        int count = b.getInt(start + 16);
        int from = start + lenHeader;
        if (lenHeader < 20 || entryBytes < 3 || entryBytes > 16 || from > b.limit()) return null;
        int length = (int) Math.min((long) count * entryBytes, b.limit() - from);
        ByteBuffer entries = b.position(from).limit(from + length).slice();
        return new ThreeBandWaveform(entries, entryBytes, "player (tag at byte " + start + ", header " + lenHeader + ")");
    }

    public int frames() {
        return entries.limit() / entryBytes;
    }

    public int low(int frame) {
        return entries.get(frame * entryBytes + BYTE_LOW) & 0xff;
    }

    public int mid(int frame) {
        return entries.get(frame * entryBytes + BYTE_MID) & 0xff;
    }

    public int high(int frame) {
        return entries.get(frame * entryBytes + BYTE_HIGH) & 0xff;
    }

    public String origin() {
        return origin;
    }
}
