package automa3.music;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.DataReference;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** A CDJ-3000 still analysing a track hands out a full-length waveform that is only filled up to where it got. */
class WaveformFillTest {

    private static final DataReference REF = new DataReference(2, CdjStatus.TrackSourceSlot.USB_SLOT, 21, CdjStatus.TrackType.UNANALYZED);

    /** Three-band waveform of {@code frames} half-frames with levels in the first {@code filled}. */
    private static WaveformDetail threeBand(int frames, int filled) {
        ByteBuffer b = ByteBuffer.allocate(frames * 3);
        for (int f = 0; f < filled; f++) {
            b.put(f * 3, (byte) 120);
            b.put(f * 3 + 1, (byte) 60);
            b.put(f * 3 + 2, (byte) 30);
        }
        return new WaveformDetail(REF, b, WaveformFinder.WaveformStyle.THREE_BAND);
    }

    /** 400 beats at 120 BPM: the last beat at 199.5 s = half-frame 29925. */
    private static BeatGrid grid() {
        int n = 400;
        int[] bwb = new int[n], bpm = new int[n];
        long[] time = new long[n];
        for (int i = 0; i < n; i++) {
            bwb[i] = i % 4 + 1;
            bpm[i] = 12000;
            time[i] = i * 500L;
        }
        return new BeatGrid(REF, bwb, bpm, time);
    }

    @Test
    void halfAnalysedWaveformIsNotComplete() {
        BeatGrid grid = grid();
        WaveformDetail partial = threeBand(30000, 720); // the first 24 beats, the rest zeros
        assertEquals(719, ProDjLinkSource.lastFilledFrame(partial));
        assertTrue(ProDjLinkSource.filledShare(partial, grid) < 0.05);
        WaveformDetail full = threeBand(30000, 29900);
        assertTrue(ProDjLinkSource.filledShare(full, grid) > 0.99);
    }

    @Test
    void theFurtherFilledWaveformWins() {
        BeatGrid grid = grid();
        WaveformDetail partial = threeBand(30000, 720), full = threeBand(30000, 29900);
        assertSame(full, ProDjLinkSource.better(partial, full, grid));
        assertSame(full, ProDjLinkSource.better(full, partial, grid));
        assertSame(partial, ProDjLinkSource.better(partial, null, grid));
        assertEquals(-1, ProDjLinkSource.lastFilledFrame(threeBand(100, 0)));
    }
}
