package automa3.music;

import automa3.config.ConfigStore;
import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.DeviceAnnouncement;
import org.deepsymmetry.beatlink.DeviceFinder;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.Util;
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
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;
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
    private final java.nio.file.Path analysisDir;
    private volatile MusicListener listener;
    private volatile String status = "not started";
    private volatile boolean running;
    private Thread starter;
    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "track-analysis");
        t.setDaemon(true);
        return t;
    });
    /** "player|source" -> signature of the last structure sent, to avoid duplicates. */
    private final Map<String, String> sentStructure = new ConcurrentHashMap<>();
    private final Map<Integer, String> trackKeys = new ConcurrentHashMap<>();
    /** Track keys for which rekordbox phrase analysis was sent. */
    private final java.util.Set<String> phraseSent = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Boolean> lastPlaying = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> lastOnAir = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> analysisPending = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> loggedProblems = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> noPhraseLogged = ConcurrentHashMap.newKeySet();
    /** track key -> time (ms) of explicit phrase requests made, to retry a few times only. */
    private final Map<String, List<Long>> phraseRequests = new ConcurrentHashMap<>();

    /** How often, and how long after loading, to ask again for a beat grid / waveform the player has not delivered. */
    private static final long FETCH_INTERVAL_MS = 5000, FETCH_GIVE_UP_MS = 10 * 60_000;
    /** Beat grid and waveform asked for directly, for tracks the player was still analysing when they were loaded. */
    private record Fetched(String key, BeatGrid grid, WaveformDetail detail) {
    }
    private final Map<Integer, Fetched> fetched = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastFetch = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> fetchPending = ConcurrentHashMap.newKeySet();
    /** track key -> time (ms) its beat grid or waveform was first found missing. */
    private final Map<String, Long> missingSince = new ConcurrentHashMap<>();
    private final ExecutorService fetchWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "track-data-fetch");
        t.setDaemon(true);
        return t;
    });

    public ProDjLinkSource(ConfigStore configStore, java.nio.file.Path analysisDir) {
        this.configStore = configStore;
        this.analysisDir = analysisDir;
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
                            + " (this Mac joins the DJ Link network as virtual player "
                            + VirtualCdj.getInstance().getDeviceNumber() + ")";
                    log.info("Pro DJ Link: {}", status);
                    return;
                }
                status = "no DJ Link devices found, retrying";
            } catch (java.net.BindException e) {
                status = "the DJ Link network ports are in use by another program: is AutoMA3 already running "
                        + "(window or terminal), or rekordbox / Beat Link Trigger on this Mac?";
                log.warn("Pro DJ Link start failed: {}", status);
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

    // listeners kept as fields so stop() can remove them (beat-link finders are singletons)
    private final org.deepsymmetry.beatlink.DeviceUpdateListener updateListener = this::onUpdate;
    private final org.deepsymmetry.beatlink.BeatListener beatListener = this::onBeatPacket;
    private final org.deepsymmetry.beatlink.data.TrackMetadataListener metadataListener = u -> scheduleAnalysis(u.player);
    private final org.deepsymmetry.beatlink.data.BeatGridListener gridListener = u -> scheduleAnalysis(u.player);
    private final org.deepsymmetry.beatlink.data.AnalysisTagListener tagListener = u -> scheduleAnalysis(u.player);
    private final org.deepsymmetry.beatlink.data.WaveformListener waveformListener = new org.deepsymmetry.beatlink.data.WaveformListener() {
        @Override
        public void previewChanged(org.deepsymmetry.beatlink.data.WaveformPreviewUpdate update) {
        }

        @Override
        public void detailChanged(org.deepsymmetry.beatlink.data.WaveformDetailUpdate update) {
            scheduleAnalysis(update.player);
        }
    };

    private void startFinders() throws Exception {
        // a fresh database connection for every request: a player busy analysing a track can answer late, and a
        // late answer left on a reused connection makes every following request fail until restart
        ConnectionManager.getInstance().setIdleLimit(0);
        BeatFinder.getInstance().start();
        BeatFinder.getInstance().addBeatListener(beatListener);

        MetadataFinder.getInstance().start();
        if (configStore.get().djLink.readUsbFiles) {
            // reads export database and analysis files straight off the USB over the network; fails with some
            // rekordbox 7 exports (looks up a date as file name), so off by default: everything is then asked
            // from the players directly, which also delivers phrases and three-band waveforms
            CrateDigger.getInstance().start();
        }
        BeatGridFinder.getInstance().start();
        WaveformFinder.getInstance().setFindDetails(true);
        WaveformFinder.getInstance().setPreferredStyle(WaveformFinder.WaveformStyle.THREE_BAND);
        WaveformFinder.getInstance().start();
        TimeFinder.getInstance().start();
        AnalysisTagFinder.getInstance().start();

        MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
        BeatGridFinder.getInstance().addBeatGridListener(gridListener);
        WaveformFinder.getInstance().addWaveformListener(waveformListener);
        AnalysisTagFinder.getInstance().addAnalysisTagListener(tagListener, ".EXT", "PSSI");
        // only now: status updates trigger track analysis, which needs all finders running
        VirtualCdj.getInstance().addUpdateListener(updateListener);
    }

    private boolean mixerPresent() {
        for (DeviceAnnouncement d : DeviceFinder.getInstance().getCurrentDevices()) {
            String n = d.getDeviceName();
            if (n != null && n.toUpperCase().contains("DJM")) return true;
        }
        return false;
    }

    private void onUpdate(DeviceUpdate update) {
        try {
            handleStatus(update);
        } catch (Exception e) {
            logOnce("Status update from " + update.getDeviceName() + " failed: " + e);
        }
    }

    private void onBeatPacket(Beat beat) {
        try {
            handleBeat(beat);
        } catch (Exception e) {
            logOnce("Beat from " + beat.getDeviceName() + " failed: " + e);
        }
    }

    private void logOnce(String message) {
        if (loggedProblems.add(message)) log.warn(message);
    }

    private void handleStatus(DeviceUpdate update) {
        if (!(update instanceof CdjStatus s)) return;
        MusicListener l = listener;
        if (l == null) return;
        int player = s.getDeviceNumber();
        Boolean was = lastPlaying.put(player, s.isPlaying());
        if (was == null || was != s.isPlaying()) {
            log.info("Player {} ({}): {}", player, s.getDeviceName(), s.isPlaying() ? "playing" : "stopped");
        }
        boolean mixer = mixerPresent();
        Boolean wasOnAir = lastOnAir.put(player, s.isOnAir());
        if (mixer && (wasOnAir == null || wasOnAir != s.isOnAir())) {
            log.info("Player {}: {} (the mixer reports channel {})", player, s.isOnAir() ? "on air" : "off air", player);
        }
        String key = s.isTrackLoaded() && s.getRekordboxId() != 0
                ? s.getTrackSourcePlayer() + ":" + s.getTrackSourceSlot() + ":" + s.getRekordboxId() : null;
        String previous = key == null ? trackKeys.remove(player) : trackKeys.put(player, key);
        if (key != null && !key.equals(previous)) {
            fetched.remove(player);
            scheduleAnalysis(player);
        }
        if (key != null) checkTrackData(s, key);
        // rekordbox phrase data can arrive after the waveform: switch to it as soon as it is there
        if (key != null && !phraseSent.contains(key) && AnalysisTagFinder.getInstance().isRunning()
                && (AnalysisTagFinder.getInstance().getLatestTrackAnalysisFor(player, ".EXT", "PSSI") != null
                || phraseRetryDue(key))) {
            scheduleAnalysis(player);
        }

        TrackMetadata md = MetadataFinder.getInstance().isRunning() ? MetadataFinder.getInstance().getLatestMetadataFor(player) : null;
        String title = md != null ? md.getTitle() : null;
        String artist = md != null && md.getArtist() != null ? md.getArtist().label : null;
        int beat = s.getBeatNumber();
        BeatGrid grid = gridFor(player);
        int beatWithinBar = grid != null && beat > 0 && beat <= grid.beatCount ? grid.getBeatWithinBar(beat) : s.getBeatWithinBar();
        l.onDeck(new DeckState(player, s.getDeviceName(), s.isPlaying(), s.isOnAir(), mixer,
                s.isTempoMaster(), s.getEffectiveTempo(), beat > 0 && beat < 100000 ? beat : -1, beatWithinBar,
                key, title, artist, System.currentTimeMillis()));
    }

    private void handleBeat(Beat beat) {
        MusicListener l = listener;
        if (l == null) return;
        int player = beat.getDeviceNumber();
        if (player > 16) return; // mixer or rekordbox
        long now = System.nanoTime();
        int beatNumber = -1;
        int beatWithinBar = beat.getBeatWithinBar();
        BeatGrid grid = gridFor(player);
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
        if (!analysisPending.add(player)) return; // already queued
        analysisWorker.submit(() -> {
            analysisPending.remove(player);
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

        if (!BeatGridFinder.getInstance().isRunning() || !AnalysisTagFinder.getInstance().isRunning()) return;
        BeatGrid grid = gridFor(player);
        if (grid == null || grid.beatCount < 8) return;
        int firstDownbeat = 1;
        for (int b = 1; b <= Math.min(8, grid.beatCount); b++) {
            if (grid.getBeatWithinBar(b) == 1) {
                firstDownbeat = b;
                break;
            }
        }
        WaveformDetail detail = detailFor(player);
        List<WaveformAnalyzer.BarBands> beats = detail != null ? beatBands(detail, grid) : List.of();
        List<WaveformAnalyzer.BarBands> bars = WaveformAnalyzer.barsFromBeats(beats, firstDownbeat);
        TrackStructure wave = beats.isEmpty() ? null : WaveformAnalyzer.analyzeBeats(key, beats, firstDownbeat, dropBeats);

        TrackStructure phrase = null;
        RekordboxAnlz.TaggedSection tag = AnalysisTagFinder.getInstance().getLatestTrackAnalysisFor(player, ".EXT", "PSSI");
        if (tag == null) tag = requestPhrases(player, key);
        if (tag != null && tag.body() instanceof RekordboxAnlz.SongStructureTag sst) {
            phrase = fromPhrases(key, sst, grid.beatCount + 1, wave == null ? List.of() : wave.barEnergy(),
                    firstDownbeat, dropBeats, bars);
            if (phrase != null) phrase = PhraseMapper.refineWithWaveform(phrase, wave, dropBeats).withBeatBands(beats);
        } else if (tag != null) {
            log.warn("Player {}: phrase section has unexpected type {}", player, tag.body() == null ? null : tag.body().getClass());
        }
        if (phrase == null && wave != null && noPhraseLogged.add(key)) {
            log.info("Player {}: no rekordbox phrase analysis for this track (yet), only the waveform is available. "
                    + "Analyse with Phrase enabled in rekordbox and export to the USB again.", player);
        }

        // send both analyses; the engine uses the one selected in the UI (auto / rekordbox / waveform)
        boolean changed = false;
        if (wave != null && configStore.get().djLink.waveformAnalysis) changed |= send(l, player, key, wave);
        if (phrase != null && !phrase.segments().isEmpty()) {
            changed |= send(l, player, key, phrase);
            phraseSent.add(key);
        }
        if (!changed || bars.isEmpty()) return;

        TrackMetadata md = MetadataFinder.getInstance().getLatestMetadataFor(player);
        List<Long> barStarts = new java.util.ArrayList<>();
        for (int i = 0; i < bars.size(); i++) barStarts.add(grid.getTimeWithinTrack(firstDownbeat + i * 4));
        new AnalysisDump(md == null ? null : md.getTitle(),
                md == null || md.getArtist() == null ? null : md.getArtist().label,
                key, detail == null ? null : detail.style.name(), grid.getBpm(firstDownbeat) / 100.0,
                firstDownbeat, grid.beatCount, phrase != null, barStarts, bars, beats,
                wave == null ? List.of() : wave.segments(), phrase == null ? List.of() : phrase.segments())
                .save(analysisDir);
    }

    /** The beat grid of the loaded track: from beat-link, or asked for directly while beat-link has none. */
    private BeatGrid gridFor(int player) {
        BeatGrid grid = BeatGridFinder.getInstance().isRunning() ? BeatGridFinder.getInstance().getLatestBeatGridFor(player) : null;
        Fetched f = currentFetch(player);
        return grid != null || f == null ? grid : f.grid();
    }

    /** The waveform detail of the loaded track, preferring the three-band one. */
    private WaveformDetail detailFor(int player) {
        WaveformDetail detail = WaveformFinder.getInstance().isRunning() ? WaveformFinder.getInstance().getLatestDetailFor(player) : null;
        Fetched f = currentFetch(player);
        WaveformDetail other = f == null ? null : f.detail();
        if (detail == null) return other;
        boolean otherBetter = other != null && other.style == WaveformFinder.WaveformStyle.THREE_BAND
                && detail.style != WaveformFinder.WaveformStyle.THREE_BAND;
        return otherBetter ? other : detail;
    }

    private Fetched currentFetch(int player) {
        Fetched f = fetched.get(player);
        return f != null && f.key().equals(trackKeys.get(player)) ? f : null;
    }

    /**
     * A CDJ-3000 analyses tracks that were not analysed in rekordbox while they are loaded. beat-link asks for the
     * beat grid and waveform right away and only retries for a short while, so ask again until the player has them.
     */
    private void checkTrackData(CdjStatus s, String key) {
        int player = s.getDeviceNumber();
        if (!MetadataFinder.getInstance().isRunning()) return;
        TrackMetadata md = MetadataFinder.getInstance().getLatestMetadataFor(player);
        if (md == null || md.trackReference.rekordboxId != s.getRekordboxId()
                || md.trackReference.player != s.getTrackSourcePlayer() || md.trackReference.slot != s.getTrackSourceSlot()) {
            return; // beat-link is still asking for the metadata, and keeps doing so by itself
        }
        if (complete(gridFor(player), detailFor(player), md)) return;
        long now = System.currentTimeMillis();
        long since = missingSince.computeIfAbsent(key, k -> {
            log.info("Player {}: no beat grid / waveform for \"{}\" yet (the player may still be analysing it), "
                    + "asking again every {} s", player, md.getTitle(), FETCH_INTERVAL_MS / 1000);
            return now;
        });
        if (now - since > FETCH_GIVE_UP_MS) return;
        Long last = lastFetch.get(player);
        if (last != null && now - last < FETCH_INTERVAL_MS) return;
        if (!fetchPending.add(player)) return;
        lastFetch.put(player, now);
        fetchWorker.submit(() -> {
            try {
                fetchTrackData(player, key, md);
            } catch (Exception e) {
                logOnce("Player " + player + ": asking for beat grid / waveform failed: " + e);
            } finally {
                fetchPending.remove(player);
            }
        });
    }

    /** Beat grid and waveform are there; on a track the player analyses itself, wait for its three-band waveform. */
    private static boolean complete(BeatGrid grid, WaveformDetail detail, TrackMetadata md) {
        return grid != null && detail != null && (detail.style == WaveformFinder.WaveformStyle.THREE_BAND
                || md.trackReference.trackType != CdjStatus.TrackType.UNANALYZED);
    }

    private void fetchTrackData(int player, String key, TrackMetadata md) {
        if (!key.equals(trackKeys.get(player))) return;
        BeatGrid grid = gridFor(player);
        WaveformDetail detail = detailFor(player);
        boolean newGrid = false, newDetail = false;
        if (grid == null) {
            BeatGrid g = BeatGridFinder.getInstance().requestBeatGridFrom(md.trackReference);
            if (g != null && g.beatCount > 0) {
                grid = g;
                newGrid = true;
            }
        }
        if (detail == null || detail.style != WaveformFinder.WaveformStyle.THREE_BAND) {
            WaveformDetail d = WaveformFinder.getInstance().requestWaveformDetailFrom(md.trackReference);
            if (d != null && (detail == null || d.style == WaveformFinder.WaveformStyle.THREE_BAND)) {
                detail = d;
                newDetail = true;
            }
        }
        if (!(newGrid || newDetail) || !key.equals(trackKeys.get(player))) return;
        fetched.put(player, new Fetched(key, grid, detail));
        log.info("Player {}: received {} for \"{}\"", player, !newDetail ? "the beat grid"
                : (newGrid ? "beat grid and " : "") + detail.style.name().toLowerCase().replace('_', '-') + " waveform", md.getTitle());
        scheduleAnalysis(player);
    }

    /** Send a structure unless the same one was already sent for this player. */
    private boolean send(MusicListener l, int player, String key, TrackStructure structure) {
        String sig = key + "|" + structure.segments() + "|" + structure.beatBands().size();
        if (sig.equals(sentStructure.put(player + "|" + structure.source(), sig))) return false;
        log.info("Player {}: {} sections from {}", player, structure.segments().size(),
                structure.source().equals("phrase") ? "rekordbox phrase analysis" : "waveform");
        l.onStructure(player, structure);
        return true;
    }

    private boolean phraseRetryDue(String key) {
        List<Long> tries = phraseRequests.get(key);
        return tries != null && !tries.isEmpty() && tries.size() < 3
                && System.currentTimeMillis() - tries.get(tries.size() - 1) >= 5000;
    }

    /**
     * Ask for the rekordbox phrase analysis of the loaded track directly (a few tries per track) and log
     * the outcome, so it is visible why phrases are missing.
     */
    private RekordboxAnlz.TaggedSection requestPhrases(int player, String key) {
        long now = System.currentTimeMillis();
        List<Long> tries = phraseRequests.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (tries.size() >= 3 || (!tries.isEmpty() && now - tries.get(tries.size() - 1) < 5000)) return null;
        tries.add(now);
        TrackMetadata md = MetadataFinder.getInstance().getLatestMetadataFor(player);
        if (md == null) {
            log.info("Player {}: no track metadata yet, cannot ask for phrase analysis", player);
            return null;
        }
        try {
            RekordboxAnlz.TaggedSection tag = AnalysisTagFinder.getInstance().requestAnalysisTagFrom(md.trackReference, ".EXT", "PSSI");
            log.info("Player {}: phrase analysis request for \"{}\" ({}): {}", player, md.getTitle(), md.trackReference,
                    tag == null ? "nothing returned" : "received");
            return tag;
        } catch (Exception e) {
            log.warn("Player {}: phrase analysis request for \"{}\" failed: {}", player, md.getTitle(), e.toString());
            return null;
        }
    }

    static TrackStructure fromPhrases(String key, RekordboxAnlz.SongStructureTag tag, int fallbackEnd,
                                      List<Double> energy, int firstDownbeat, int dropBeats,
                                      List<WaveformAnalyzer.BarBands> bands) {
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
        return PhraseMapper.map(key, mood, phrases, end, energy, firstDownbeat, dropBeats, bands);
    }

    /** Average band heights per beat (beat 1 = index 0) from the waveform detail, using the beat grid for timing. */
    static List<WaveformAnalyzer.BarBands> beatBands(WaveformDetail detail, BeatGrid grid) {
        List<WaveformAnalyzer.BarBands> out = new ArrayList<>();
        ThreeBandWaveform threeBand = ThreeBandWaveform.of(detail);
        int frames = threeBand != null ? threeBand.frames() : detail.getFrameCount();
        ByteBuffer data = detail.getData();
        for (int b = 1; b < grid.beatCount; b++) {
            int f0 = Util.timeToHalfFrame(grid.getTimeWithinTrack(b));
            int f1 = Util.timeToHalfFrame(grid.getTimeWithinTrack(b + 1));
            f0 = Math.max(0, Math.min(frames, f0));
            f1 = Math.max(f0, Math.min(frames, f1));
            if (f1 - f0 < 1) break;
            double lo = 0, mid = 0, hi = 0;
            for (int f = f0; f < f1; f++) {
                switch (detail.style) {
                    case THREE_BAND -> {
                        lo += threeBand.low(f);
                        mid += threeBand.mid(f);
                        hi += threeBand.high(f);
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
            out.add(new WaveformAnalyzer.BarBands(lo / n, mid / n, hi / n));
        }
        return out;
    }

    @Override
    public void stop() {
        running = false;
        listener = null;
        if (starter != null) starter.interrupt();
        VirtualCdj.getInstance().removeUpdateListener(updateListener);
        BeatFinder.getInstance().removeBeatListener(beatListener);
        MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
        BeatGridFinder.getInstance().removeBeatGridListener(gridListener);
        WaveformFinder.getInstance().removeWaveformListener(waveformListener);
        AnalysisTagFinder.getInstance().removeAnalysisTagListener(tagListener, ".EXT", "PSSI");
        try {
            // stopping the virtual player also stops the finders that depend on it
            VirtualCdj.getInstance().stop();
            BeatFinder.getInstance().stop();
            CrateDigger.getInstance().stop();
            DeviceFinder.getInstance().stop();
        } catch (Exception ignored) {
            // shutting down
        }
        analysisWorker.shutdownNow();
        fetchWorker.shutdownNow();
        status = "stopped";
    }
}
