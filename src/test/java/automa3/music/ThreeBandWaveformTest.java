package automa3.music;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ThreeBandWaveformTest {

    /** A PWV7 tag as a player sends it: some leading bytes, the tag header, then the entries. */
    private static ByteBuffer blob(int leading, int lenHeader, int[][] entries) {
        ByteBuffer b = ByteBuffer.allocate(leading + lenHeader + entries.length * 3);
        for (int i = 0; i < leading; i++) b.put((byte) 0xEE);
        b.put(new byte[]{'P', 'W', 'V', '7'});
        b.putInt(lenHeader);
        b.putInt(lenHeader + entries.length * 3);
        b.putInt(3);
        b.putInt(entries.length);
        for (int i = 20; i < lenHeader; i++) b.put((byte) 0x55); // rest of the header
        b.put(new byte[entries.length * 3]);
        // each band at its position within the entry
        int base = leading + lenHeader;
        for (int i = 0; i < entries.length; i++) {
            b.put(base + i * 3 + ThreeBandWaveform.BYTE_LOW, (byte) entries[i][0]);
            b.put(base + i * 3 + ThreeBandWaveform.BYTE_MID, (byte) entries[i][1]);
            b.put(base + i * 3 + ThreeBandWaveform.BYTE_HIGH, (byte) entries[i][2]);
        }
        return b.flip();
    }

    @Test
    void readsBandsAtTheRightPositionWhateverTheHeaderLength() {
        int[][] entries = {{200, 20, 5}, {10, 150, 60}, {0, 30, 240}}; // low, mid, high
        for (int leading : new int[]{0, 4, 7}) {
            for (int lenHeader : new int[]{24, 28, 32}) {
                ThreeBandWaveform w = ThreeBandWaveform.fromTag(blob(leading, lenHeader, entries));
                assertNotNull(w, leading + "/" + lenHeader);
                assertEquals(3, w.frames());
                for (int i = 0; i < entries.length; i++) {
                    assertEquals(entries[i][0], w.low(i), "low");
                    assertEquals(entries[i][1], w.mid(i), "mid");
                    assertEquals(entries[i][2], w.high(i), "high");
                }
            }
        }
    }

    @Test
    void noTagNoData() {
        assertNull(ThreeBandWaveform.fromTag(ByteBuffer.wrap(new byte[64])));
    }
}
