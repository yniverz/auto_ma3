package automa3.music;

import automa3.config.ConfigStore;
import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.DeviceAnnouncement;
import org.deepsymmetry.beatlink.DeviceFinder;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.deepsymmetry.beatlink.BeatFinder;
import org.deepsymmetry.beatlink.data.AnalysisTagFinder;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.BeatGridFinder;
import org.deepsymmetry.beatlink.data.CrateDigger;
import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.beatlink.data.TimeFinder;
import org.deepsymmetry.beatlink.data.TrackMetadata;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live data from Pioneer / AlphaTheta players over Pro DJ Link, using Deep Symmetry's beat-link
 * (the library behind Beat Link Trigger).
 */
public class ProDjLinkSource implements MusicSource {

    private static final Logger log = LoggerFactory.getLogger(ProDjLinkSource.class);

    private final ConfigStore configStore;
    private volatile MusicListener listener;
    private volatile String status = "not started";
    private volatile boolean running;
    private Thread starter;
    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "track-analysis");
        t.setDaemon(true);
        return t;
    });
    /** player -> signature of the last structure sent, to avoid duplicates. */
    private final Map<Integer, String> sentStructure = new ConcurrentHashMap<>();
    private final Map<Integer, String> trackKeys = new ConcurrentHashMap<>();

    public ProDjLinkSource(ConfigStore configStore) {
        this.configStore = configStore;
    }

    @Override
    public String name() {
        return "Pro DJ Link";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void start(MusicListener listener) {
        this.listener = listener;
        running = true;
        starter = new Thread(this::connectLoop, "djlink-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                status = "searching for DJ Link devices (check Ethernet)...";
                if (VirtualCdj.getInstance().start()) {
                    startFinders();
                    status = "connected via " + VirtualCdj.getInstance().getLocalAddress().getHostAddress()
                            + " as player " + VirtualCdj.getInstance().getDeviceNumber();
                    log.info("Pro DJ Link: {}", status);
                    return;
                }
                status = "no DJ Link devices found, retrying";
            } catch (Exception e) {
                status = "error: " + e.getMessage();
                log.warn("Pro DJ Link start failed: {}", e.toString());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void startFinders() throws Exception {
        VirtualCdj.getInstance().addUpdateListener(this::onUpdate);
        BeatFinder.getInstance().start();
        BeatFinder.getInstance().addBeatListener(this::onBeatPacket);

        MetadataFinder.getInstance().start();
        CrateDigger.getInstance().start();
        BeatGridFinder.getInstance().start();
        WaveformFinder.getInstance().setFindDetails(true);
        WaveformFinder.getInstance().setPreferredStyle(WaveformFinder.WaveformStyle.THREE_BAND);
        WaveformFinder.getInstance().start();
        TimeFinder.getInstance().start();
        AnalysisTagFinder.getInstance().start();

        MetadataFinder.getInstance().addTrackMetadataListener(u -> scheduleAnalysis(u.player));
        BeatGridFinder.getInstance().addBeatGridListener(u -> scheduleAnalysis(u.player));
        WaveformFinder.getInstance().addWaveformListener(new org.deepsymmetry.beatlink.data.WaveformListener() {
            @Override
            public void previewChanged(org.deepsymmetry.beatlink.data.WaveformPreviewUpdate update) {
            }

            @Override
            public void detailChanged(org.deepsymmetry.beatlink.data.WaveformDetailUpdate update) {
                scheduleAnalysis(update.player);
            }
        });
        AnalysisTagFinder.getInstance().addAnalysisTagListener(u -> scheduleAnalysis(u.player), ".EXT", "PSSI");
    }

    private boolean mixerPresent() {
        for (DeviceAnnouncement d : DeviceFinder.getInstance().getCurrentDevices()) {
            String n = d.getDeviceName();
            if (n != null && n.toUpperCase().contains("DJM")) return true;
        }
        return false;
    }

    private void onUpdate(DeviceUpdate update) {
        if (!(update instanceof CdjStatus s)) return;
        MusicListener l = listener;
        if (l == null) return;
        int player = s.getDeviceNumber();
        String key = s.isTrackLoaded() && s.getRekordboxId() != 0
                ? s.getTrackSourcePlayer() + ":" + s.getTrackSourceSlot() + ":" + s.getRekordboxId() : null;
        String previous = key == null ? trackKeys.remove(player) : trackKeys.put(player, key);
        if (key != null && !key.equals(previous)) scheduleAnalysis(player);

        TrackMetadata md = MetadataFinder.getInstance().isRunning() ? MetadataFinder.getInstance().getLatestMetadataFor(player) : null;
        String title = md != null ? md.getTitle() : null;
        String artist = md != null && md.getArtist() != null ? md.getArtist().label : null;
        int beat = s.getBeatNumber();
        BeatGrid grid = BeatGridFinder.getInstance().isRunning() ? BeatGridFinder.getInstance().getLatestBeatGridFor(player) : null;
        int beatWithinBar = grid != null && beat > 0 && beat <= grid.beatCount ? grid.getBeatWithinBar(beat) : s.getBeatWithinBar();
        l.onDeck(new DeckState(player, s.getDeviceName(), s.isPlaying(), s.isOnAir(), mixerPresent(),
                s.isTempoMaster(), s.getEffectiveTempo(), beat > 0 && beat < 100000 ? beat : -1, beatWithinBar,
                key, title, artist, System.currentTimeMillis()));
    }

    private void onBeatPacket(Beat beat) {
        MusicListener l = listener;
        if (l == null) return;
        int player = beat.getDeviceNumber();
        if (player > 16) return; // mixer or rekordbox
        long now = System.nanoTime();
        int beatNumber = -1;
        int beatWithinBar = beat.getBeatWithinBar();
        BeatGrid grid = BeatGridFinder.getInstance().getLatestBeatGridFor(player);
        if (grid != null && TimeFinder.getInstance().isRunning()) {
            long ms = TimeFinder.getInstance().getTimeFor(player);
            if (ms >= 0) {
                // the packet marks the start of a beat: look slightly ahead to land inside it
                beatNumber = grid.findBeatAtTime(ms + 40);
                if (beatNumber > 0 && beatNumber <= grid.beatCount) beatWithinBar = grid.getBeatWithinBar(beatNumber);
            }
        }
        if (beatNumber <= 0) {
            DeviceUpdate u = TimeFinder.getInstance().getLatestUpdateFor(player);
            if (u instanceof CdjStatus s && s.getBeatNumber() > 0) beatNumber = s.getBeatNumber() + 1;
        }
        l.onBeat(new BeatEvent(player, beatNumber, beatWithinBar, beat.getEffectiveTempo(), now));
    }

    private void scheduleAnalysis(int player) {
        analysisWorker.submit(() -> {
            try {
                analyze(player);
            } catch (Exception e) {
                log.warn("Track analysis for player {} failed: {}", player, e.toString());
            }
        });
    }

    private void analyze(int player) {
        MusicListener l = listener;
        String key = trackKeys.get(player);
        if (l == null || key == null) return;
        int dropBeats = configStore.get().engine.dropBars * 4;

        BeatGrid grid = BeatGridFinder.getInstance().getLatestBeatGridFor(player);
        if (grid == null || grid.beatCount < 8) return;
        int firstDownbeat = 1;
        for (int b = 1; b <= Math.min(8, grid.beatCount); b++) {
            if (grid.getBeatWithinBar(b) == 1) {
                firstDownbeat = b;
                break;
            }
        }
        WaveformDetail detail = WaveformFinder.getInstance().getLatestDetailFor(player);
        List<WaveformAnalyzer.BarBands> bars = detail != null ? barBands(detail, grid, firstDownbeat) : List.of();
        List<Double> energy = List.of();
        if (!bars.isEmpty()) {
            energy = WaveformAnalyzer.analyze(key, bars, firstDownbeat, dropBeats).barEnergy();
        }

        TrackStructure structure = null;
        RekordboxAnlz.TaggedSection tag = AnalysisTagFinder.getInstance().getLatestTrackAnalysisFor(player, ".EXT", "PSSI");
        if (tag != null && tag.body() instanceof RekordboxAnlz.SongStructureTag sst) {
            structure = fromPhrases(key, sst, grid.beatCount + 1, energy, firstDownbeat, dropBeats);
        }
        if (structure == null && !bars.isEmpty() && configStore.get().djLink.waveformAnalysis) {
            structure = WaveformAnalyzer.analyze(key, bars, firstDownbeat, dropBeats);
        }
        if (structure == null || structure.segments().isEmpty()) return;

        String sig = key + "|" + structure.source() + "|" + structure.segments().size() + "|" + structure.barEnergy().size();
        if (sig.equals(sentStructure.put(player, sig))) return;
        log.info("Player {}: {} sections from {}", player, structure.segments().size(), structure.source());
        l.onStructure(player, structure);
    }

    static TrackStructure fromPhrases(String key, RekordboxAnlz.SongStructureTag tag, int fallbackEnd,
                                      List<Double> energy, int firstDownbeat, int dropBeats) {
        RekordboxAnlz.SongStructureBody body = tag.body();
        if (body == null || body.entries() == null || body.entries().isEmpty()) return null;
        PhraseMapper.Mood mood = switch (body.mood() == null ? RekordboxAnlz.TrackMood.MID : body.mood()) {
            case HIGH -> PhraseMapper.Mood.HIGH;
            case LOW -> PhraseMapper.Mood.LOW;
            default -> PhraseMapper.Mood.MID;
        };
        List<PhraseMapper.Phrase> phrases = new ArrayList<>();
        for (RekordboxAnlz.SongStructureEntry e : body.entries()) {
            String kind;
            Object k = e.kind();
            if (k instanceof RekordboxAnlz.PhraseHigh h) kind = h.id() == null ? "VERSE" : h.id().name();
            else if (k instanceof RekordboxAnlz.PhraseMid m) kind = m.id() == null ? "VERSE" : m.id().name();
            else if (k instanceof RekordboxAnlz.PhraseLow lo) kind = lo.id() == null ? "VERSE" : lo.id().name();
            else kind = "VERSE";
            phrases.add(new PhraseMapper.Phrase(e.beat(), kind));
        }
        int end = body.endBeat() > 0 ? body.endBeat() : fallbackEnd;
        return PhraseMapper.map(key, mood, phrases, end, energy, firstDownbeat, dropBeats);
    }

    /** Average band heights per bar from the waveform detail, using the beat grid for bar times. */
    static List<WaveformAnalyzer.BarBands> barBands(WaveformDetail detail, BeatGrid grid, int firstDownbeat) {
        List<WaveformAnalyzer.BarBands> bars = new ArrayList<>();
        int frames = detail.getFrameCount();
        ByteBuffer data = detail.getData();
        for (int b = firstDownbeat; b + 4 <= grid.beatCount; b += 4) {
            int f0 = (int) (grid.getTimeWithinTrack(b) * 150 / 1000);
            int f1 = (int) (grid.getTimeWithinTrack(b + 4) * 150 / 1000);
            f0 = Math.max(0, Math.min(frames, f0));
            f1 = Math.max(f0, Math.min(frames, f1));
            if (f1 - f0 < 1) break;
            double lo = 0, mid = 0, hi = 0;
            for (int f = f0; f < f1; f++) {
                switch (detail.style) {
                    case THREE_BAND -> {
                        lo += detail.segmentHeight(f, 1, WaveformFinder.ThreeBandLayer.LOW);
                        mid += detail.segmentHeight(f, 1, WaveformFinder.ThreeBandLayer.MID);
                        hi += detail.segmentHeight(f, 1, WaveformFinder.ThreeBandLayer.HIGH);
                    }
                    case RGB -> {
                        int bits = ((data.get(f * 2) & 0xff) << 8) | (data.get(f * 2 + 1) & 0xff);
                        int height = (bits >> 2) & 0x1f;
                        lo += height * ((bits >> 13) & 7) / 7.0;
                        mid += height * ((bits >> 10) & 7) / 7.0;
                        hi += height * ((bits >> 7) & 7) / 7.0;
                    }
                    default -> {
                        int height = data.get(f) & 0x1f;
                        lo += height;
                        mid += height;
                        hi += ((data.get(f) & 0xe0) >> 5) * height / 7.0;
                    }
                }
            }
            int n = f1 - f0;
            bars.add(new WaveformAnalyzer.BarBands(lo / n, mid / n, hi / n));
        }
        return bars;
    }

    @Override
    public void stop() {
        running = false;
        if (starter != null) starter.interrupt();
        try {
            VirtualCdj.getInstance().stop();
            DeviceFinder.getInstance().stop();
        } catch (Exception ignored) {
            // shutting down
        }
        analysisWorker.shutdownNow();
    }
}
