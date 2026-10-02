package automa3.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional live audio analysis of the mixer's booth / record output. Tells the engine whether the
 * kick is really running right now (DJ filtered the bass, cut a channel, or a track had no analysis)
 * and how loud / bright the music is. Everything keeps working when no audio is connected.
 */
public class AudioAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalyzer.class);

    public record AudioState(boolean running, boolean signal, boolean kick, double kickScore, double pulse,
                             double energy, boolean highsRising, long kickChangedMs, String device) {
        public static final AudioState OFF = new AudioState(false, false, false, 0, 0, 0, false, 0, "");
    }

    private final String deviceFilter;
    private final float sampleRate;
    private volatile boolean running;
    private Thread thread;
    private volatile AudioState state = AudioState.OFF;
    private volatile String error;

    // analysis state (audio thread only)
    private final Biquad lowPass;
    private final Biquad highPass;
    private final double[] lowHist = new double[256];
    private final double[] fullHist = new double[256];
    private final double[] highHist = new double[1024];
    private int histPos, highPos;
    private double lowRef = 1e-4, fullRef = 1e-4;
    private boolean kick;
    private long kickChangedMs = System.currentTimeMillis();

    public AudioAnalyzer(String deviceFilter, float sampleRate) {
        this.deviceFilter = deviceFilter == null ? "" : deviceFilter.trim();
        this.sampleRate = sampleRate;
        this.lowPass = Biquad.lowPass(sampleRate, 140, 0.707);
        this.highPass = Biquad.highPass(sampleRate, 3500, 0.707);
    }

    public static List<String> inputDevices() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(info);
            if (m.getTargetLineInfo().length > 0) names.add(info.getName());
        }
        return names;
    }

    public AudioState state() {
        return state;
    }

    public String error() {
        return error;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "audio-in");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        state = AudioState.OFF;
    }

    private TargetDataLine open(AudioFormat format) throws Exception {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!deviceFilter.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().toLowerCase().contains(deviceFilter.toLowerCase())) {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) return (TargetDataLine) mixer.getLine(info);
                }
            }
            throw new IllegalStateException("No audio input matching \"" + deviceFilter + "\"");
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    private void run() {
        AudioFormat mono = new AudioFormat(sampleRate, 16, 1, true, false);
        AudioFormat stereo = new AudioFormat(sampleRate, 16, 2, true, false);
        TargetDataLine line;
        int channels;
        try {
            try {
                line = open(mono);
                channels = 1;
                line.open(mono, 4096);
            } catch (Exception e) {
                line = open(stereo);
                channels = 2;
                line.open(stereo, 8192);
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("Audio input unavailable: {}", e.getMessage());
            running = false;
            return;
        }
        error = null;
        String deviceName = deviceFilter.isEmpty() ? "default input" : deviceFilter;
        line.start();
        log.info("Audio analysis running on {}", deviceName);
        int block = 512;
        byte[] buf = new byte[block * 2 * channels];
        try {
            while (running) {
                int read = line.read(buf, 0, buf.length);
                if (read <= 0) continue;
                processBlock(buf, read, channels, deviceName);
            }
        } finally {
            line.stop();
            line.close();
        }
    }

    private void processBlock(byte[] buf, int len, int channels, String deviceName) {
        int frames = len / (2 * channels);
        double lowSum = 0, highSum = 0, fullSum = 0;
        for (int i = 0; i < frames; i++) {
            double s = 0;
            for (int c = 0; c < channels; c++) {
                int idx = (i * channels + c) * 2;
                s += (short) ((buf[idx] & 0xff) | (buf[idx + 1] << 8)) / 32768.0;
            }
            s /= channels;
            double lo = lowPass.process(s);
            double hi = highPass.process(s);
            lowSum += lo * lo;
            highSum += hi * hi;
            fullSum += s * s;
        }
        double low = Math.sqrt(lowSum / frames), high = Math.sqrt(highSum / frames), full = Math.sqrt(fullSum / frames);
        lowHist[histPos] = low;
        fullHist[histPos] = full;
        histPos = (histPos + 1) % lowHist.length;
        highHist[highPos] = high;
        highPos = (highPos + 1) % highHist.length;

        // ~1.5 s windows at 44.1 kHz / 512 samples per block (~86 blocks/s)
        int win = 128;
        double lowMean = 0, fullMean = 0;
        for (int i = 0; i < win; i++) {
            int k = Math.floorMod(histPos - 1 - i, lowHist.length);
            lowMean += lowHist[k];
            fullMean += fullHist[k];
        }
        lowMean /= win;
        fullMean /= win;
        double var = 0;
        for (int i = 0; i < win; i++) {
            double d = lowHist[Math.floorMod(histPos - 1 - i, lowHist.length)] - lowMean;
            var += d * d;
        }
        double pulse = lowMean > 1e-6 ? Math.sqrt(var / win) / lowMean : 0;

        // slowly decaying references = "loud part of the night"
        lowRef = Math.max(lowMean, lowRef * 0.99995);
        fullRef = Math.max(fullMean, fullRef * 0.99995);
        double kickScore = lowMean / lowRef;
        boolean signal = fullMean > 0.003;

        boolean newKick = kick ? (kickScore > 0.32 && pulse > 0.22) : (kickScore > 0.45 && pulse > 0.30);
        newKick &= signal;
        if (newKick != kick) {
            kick = newKick;
            kickChangedMs = System.currentTimeMillis();
        }

        // highs rising: last ~2 s vs the ~8 s before
        double recent = 0, before = 0;
        for (int i = 0; i < 172; i++) recent += highHist[Math.floorMod(highPos - 1 - i, highHist.length)];
        for (int i = 172; i < 860; i++) before += highHist[Math.floorMod(highPos - 1 - i, highHist.length)];
        recent /= 172;
        before /= 688;
        boolean rising = signal && recent > before * 1.3 && !kick;

        state = new AudioState(true, signal, kick, kickScore, pulse, Math.min(1, fullMean / fullRef), rising, kickChangedMs, deviceName);
    }

    /** RBJ biquad filter. */
    static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0;
            this.b1 = b1 / a0;
            this.b2 = b2 / a0;
            this.a1 = a1 / a0;
            this.a2 = a2 / a0;
        }

        static Biquad lowPass(double fs, double f, double q) {
            double w = 2 * Math.PI * f / fs, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        static Biquad highPass(double fs, double f, double q) {
            double w = 2 * Math.PI * f / fs, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
