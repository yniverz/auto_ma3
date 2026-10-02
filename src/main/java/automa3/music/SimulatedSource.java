package automa3.music;

import automa3.model.Section;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plays synthetic techno tracks on two virtual decks with DJ-style overlapping transitions.
 * Tracks cycle through the three structure sources (rekordbox phrases, waveform analysis, none)
 * so every path of the engine can be tested without CDJs or a console.
 */
public class SimulatedSource implements MusicSource {

    public record Part(Section section, int bars) {
    }

    public record Template(String name, double bpm, List<Part> parts) {
        public int bars() {
            return parts.stream().mapToInt(Part::bars).sum();
        }
    }

    public static final List<Template> TEMPLATES = List.of(
            new Template("Warehouse Roller", 133, List.of(
                    new Part(Section.INTRO, 16), new Part(Section.GROOVE, 32), new Part(Section.BREAKDOWN, 16),
                    new Part(Section.BUILD, 8), new Part(Section.DROP, 32), new Part(Section.BREAKDOWN, 8),
                    new Part(Section.BUILD, 8), new Part(Section.DROP, 32), new Part(Section.OUTRO, 16))),
            new Template("Peak Time Hammer", 140, List.of(
                    new Part(Section.INTRO, 16), new Part(Section.GROOVE, 48), new Part(Section.BREAKDOWN, 24),
                    new Part(Section.BUILD, 8), new Part(Section.DROP, 48), new Part(Section.OUTRO, 16))),
            new Template("Hypnotic Loop", 128, List.of(
                    new Part(Section.INTRO, 16), new Part(Section.GROOVE, 64), new Part(Section.BREAKDOWN, 16),
                    new Part(Section.DROP, 32), new Part(Section.OUTRO, 16))));

    private static final String[] SOURCES = {"phrase", "waveform", "none"};

    private final double speed;
    private final int dropBeats;
    private volatile MusicListener listener;
    private volatile boolean running;
    private Thread thread;
    private volatile String status = "idle";
    private final Random random = new Random(7);

    private final class SimDeck {
        final int player;
        Template track;
        String source;
        String key;
        boolean playing;
        boolean onAir;
        int beat; // last played beat (1-based)

        SimDeck(int player) {
            this.player = player;
        }

        int totalBeats() {
            return track.bars() * 4;
        }

        int outroStartBeat() {
            int beats = 0;
            for (Part p : track.parts()) {
                if (p.section() == Section.OUTRO) return beats + 1;
                beats += p.bars() * 4;
            }
            return beats + 1;
        }
    }

    public SimulatedSource(double speed, int dropBeats) {
        this.speed = speed <= 0 ? 1 : speed;
        this.dropBeats = dropBeats;
    }

    @Override
    public String name() {
        return "Simulator";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void start(MusicListener listener) {
        this.listener = listener;
        running = true;
        thread = new Thread(this::run, "simulator");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        SimDeck[] decks = {new SimDeck(1), new SimDeck(2)};
        int trackCounter = 0;
        load(decks[0], trackCounter++);
        decks[0].playing = true;
        decks[0].onAir = true;
        int active = 0;
        long nextBeatNanos = System.nanoTime() + 500_000_000L;
        long lastDeckUpdate = 0;

        while (running) {
            SimDeck a = decks[active];
            SimDeck b = decks[1 - active];
            double periodNanos = 60_000_000_000.0 / a.track.bpm() / speed;
            long now = System.nanoTime();
            if (now >= nextBeatNanos) {
                // advance both playing decks by one beat (they are beat-matched to the active deck)
                for (SimDeck d : decks) {
                    if (!d.playing) continue;
                    d.beat++;
                    int bwb = ((d.beat - 1) % 4) + 1;
                    listener.onBeat(new BeatEvent(d.player, d.beat, bwb, a.track.bpm(), now));
                }
                // transition: start the other deck at the outro of the active one
                if (!b.playing && a.beat == a.outroStartBeat() - 1) {
                    load(b, trackCounter++);
                    b.playing = true;
                    b.onAir = true;
                    b.beat = 0;
                }
                if (a.beat >= a.totalBeats()) {
                    a.playing = false;
                    a.onAir = false;
                    active = 1 - active;
                }
                nextBeatNanos += (long) periodNanos;
                status = "playing \"" + decks[active].track.name() + "\" (" + decks[active].source + ") at "
                        + decks[active].track.bpm() + " BPM" + (speed != 1 ? ", " + speed + "x speed" : "");
            }
            if (now - lastDeckUpdate > 200_000_000L) {
                lastDeckUpdate = now;
                for (SimDeck d : decks) {
                    if (d.track == null) continue;
                    listener.onDeck(new DeckState(d.player, "SIM-CDJ", d.playing, d.onAir, true,
                            d.player == decks[active].player, d.track.bpm(), d.beat > 0 ? d.beat : -1,
                            d.beat > 0 ? ((d.beat - 1) % 4) + 1 : 1,
                            d.key, d.track.name(), "AutoMA3 Simulator", System.currentTimeMillis()));
                }
            }
            long sleepNanos = Math.min(nextBeatNanos - System.nanoTime(), 5_000_000L);
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void load(SimDeck deck, int index) {
        deck.track = TEMPLATES.get(index % TEMPLATES.size());
        deck.source = SOURCES[index % SOURCES.length];
        deck.key = "sim:" + index;
        deck.beat = 0;
        listener.onDeck(new DeckState(deck.player, "SIM-CDJ", false, false, true, false, deck.track.bpm(), -1, 1,
                deck.key, deck.track.name(), "AutoMA3 Simulator", System.currentTimeMillis()));
        TrackStructure structure = switch (deck.source) {
            case "phrase" -> exactStructure(deck.key, deck.track, dropBeats);
            case "waveform" -> WaveformAnalyzer.analyze(deck.key, syntheticWaveform(deck.track, random), 1, dropBeats);
            default -> null;
        };
        if (structure != null) listener.onStructure(deck.player, structure);
    }

    public static TrackStructure exactStructure(String key, Template t, int dropBeats) {
        List<TrackStructure.Segment> segs = new ArrayList<>();
        int beat = 1;
        for (Part p : t.parts()) {
            segs.add(new TrackStructure.Segment(beat, beat + p.bars() * 4, p.section(), p.section().name().toLowerCase()));
            beat += p.bars() * 4;
        }
        return new TrackStructure(key, "phrase", TrackStructure.normalize(segs, dropBeats), List.of(), 1);
    }

    /** Bar band energies a real waveform of this template would roughly have. */
    public static List<WaveformAnalyzer.BarBands> syntheticWaveform(Template t, Random random) {
        List<WaveformAnalyzer.BarBands> bars = new ArrayList<>();
        for (Part p : t.parts()) {
            for (int i = 0; i < p.bars(); i++) {
                double progress = (double) i / p.bars();
                double n = random.nextGaussian() * 0.04;
                switch (p.section()) {
                    case INTRO, OUTRO -> bars.add(new WaveformAnalyzer.BarBands(0.08 + n, 0.35 + n, 0.30 + n));
                    case BREAKDOWN -> bars.add(new WaveformAnalyzer.BarBands(0.12 + n, 0.40 + n, 0.25 + n));
                    case BUILD -> bars.add(new WaveformAnalyzer.BarBands(0.10 + n, 0.45 + 0.4 * progress + n, 0.30 + 0.6 * progress + n));
                    case GROOVE -> bars.add(new WaveformAnalyzer.BarBands(0.85 + n, 0.55 + n, 0.50 + n));
                    case DROP, PEAK -> bars.add(new WaveformAnalyzer.BarBands(0.95 + n, 0.80 + n, 0.75 + n));
                }
            }
        }
        return bars;
    }
}
