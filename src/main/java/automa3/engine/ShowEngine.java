package automa3.engine;

import automa3.audio.AudioAnalyzer.AudioState;
import automa3.config.Config;
import automa3.config.Config.Look;
import automa3.config.ConfigStore;
import automa3.ma3.Ma3Action;
import automa3.ma3.OscCodec;
import automa3.model.Role;
import automa3.model.Section;
import automa3.music.BeatEvent;
import automa3.music.BeatMoments;
import automa3.music.DeckState;
import automa3.music.MusicListener;
import automa3.music.TrackStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The brain. On every beat of the deck that is on air it decides what the console does on the
 * next beat and schedules it {@code latencyMs} early so it lands on the beat.
 *
 * <p>Layers: every scene role (BASE, COLOR, MOVEMENT, EFFECT, or custom layer names) keeps one
 * running look chosen for the current section and energy. Events (RISER, ACCENT, STROBE, BLINDER,
 * BLACKOUT, FOG, SPECIAL) are fired around builds and drops, with look-ahead from the track
 * structure, and on short moments inside sections (breaks, bass hits). All engine state is touched
 * only on the scheduler thread.</p>
 */
public class ShowEngine implements MusicListener {

    private static final Logger log = LoggerFactory.getLogger(ShowEngine.class);
    private static final Pattern SEQ_FEEDBACK = Pattern.compile("13\\.13\\.1\\.6\\.(\\d+)$");
    private static final long ECHO_WINDOW_MS = 1000;

    /** A command to send {@code delayMs} after the planned beat time. */
    record Step(double delayMs, Ma3Action action, Look look) {
    }

    private record TrackMoments(String trackKey, List<BeatMoments.Moment> list) {
    }

    private final ConfigStore configStore;
    private final TaskScheduler sched;
    private final Consumer<Ma3Action> out;
    private final OperatorState op;
    private final Supplier<AudioState> audio;
    private final LookSelector selector;

    // music state
    private final Map<Integer, DeckState> decks = new HashMap<>();
    /** player -> analysis source ("phrase" / "waveform" / ...) -> structure. Concurrent: also read by the web UI. */
    private final Map<Integer, Map<String, TrackStructure>> structures = new java.util.concurrent.ConcurrentHashMap<>();
    /** player -> breaks and bass hits of its track. Concurrent: also read by the web UI. */
    private final Map<Integer, TrackMoments> moments = new java.util.concurrent.ConcurrentHashMap<>();
    /** Sections corrected by hand, per song; they replace the analysis. Read by the web UI too (synchronized). */
    private volatile automa3.music.SectionEdits sectionEdits = new automa3.music.SectionEdits();
    /** player -> number of analyses received, so the UI knows when to fetch the graph again. */
    private final Map<Integer, Integer> analysisRevision = new java.util.concurrent.ConcurrentHashMap<>();
    /** "player|analysis source" -> build steps (they depend on where that analysis puts the builds). */
    private final Map<String, TrackMoments> buildSteps = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastBeatNumber = new HashMap<>();
    private final Map<Integer, Long> lastBeatMs = new HashMap<>();
    private int primary = -1;
    /** Why the current deck drives the lights (or why none does), for the UI. */
    private String primaryReason = "no players found";
    private String currentTrackKey;
    private long globalBeat;
    private double lastPeriodMs = 469;
    private double lastBpm;

    // show state
    private Section currentSection;
    private String sectionReason = "";
    private String lastSource;
    private long sectionStartGlobal;
    private long lullStartGlobal = -1;
    private long dropUntilGlobal = -1;
    private String dropReason = "";
    private long strobeCoversGlobal = -1;
    private double currentEnergy;
    private final Map<String, Look> active = new LinkedHashMap<>();
    private final Map<String, Look> trackColors = new HashMap<>();
    private Look activeRiser;
    private int rotationIndex;
    /** Global beat of the last look change; the timed change counts from here. */
    private long rotateFromGlobal;
    /** Global beat when the drop's blinder hit is over and a look changes (-1 = none). */
    private long changeAfterFlashGlobal = -1;
    private boolean needsRefresh = true;
    private boolean wasAuto = true;
    private boolean wasHold = false;
    private Integer lastHazePct;
    /** Since when no deck drives the lights (ms), -1 while music is playing. */
    private long silentSinceMs = -1;
    /** The show's looks were stopped because the music stopped; restore them when it plays again. */
    private boolean stoppedForSilence;
    /** player -> engine time (ms) of its last status update (the deck's own timestamp can come from a replay). */
    private final Map<Integer, Long> lastDeckMs = new HashMap<>();
    private Section upcomingSection;
    private int beatsToUpcoming = -1;

    // limits
    private final SafetyLimiter strobeLimiter = new SafetyLimiter();
    private final SafetyLimiter blinderLimiter = new SafetyLimiter();
    private long lastFogMs = -1_000_000;
    private long lastSpecialMs = -1_000_000;
    private final Deque<Long> specialTimes = new ArrayDeque<>();
    private double lastBpmSent = -1;
    private long lastBpmSentMs;

    private final Map<String, Long> lastCommandMs = new HashMap<>();
    private final Deque<String> events = new ArrayDeque<>();
    private volatile EngineSnapshot snapshot = EngineSnapshot.EMPTY;

    public ShowEngine(ConfigStore configStore, TaskScheduler sched, Consumer<Ma3Action> out, OperatorState op,
                      Supplier<AudioState> audio, Random random) {
        this.configStore = configStore;
        this.sched = sched;
        this.out = out;
        this.op = op;
        this.audio = audio;
        this.selector = new LookSelector(random);
        op.strobeAllowed = configStore.get().safety.strobeAllowedAtStart;
        configStore.onChange(c -> sched.execute(this::onConfigChanged));
        sched.scheduleRepeating(this::tick, 40_000_000L);
    }

    public EngineSnapshot snapshot() {
        return snapshot;
    }

    /** Full analysis (sections, energy and waveform bands) of the track on a player, for the UI. */
    public TrackStructure structure(int player) {
        TrackStructure st = structureFor(player);
        if (st == null || !st.beatBands().isEmpty()) return st;
        // for the graph: borrow the per-beat waveform data from another analysis of the same track
        Map<String, TrackStructure> bySource = structures.get(player);
        if (bySource != null) {
            for (TrackStructure other : bySource.values()) {
                if (Objects.equals(other.trackKey(), st.trackKey()) && !other.beatBands().isEmpty()) {
                    return st.withBeatBands(other.beatBands());
                }
            }
        }
        return st;
    }

    public OperatorState operator() {
        return op;
    }

    // ------------------------------------------------------------------ MusicListener (any thread)

    @Override
    public void onDeck(DeckState deck) {
        sched.execute(() -> {
            decks.put(deck.player(), deck);
            lastDeckMs.put(deck.player(), sched.currentTimeMillis());
            if (deck.beatNumber() > 0 && !lastBeatNumber.containsKey(deck.player())) {
                lastBeatNumber.put(deck.player(), deck.beatNumber());
            }
            choosePrimary();
        });
    }

    @Override
    public void onBeat(BeatEvent beat) {
        sched.execute(() -> handleBeat(beat));
    }

    @Override
    public void onStructure(int player, TrackStructure structure) {
        sched.execute(() -> {
            Map<String, TrackStructure> bySource = structures.computeIfAbsent(player, p -> new java.util.concurrent.ConcurrentHashMap<>());
            bySource.values().removeIf(old -> !Objects.equals(old.trackKey(), structure.trackKey())); // new track
            bySource.put(structure.source(), structure);
            analysisRevision.merge(player, 1, Integer::sum);
            // again for every analysis: the waveform of a track the player is still analysing fills in over time
            if (!structure.beatBands().isEmpty()) {
                moments.put(player, new TrackMoments(structure.trackKey(), BeatMoments.detect(structure.beatBands())));
            }
            buildSteps.put(player + "|" + structure.source(), new TrackMoments(structure.trackKey(),
                    structure.beatBands().isEmpty() ? List.of() : BeatMoments.buildSteps(structure)));
            event("Player " + player + ": " + structure.segments().size() + " sections from " + structure.source());
        });
    }

    // ------------------------------------------------------------------ deck selection

    /**
     * Pick the deck that drives the lights: the deck the operator chose, else a playing deck the mixer reports
     * on air. Without a mixer any playing deck; with a mixer but every fader down, no deck ("mixer" mode) or any
     * playing deck ("playing" mode).
     */
    private void choosePrimary() {
        long now = sched.currentTimeMillis();
        int forced = op.followPlayer;
        if (forced > 0) {
            if (decks.containsKey(forced)) {
                setPrimary(forced, "chosen by the operator");
                primaryReason = "chosen by the operator";
            } else {
                primaryReason = "player " + forced + " chosen, but not on the network";
            }
            return;
        }
        boolean onAirKnown = decks.values().stream().anyMatch(DeckState::onAirKnown);
        List<DeckState> playing = new ArrayList<>();
        for (DeckState d : decks.values()) {
            boolean fresh = now - d.updatedMs() < 3000 || now - lastBeatMs.getOrDefault(d.player(), 0L) < 3000;
            if (isPlaying(d, now) && fresh) playing.add(d);
        }
        List<DeckState> onAir = playing.stream().filter(DeckState::onAir).toList();
        boolean faderOnly = onAirKnown && "mixer".equalsIgnoreCase(configStore.get().djLink.deckSelection);
        // with a mixer, decks on air come first; if it reports none (faders down, or players not numbered like
        // their mixer channels), "playing" mode follows a playing deck anyway and "mixer" mode follows none
        List<DeckState> candidates = onAirKnown && !onAir.isEmpty() ? onAir : faderOnly ? List.of() : playing;
        if (candidates.isEmpty()) {
            if (faderOnly && !playing.isEmpty()) {
                primaryReason = "no deck on air: raise a fader, or use Follow";
                clearPrimary(primaryReason);
            } else if (!decks.containsKey(primary) || !isPlaying(decks.get(primary), now)) {
                primaryReason = decks.isEmpty() ? "no players found" : "no deck playing";
            }
            return;
        }
        String basis = !onAirKnown ? "playing" : !onAir.isEmpty() ? "on air" : "playing; mixer reports no deck on air";
        DeckState current = decks.get(primary);
        boolean currentOk = current != null && candidates.stream().anyMatch(d -> d.player() == primary);
        if (currentOk) {
            primaryReason = basis;
            // hand over during a mix once the current track is in its outro
            Section s = sectionOf(primary);
            if (s != Section.OUTRO || candidates.size() < 2) return;
            DeckState other = candidates.stream().filter(d -> d.player() != primary)
                    .filter(d -> sectionOf(d.player()) != Section.OUTRO).findFirst().orElse(null);
            if (other == null) return;
            setPrimary(other.player(), "outro of player " + primary);
            return;
        }
        DeckState pick = candidates.stream().filter(DeckState::tempoMaster).findFirst()
                .orElse(candidates.stream().min((a, b) -> Integer.compare(a.player(), b.player())).orElseThrow());
        primaryReason = basis;
        setPrimary(pick.player(), (current == null ? "first deck " : "player " + primary + " stopped / off air, deck ") + basis);
    }

    /** Playing per the status flag, or beats arrived recently (beat packets are only sent while playing). */
    private boolean isPlaying(DeckState d, long nowMs) {
        return d.playing() || nowMs - lastBeatMs.getOrDefault(d.player(), Long.MIN_VALUE / 2) < 2000;
    }

    private void setPrimary(int player, String why) {
        if (player == primary) return;
        primary = player;
        event("Following player " + player + " (" + why + ")");
    }

    /** No deck drives the lights: the running looks stay, nothing new is triggered. */
    private void clearPrimary(String why) {
        if (primary < 0) return;
        primary = -1;
        event("Following no deck (" + why + ")");
    }

    private Section sectionOf(int player) {
        TrackStructure st = structureFor(player);
        Integer beat = lastBeatNumber.get(player);
        if (st == null || beat == null) return null;
        TrackStructure.Segment seg = st.segmentAt(beat);
        return seg == null ? null : seg.section();
    }

    /**
     * The sections of the player's current track: the operator's correction if there is one (on top of the
     * analysis' waveform data), else the analysis selected by the analysis mode.
     */
    private TrackStructure structureFor(int player) {
        TrackStructure st = analysisFor(player);
        automa3.music.SectionEdits.Edit edit = editFor(player);
        if (edit == null) return st;
        DeckState d = decks.get(player);
        return st == null
                ? new TrackStructure(d.trackKey(), "edited", edit.segments(), List.of(), 1)
                : new TrackStructure(st.trackKey(), st.source(), edit.segments(), st.barEnergy(), st.firstDownbeat(), st.bands(), st.beatBands());
    }

    private automa3.music.SectionEdits.Edit editFor(int player) {
        DeckState d = decks.get(player);
        return d == null ? null : sectionEdits.get(automa3.music.SectionEdits.songKey(d.title(), d.artist(), d.trackKey()));
    }

    public void setSectionEdits(automa3.music.SectionEdits edits) {
        this.sectionEdits = edits;
    }

    /**
     * Replace the sections of the track on a player (dragged transitions, changed types). Applies from the next
     * beat and to the same song on any player, also next time. Returns the stored sections.
     */
    public java.util.concurrent.CompletableFuture<List<TrackStructure.Segment>> editSections(int player, List<TrackStructure.Segment> segments) {
        return onEngine(() -> {
            DeckState d = decks.get(player);
            if (d == null || d.trackKey() == null) throw new IllegalArgumentException("no track on player " + player);
            TrackStructure base = analysisFor(player);
            List<TrackStructure.Segment> stored = sectionEdits.put(automa3.music.SectionEdits.songKey(d.title(), d.artist(), d.trackKey()),
                    d.title(), d.artist(), base == null ? null : base.source(), segments);
            analysisRevision.merge(player, 1, Integer::sum);
            event("Player " + player + ": sections edited by hand (" + stored.size() + " sections)");
            return stored;
        });
    }

    /** Forget the hand-made sections of the track on a player: back to the analysis. */
    public java.util.concurrent.CompletableFuture<Boolean> resetSections(int player) {
        return onEngine(() -> {
            DeckState d = decks.get(player);
            if (d == null) return false;
            boolean had = sectionEdits.remove(automa3.music.SectionEdits.songKey(d.title(), d.artist(), d.trackKey()));
            if (had) {
                analysisRevision.merge(player, 1, Integer::sum);
                event("Player " + player + ": sections back to the analysis");
            }
            return had;
        });
    }

    private <T> java.util.concurrent.CompletableFuture<T> onEngine(java.util.concurrent.Callable<T> task) {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        sched.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** The analysis of the player's current track selected by the analysis mode (auto / phrase / waveform). */
    private TrackStructure analysisFor(int player) {
        Map<String, TrackStructure> bySource = structures.get(player);
        DeckState d = decks.get(player);
        if (bySource == null || d == null) return null;
        String mode = configStore.get().djLink.analysisMode == null ? "auto" : configStore.get().djLink.analysisMode;
        List<String> order = mode.equalsIgnoreCase("waveform") ? List.of("waveform", "phrase") : List.of("phrase", "waveform");
        for (String source : order) {
            TrackStructure st = bySource.get(source);
            if (st != null && Objects.equals(st.trackKey(), d.trackKey())) return st;
        }
        for (TrackStructure st : bySource.values()) {
            if (Objects.equals(st.trackKey(), d.trackKey())) return st;
        }
        return null;
    }

    /** Breaks, bass hits and build steps of the player's current track that the show reacts to, for the UI. */
    public List<BeatMoments.Moment> moments(int player) {
        TrackStructure st = structureFor(player);
        if (st == null) return List.of();
        return trackMoments(player).stream().filter(m -> {
            if (m.kind() == BeatMoments.Kind.BREAK || m.kind() == BeatMoments.Kind.STOP) return breakCounts(st, m);
            TrackStructure.Segment seg = st.segmentAt(m.startBeat());
            if (seg == null) return false;
            return m.kind() == BeatMoments.Kind.HIT ? hitCounts(seg.section()) : seg.section() == Section.BUILD;
        }).toList();
    }

    /** All breaks, bass hits and build steps found in the player's current track (steps of the analysis in use). */
    private List<BeatMoments.Moment> trackMoments(int player) {
        DeckState d = decks.get(player);
        TrackStructure st = structureFor(player);
        if (d == null) return List.of();
        List<BeatMoments.Moment> out = new ArrayList<>();
        TrackMoments tm = moments.get(player);
        if (tm != null && Objects.equals(tm.trackKey(), d.trackKey())) out.addAll(tm.list());
        TrackMoments steps = st == null ? null : buildSteps.get(player + "|" + st.source());
        if (steps != null && Objects.equals(steps.trackKey(), d.trackKey())) out.addAll(steps.list());
        out.sort((a, b) -> Integer.compare(a.startBeat(), b.startBeat()));
        return out;
    }

    /** Analyses available for the player's current track. */
    private List<String> availableAnalyses(int player) {
        Map<String, TrackStructure> bySource = structures.get(player);
        DeckState d = decks.get(player);
        if (bySource == null || d == null) return List.of();
        return bySource.values().stream().filter(st -> Objects.equals(st.trackKey(), d.trackKey()))
                .map(TrackStructure::source).sorted().toList();
    }

    // ------------------------------------------------------------------ beat handling

    private void handleBeat(BeatEvent b) {
        lastBeatMs.put(b.player(), sched.currentTimeMillis());
        if (b.beatNumber() > 0) lastBeatNumber.put(b.player(), b.beatNumber());
        if (primary < 0 || decks.get(primary) == null || !isPlaying(decks.get(primary), sched.currentTimeMillis())) choosePrimary();
        if (primary < 0 && decks.isEmpty()) primary = b.player();
        if (b.player() != primary) return;
        globalBeat++;
        Config cfg = configStore.get();
        double period = b.beatPeriodMs();
        lastPeriodMs = period;
        lastBpm = b.bpm();
        updateBpm(cfg, b.bpm());

        long now = sched.nanoTime();
        long fireAt = b.timeNanos() + (long) ((period - cfg.engine.latencyMs) * 1_000_000L);
        if (fireAt < now) fireAt = now;
        int next = b.beatNumber() > 0 ? b.beatNumber() + 1 : -1;
        int nextBwb = (Math.max(1, b.beatWithinBar()) % 4) + 1;

        List<Step> steps = new ArrayList<>();
        plan(cfg, next, nextBwb, globalBeat + 1, period, steps);
        final long base = fireAt;
        for (Step s : steps) {
            long at = base + (long) (s.delayMs() * 1_000_000L);
            sched.schedule(() -> send(s), at - now);
        }
        snapshot = buildSnapshot(cfg); // show the new beat right away
    }

    private void send(Step s) {
        if (s.look() != null) lastCommandMs.put(lookKey(s.look()), sched.currentTimeMillis());
        out.accept(s.action());
    }

    private static String lookKey(Look l) {
        return l.page + "." + l.exec;
    }

    /** Decide everything for the beat {@code next} (track beat, may be -1 if unknown). */
    void plan(Config cfg, int next, int nextBwb, long nextGlobal, double period, List<Step> steps) {
        DeckState deck = decks.get(primary);
        TrackStructure st = structureFor(primary);
        String key = deck == null ? null : deck.trackKey();
        boolean trackChanged = !Objects.equals(key, currentTrackKey);
        if (trackChanged) {
            currentTrackKey = key;
            trackColors.clear();
            if (deck != null && deck.title() != null) event("Track: " + deck.title() + (deck.artist() != null ? " - " + deck.artist() : ""));
        }

        String source = st == null ? "none" : st.source();
        if (!source.equals(lastSource)) {
            if (lastSource != null && !trackChanged) event("Analysis now: " + source);
            lastSource = source;
        }

        // ---- section for the next beat
        TrackStructure.Segment seg = st != null && next > 0 ? st.segmentAt(next) : null;
        Section section;
        String reason;
        if (seg != null) {
            section = seg.section();
            reason = "edited".equals(seg.label()) ? "edited by hand" : st.source() + ": " + seg.label();
        } else if (st != null && next > 0 && !st.segments().isEmpty()) {
            section = next < st.segments().get(0).startBeat() ? Section.INTRO : Section.OUTRO;
            reason = st.source() + ": outside analysed range";
        } else {
            section = Section.GROOVE;
            reason = "no analysis";
        }
        String audioReason = null;
        Section audioSection = applyAudio(section, st != null);
        if (audioSection != section) {
            audioReason = "audio: " + (audioSection == Section.BREAKDOWN ? "kick gone" : audioSection == Section.BUILD ? "highs rising" : "kick running");
            section = audioSection;
        }

        if (audioReason != null) reason = audioReason;

        // kick back after a long lull = drop: without an analysis (live audio), or when the music goes straight to full
        // energy (a DJ filtering the bass out); a groove after a breakdown is not a drop
        boolean lullBefore = currentSection != null && !currentSection.isKicking() && currentSection != Section.INTRO;
        if (section.isKicking() && section != Section.DROP && (st == null || section == Section.PEAK) && lullBefore && lullStartGlobal >= 0
                && nextGlobal - lullStartGlobal >= 32) {
            dropUntilGlobal = nextGlobal + cfg.engine.dropBars * 4L;
            dropReason = "kick returns after " + (nextGlobal - lullStartGlobal) / 4 + " bars";
        }
        if (op.dropRequested && nextBwb == 1) {
            op.dropRequested = false;
            dropUntilGlobal = nextGlobal + cfg.engine.dropBars * 4L;
            dropReason = "operator";
        }
        if (nextGlobal < dropUntilGlobal) {
            if (section.isKicking() || "operator".equals(dropReason)) {
                if (section != Section.DROP) reason = dropReason;
                section = Section.DROP;
            } else {
                dropUntilGlobal = -1; // the music went quiet again
            }
        }

        // ---- energy
        double energy = section.baseEnergy;
        if (section == Section.BUILD && seg != null && seg.section() == Section.BUILD) {
            energy = 0.5 + 0.4 * (double) (next - seg.startBeat()) / Math.max(1, seg.lengthBeats());
        }
        double waveEnergy = st != null && next > 0 ? st.energyAt(next) : Double.NaN;
        if (!Double.isNaN(waveEnergy)) energy = 0.7 * energy + 0.3 * waveEnergy;
        AudioState a = audio.get();
        if (a.running() && a.signal()) energy = 0.8 * energy + 0.2 * a.energy();
        energy = Math.max(0, Math.min(1, energy + op.energyBias));
        currentEnergy = energy;

        // ---- look-ahead
        TrackStructure.Segment upcoming = seg != null ? st.nextAfter(seg) : null;
        upcomingSection = upcoming == null ? null : upcoming.section();
        beatsToUpcoming = upcoming == null ? -1 : upcoming.startBeat() - next;
        int toDrop = beatsToNextDrop(st, seg, next);

        // ---- section change bookkeeping
        boolean sectionChanged = section != currentSection;
        sectionReason = reason; // always current, also after switching the analysis
        if (sectionChanged) {
            if (section.isKicking() || section == Section.INTRO) lullStartGlobal = -1;
            else if (lullStartGlobal < 0) lullStartGlobal = nextGlobal;
            currentSection = section;
            sectionStartGlobal = nextGlobal;
            rotateFromGlobal = nextGlobal;
            changeAfterFlashGlobal = -1; // a new section brings its own looks
            event("Beat " + (next > 0 ? next : "?") + ": " + section + " (" + reason + ")");
        }

        // the riser always stops when the build is over, even in HOLD
        if (sectionChanged && section != Section.BUILD && activeRiser != null) {
            Look r = activeRiser;
            activeRiser = null;
            steps.add(new Step(0, stopAction(r, "build over"), r));
            steps.add(new Step(5, Ma3Action.fader(r.page, r.exec, r.level, 0, "riser reset"), r));
        }

        boolean driving = op.auto && !op.hold;
        if (!driving) return;

        Set<String> force = new HashSet<>();
        if (trackChanged) force.addAll(layersOf(cfg, Role.COLOR));
        if (sectionChanged && section == Section.DROP) {
            for (String layer : sceneLayers(cfg).keySet()) {
                if (!isColorLayer(cfg, layer) || cfg.engine.colorChangeOnDrop) force.add(layer);
            }
        }
        long barsIn = (nextGlobal - sectionStartGlobal) / 4;
        boolean barStart = nextBwb == 1;
        // the drop's blinder hit is over: something new underneath
        if (changeAfterFlashGlobal >= 0 && nextGlobal >= changeAfterFlashGlobal) {
            boolean onTime = nextGlobal - changeAfterFlashGlobal < 4; // not a leftover from before HOLD
            boolean fresh = nextGlobal - rotateFromGlobal < 16; // the look just changed (e.g. kick back from a break)
            changeAfterFlashGlobal = -1;
            if (onTime && !fresh) {
                event("Beat " + next + ": blinder hit over, new look");
                force.add(nextRotationLayer(cfg, section, energy, force));
            }
        }
        if (op.nextRequested) {
            op.nextRequested = false;
            force.add(nextRotationLayer(cfg, section, energy, force));
        }
        // keep long peaks and builds moving: change a layer when the kick returns after a break (after a bar or a
        // stop: a new look on every layer, or two layers) and at each step of a build
        if (st != null && next > 0 && !(sectionChanged && section == Section.DROP)) { // a drop changes everything anyway
            for (BeatMoments.Moment m : trackMoments(primary)) {
                if (m.startBeat() > next) break;
                boolean back = (m.kind() == BeatMoments.Kind.BREAK || m.kind() == BeatMoments.Kind.STOP)
                        && m.endBeat() == next && section.isKicking() && breakCounts(st, m);
                boolean step = m.kind() == BeatMoments.Kind.STEP && m.startBeat() == next && section == Section.BUILD;
                if (back) {
                    boolean longBreak = m.kind() == BeatMoments.Kind.STOP || m.length() >= 4;
                    if (longBreak && cfg.engine.freshLookAfterLongBreak) {
                        event("Beat " + next + ": kick back after a long break, new look");
                        for (String layer : sceneLayers(cfg).keySet()) {
                            if (!isColorLayer(cfg, layer) || !cfg.engine.colorPerTrack) force.add(layer);
                        }
                    } else {
                        force.add(nextRotationLayer(cfg, section, energy, force));
                        if (longBreak) force.add(nextRotationLayer(cfg, section, energy, force));
                    }
                }
                if (step) {
                    event("Beat " + next + ": build gets harder");
                    force.add(nextRotationLayer(cfg, section, energy, force));
                }
            }
        }
        // the timed change, counted from the last change (a break or a flash restarts it, so changes never bunch up)
        if (force.isEmpty() && !sectionChanged && barStart && barsIn > 0 && section != Section.BUILD) {
            int every = section == Section.DROP || section == Section.PEAK ? cfg.engine.rotateBarsPeak : cfg.engine.rotateBarsGroove;
            if (nextGlobal - rotateFromGlobal >= every * 4L) force.add(nextRotationLayer(cfg, section, energy, force));
        }
        force.remove(null);
        if (!force.isEmpty()) rotateFromGlobal = nextGlobal;

        if (sectionChanged || trackChanged || needsRefresh || !force.isEmpty()) {
            needsRefresh = false;
            applyScene(cfg, section, energy, force, steps);
            if (stoppedForSilence && !sectionChanged) {
                // the music plays again: the scene is back, and the haze (and in a build the riser) for this section
                stoppedForSilence = false;
                if (section == Section.BUILD) sectionEvents(cfg, section, seg, next, nextGlobal, toDrop, period, steps);
                else haze(cfg, section, steps);
            }
            stoppedForSilence = false;
        }

        if (sectionChanged) sectionEvents(cfg, section, seg, next, nextGlobal, toDrop, period, steps);
        if (section == Section.DROP && !sectionChanged && barStart && cfg.engine.dropBlinderEveryBars > 0
                && barsIn > 0 && barsIn % cfg.engine.dropBlinderEveryBars == 0) {
            long ms = blinder(cfg, period, 0, steps, "drop phrase");
            if (ms > 0 && cfg.engine.changeAfterDropFlash) changeAfterFlashGlobal = nextGlobal + Math.max(1, Math.round(ms / period));
        }
        lookAhead(cfg, st, toDrop, next, nextGlobal, period, steps);
        momentEvents(cfg, st, section, sectionChanged, next, period, steps);
    }

    // ------------------------------------------------------------------ audio

    private Section applyAudio(Section section, boolean haveStructure) {
        AudioState a = audio.get();
        if (!a.running() || !a.signal()) return section;
        long sinceChange = sched.currentTimeMillis() - a.kickChangedMs();
        double barMs = lastPeriodMs * 4;
        if (section.isKicking() && !a.kick() && sinceChange > 2 * barMs) {
            return a.highsRising() ? Section.BUILD : Section.BREAKDOWN;
        }
        if (!haveStructure) {
            if (a.kick() && sinceChange > barMs) return Section.GROOVE;
            if (!a.kick() && sinceChange > barMs) return a.highsRising() ? Section.BUILD : Section.BREAKDOWN;
        }
        return section;
    }

    // ------------------------------------------------------------------ scene

    private Map<String, List<Look>> sceneLayers(Config cfg) {
        Map<String, List<Look>> layers = new LinkedHashMap<>();
        cfg.looks.stream().filter(l -> l.enabled && l.role.sceneRole)
                .sorted((x, y) -> Integer.compare(x.role.ordinal(), y.role.ordinal()))
                .forEach(l -> layers.computeIfAbsent(l.layerName(), k -> new ArrayList<>()).add(l));
        return layers;
    }

    private boolean isColorLayer(Config cfg, String layer) {
        List<Look> looks = sceneLayers(cfg).get(layer);
        return looks != null && !looks.isEmpty() && looks.get(0).role == Role.COLOR;
    }

    private Set<String> layersOf(Config cfg, Role role) {
        Set<String> out = new HashSet<>();
        sceneLayers(cfg).forEach((layer, looks) -> {
            if (looks.stream().anyMatch(l -> l.role == role)) out.add(layer);
        });
        return out;
    }

    /**
     * The next layer to change, in turn: one that is not changing already and has another look that fits the
     * section and energy (otherwise the change would be lost, e.g. BASE in a drop with a single loud base look).
     */
    private String nextRotationLayer(Config cfg, Section section, double energy, Set<String> taken) {
        Map<String, List<Look>> layers = sceneLayers(cfg);
        List<String> rot = new ArrayList<>();
        layers.forEach((layer, looks) -> {
            boolean color = looks.get(0).role == Role.COLOR;
            if (looks.size() > 1 && (!color || !cfg.engine.colorPerTrack)) rot.add(layer);
        });
        for (int i = 0; i < rot.size(); i++) {
            String layer = rot.get(rotationIndex++ % rot.size());
            if (taken.contains(layer) || locked(layer)) continue;
            Look cur = active.get(layer);
            if (candidates(layers.get(layer), section, energy).stream().anyMatch(l -> cur == null || !l.id.equals(cur.id))) return layer;
        }
        return null;
    }

    /** Looks of a layer that fit the section and energy; effects are optional, so no fallback for them. */
    private static List<Look> candidates(List<Look> looks, Section section, double energy) {
        return looks.get(0).role == Role.EFFECT
                ? looks.stream().filter(l -> LookSelector.fits(l, section, energy)).toList()
                : LookSelector.candidates(looks, section, energy);
    }

    private boolean locked(String layer) {
        Long until = op.layerLocks.get(layer);
        return until != null && until > sched.currentTimeMillis();
    }

    private void applyScene(Config cfg, Section section, double energy, Set<String> force, List<Step> steps) {
        Map<String, List<Look>> layers = sceneLayers(cfg);
        // looks that disappeared from the config: stop them
        for (String layer : new ArrayList<>(active.keySet())) {
            if (!layers.containsKey(layer) && !locked(layer)) {
                Look gone = active.remove(layer);
                steps.add(new Step(0, stopAction(gone, "removed from config"), gone));
            }
        }
        for (Map.Entry<String, List<Look>> e : layers.entrySet()) {
            String layer = e.getKey();
            if (locked(layer)) continue;
            List<Look> looks = e.getValue();
            Look current = active.get(layer);
            if (current != null && looks.stream().noneMatch(l -> l.id.equals(current.id))) {
                steps.add(new Step(0, stopAction(current, "removed from config"), current));
                active.remove(layer);
            }
            Look cur = active.get(layer);
            boolean color = looks.get(0).role == Role.COLOR;
            boolean forced = force.contains(layer);
            Look chosen;
            // effects are optional: no fallback, the layer goes dark when nothing fits
            List<Look> cands = candidates(looks, section, energy);
            Look held = color && cfg.engine.colorPerTrack ? trackColors.get(layer) : null;
            if (held != null && !forced && cands.stream().anyMatch(l -> l.id.equals(held.id))) {
                chosen = held;
            } else if (!forced && cur != null && cands.stream().anyMatch(l -> l.id.equals(cur.id))) {
                chosen = cur;
            } else {
                chosen = selector.pick(cands, forced ? cur : null);
            }
            if (color && chosen != null) trackColors.put(layer, chosen);
            if (chosen != null && cur != null && chosen.id.equals(cur.id)) continue;
            String why = section + (forced ? " (change)" : "");
            if (chosen != null) {
                steps.add(new Step(0, startAction(chosen, why), chosen));
                active.put(layer, chosen);
            } else {
                active.remove(layer);
            }
            if (cur != null && (chosen == null || !lookKey(cur).equals(lookKey(chosen)))) {
                steps.add(new Step(cfg.engine.crossfadeOffDelayMs, stopAction(cur, why), cur));
            }
        }
    }

    // ------------------------------------------------------------------ events

    private void sectionEvents(Config cfg, Section section, TrackStructure.Segment seg, int next, long nextGlobal,
                               int toDrop, double period, List<Step> steps) {
        switch (section) {
            case BUILD -> {
                Look riser = pickEvent(cfg, Role.RISER, section);
                if (riser != null) {
                    double beats = seg != null && seg.section() == Section.BUILD && next > 0 ? seg.endBeat() - next : 32;
                    double sec = Math.max(1, beats * period / 1000);
                    steps.add(new Step(0, Ma3Action.fader(riser.page, riser.exec, 0, 0, "riser start"), riser));
                    steps.add(new Step(0, startAction(riser, "build"), riser));
                    steps.add(new Step(5, Ma3Action.fader(riser.page, riser.exec, riser.level, sec, "riser ramp " + Math.round(sec) + "s"), riser));
                    activeRiser = riser;
                }
            }
            case DROP -> {
                Look accent = pickEvent(cfg, Role.ACCENT, section);
                if (accent != null) {
                    double beats = accent.flashBeats > 0 ? accent.flashBeats : cfg.engine.accentBeats;
                    pulse(accent, 0, beats * period, steps, "drop hit");
                }
                blinder(cfg, period, 0, steps, "drop");
                if (cfg.engine.dropStrobeBeats > 0 && strobeCoversGlobal != nextGlobal) {
                    strobe(cfg, cfg.engine.dropStrobeBeats * period, 0, steps, "drop");
                }
                special(cfg, steps);
                // fog on the drop only if the look-ahead did not already fire it
                fog(cfg, steps, "drop");
            }
            case BREAKDOWN -> {
                // when a drop is known to be coming, save the fog for the pre-drop burst
                if (cfg.atmos.fogInBreakdown && toDrop < 0) fog(cfg, steps, "breakdown");
            }
            default -> {
            }
        }
        haze(cfg, section, steps);
    }

    private void lookAhead(Config cfg, TrackStructure st, int toDrop, int next, long nextGlobal, double period, List<Step> steps) {
        if (toDrop <= 0) return;
        if (toDrop == cfg.atmos.fogLeadBeats) fog(cfg, steps, "pre-drop (" + toDrop + " beats)");
        if (cfg.engine.buildStrobeBeats > 0 && toDrop == cfg.engine.buildStrobeBeats && !breakBefore(st, next, toDrop)) {
            // one continuous burst from the end of the build into the drop (the safety limiter caps it)
            if (strobe(cfg, (toDrop + cfg.engine.dropStrobeBeats) * period, 0, steps, "end of build into drop")) {
                strobeCoversGlobal = nextGlobal + toDrop;
            }
        }
        if (toDrop == 1 && cfg.engine.preDropBlackout) {
            Look bo = pickEvent(cfg, Role.BLACKOUT, Section.BUILD);
            if (bo != null) pulse(bo, 0, period, steps, "pre-drop blackout");
        }
    }

    /**
     * Short moments inside sections: blackout while the kick is out for a few beats (or everything stops), an
     * accent when it comes back, and a flash on each single bass hit in builds and breakdowns.
     */
    private void momentEvents(Config cfg, TrackStructure st, Section section, boolean sectionChanged, int next,
                              double period, List<Step> steps) {
        if (st == null || next <= 0) return;
        for (BeatMoments.Moment m : trackMoments(primary)) {
            if (m.startBeat() > next) break;
            if (m.kind() == BeatMoments.Kind.STEP) continue; // changes looks, see plan()
            if (m.kind() == BeatMoments.Kind.HIT) {
                if (m.startBeat() == next && cfg.engine.bassHitFlash && hitCounts(section)) {
                    Look accent = pickEvent(cfg, Role.ACCENT, section);
                    if (accent != null) pulse(accent, 0, cfg.engine.bassHitBeats * period, steps, "bass hit");
                }
                continue;
            }
            if (!breakCounts(st, m)) continue;
            if (m.startBeat() == next) {
                event("Beat " + next + ": " + (m.kind() == BeatMoments.Kind.STOP ? "stop" : "break") + ", " + m.length()
                        + " beats" + (m.kind() == BeatMoments.Kind.STOP ? " (silence)" : " (no bass)"));
                if (cfg.engine.breakBlackout) {
                    Look bo = pickEvent(cfg, Role.BLACKOUT, section);
                    if (bo != null) pulse(bo, 0, m.length() * period, steps, "break");
                }
            }
            // a drop has its own hit
            if (m.endBeat() == next && cfg.engine.breakReturnAccent && !(sectionChanged && section == Section.DROP)) {
                Look accent = pickEvent(cfg, Role.ACCENT, section);
                if (accent != null) pulse(accent, 0, (accent.flashBeats > 0 ? accent.flashBeats : 1) * period, steps, "kick back after break");
            }
        }
    }

    /** Bass hits count where no kick runs: in builds and breakdowns. */
    private static boolean hitCounts(Section section) {
        return section == Section.BUILD || section == Section.BREAKDOWN;
    }

    /**
     * Breaks count where the kick runs, or right before a drop; a stop (silence) counts everywhere. A missing kick
     * in an intro or build is part of the section, not a break.
     */
    public static boolean breakCounts(TrackStructure st, BeatMoments.Moment m) {
        if (m.kind() == BeatMoments.Kind.STOP) return true;
        TrackStructure.Segment at = st.segmentAt(m.startBeat());
        if (at != null && at.section().isKicking()) return true;
        TrackStructure.Segment after = st.segmentAt(m.endBeat());
        return after != null && after.section() == Section.DROP && Math.abs(after.startBeat() - m.endBeat()) <= 1;
    }

    /** A break or stop starts in the {@code beats} before the drop: dark there, so no strobe into the drop. */
    private boolean breakBefore(TrackStructure st, int next, int beats) {
        if (st == null || next <= 0) return false;
        return trackMoments(primary).stream().anyMatch(m -> (m.kind() == BeatMoments.Kind.BREAK || m.kind() == BeatMoments.Kind.STOP)
                && m.startBeat() >= next && m.startBeat() < next + beats && breakCounts(st, m));
    }

    /** Beats from {@code beat} to the start of the next DROP in the structure, or -1 if none is known. */
    static int beatsToNextDrop(TrackStructure st, TrackStructure.Segment seg, int beat) {
        if (st == null || seg == null || beat <= 0) return -1;
        for (TrackStructure.Segment s = st.nextAfter(seg); s != null; s = st.nextAfter(s)) {
            if (s.section() == Section.DROP) return s.startBeat() - beat;
            if (s.section().isKicking()) return -1; // the music kicks again before any drop
        }
        return -1;
    }

    private Look pickEvent(Config cfg, Role role, Section section) {
        if (locked(role.name())) return null;
        List<Look> pool = cfg.looks.stream().filter(l -> l.enabled && l.role == role).toList();
        if (pool.isEmpty()) return null;
        List<Look> cands = LookSelector.candidates(pool, section, currentEnergy);
        if (cands.isEmpty()) cands = pool;
        return selector.pick(cands, null);
    }

    private boolean strobe(Config cfg, double wantedMs, double delayMs, List<Step> steps, String why) {
        if (!op.strobeAllowed) return false;
        Look l = pickEvent(cfg, Role.STROBE, currentSection);
        if (l == null) return false;
        long ms = strobeLimiter.request(sched.currentTimeMillis(), (long) wantedMs, cfg.safety.strobeMaxOnSec,
                cfg.safety.strobeMinGapSec, cfg.safety.strobeMaxDutyPerMinute);
        if (ms > 0) {
            pulse(l, delayMs, ms, steps, "strobe " + why);
            return true;
        }
        event("Strobe skipped (safety limit)");
        return false;
    }

    /** Fires the blinder (within the safety limits); returns how long it is on (ms), 0 if not fired. */
    private long blinder(Config cfg, double period, double delayMs, List<Step> steps, String why) {
        Look l = pickEvent(cfg, Role.BLINDER, currentSection);
        if (l == null) return 0;
        double wanted = (l.flashBeats > 0 ? l.flashBeats : 1) * period;
        long ms = blinderLimiter.request(sched.currentTimeMillis(), (long) wanted, cfg.safety.blinderMaxOnSec,
                cfg.safety.blinderMinGapSec, 1.0);
        if (ms > 0) pulse(l, delayMs, ms, steps, "blinder " + why);
        return ms;
    }

    private void fog(Config cfg, List<Step> steps, String why) {
        if (!cfg.atmos.fogEnabled) return;
        long now = sched.currentTimeMillis();
        if (now - lastFogMs < cfg.atmos.fogCooldownSec * 1000) return;
        Look l = pickEvent(cfg, Role.FOG, currentSection);
        if (l == null) return;
        lastFogMs = now;
        pulse(l, 0, cfg.atmos.fogBurstSec * 1000, steps, "fog " + why);
    }

    private void special(Config cfg, List<Step> steps) {
        if (!op.specialsArmed) return;
        long now = sched.currentTimeMillis();
        while (!specialTimes.isEmpty() && specialTimes.peekFirst() < now - 3_600_000) specialTimes.removeFirst();
        if (now - lastSpecialMs < cfg.atmos.specialCooldownSec * 1000 || specialTimes.size() >= cfg.atmos.specialMaxPerHour) return;
        Look l = pickEvent(cfg, Role.SPECIAL, Section.DROP);
        if (l == null) return;
        lastSpecialMs = now;
        specialTimes.addLast(now);
        pulse(l, 0, (l.flashBeats > 0 ? l.flashBeats * lastPeriodMs : cfg.atmos.specialBurstSec * 1000), steps, "special on drop");
    }

    private void haze(Config cfg, Section section, List<Step> steps) {
        if (!cfg.atmos.hazeEnabled) return;
        Integer pct = cfg.atmos.hazeLevels.get(section);
        if (pct == null || pct.equals(lastHazePct)) return;
        lastHazePct = pct;
        for (Look l : cfg.looks) {
            if (!l.enabled || l.role != Role.HAZE || locked(Role.HAZE.name())) continue;
            int level = (int) Math.round(l.level * pct / 100.0);
            steps.add(new Step(0, Ma3Action.fader(l.page, l.exec, level, cfg.atmos.hazeFadeSec, "haze " + section), l));
        }
    }

    private void pulse(Look l, double delayMs, double durationMs, List<Step> steps, String why) {
        steps.add(new Step(delayMs, startAction(l, why), l));
        steps.add(new Step(delayMs + durationMs, stopAction(l, why + " end"), l));
    }

    private Ma3Action startAction(Look l, String why) {
        String reason = why + ": " + l.label();
        return switch (l.modeOrDefault()) {
            case TOGGLE -> Ma3Action.exec(Ma3Action.Kind.GO, l.page, l.exec, reason);
            case FLASH -> Ma3Action.exec(Ma3Action.Kind.FLASH_ON, l.page, l.exec, reason);
            case TEMP -> Ma3Action.exec(Ma3Action.Kind.TEMP_ON, l.page, l.exec, reason);
            case FADER -> Ma3Action.fader(l.page, l.exec, l.level, 0, reason);
        };
    }

    private Ma3Action stopAction(Look l, String why) {
        String reason = why + ": " + l.label();
        return switch (l.modeOrDefault()) {
            case TOGGLE -> Ma3Action.exec(Ma3Action.Kind.OFF, l.page, l.exec, reason);
            case FLASH -> Ma3Action.exec(Ma3Action.Kind.FLASH_OFF, l.page, l.exec, reason);
            case TEMP -> Ma3Action.exec(Ma3Action.Kind.TEMP_OFF, l.page, l.exec, reason);
            case FADER -> Ma3Action.fader(l.page, l.exec, 0, 0, reason);
        };
    }

    // ------------------------------------------------------------------ BPM

    private void updateBpm(Config cfg, double bpm) {
        if (!cfg.speed.enabled || bpm <= 0) return;
        if (!op.auto && !cfg.engine.bpmSyncWhenManual) return;
        double target = Math.round(bpm * cfg.speed.multiplier * 10) / 10.0;
        long now = sched.currentTimeMillis();
        boolean changed = Math.abs(target - lastBpmSent) >= cfg.speed.minChange;
        if ((changed && now - lastBpmSentMs > 250) || now - lastBpmSentMs > 30_000) {
            lastBpmSent = target;
            lastBpmSentMs = now;
            out.accept(Ma3Action.bpm(cfg.speed.master, target, "BPM sync"));
        }
    }

    // ------------------------------------------------------------------ periodic + operator

    private void tick() {
        Config cfg = configStore.get();
        if (wasAuto && !op.auto) {
            event("AUTO off" + (cfg.engine.releaseOnAutoOff ? ": releasing looks" : ""));
            if (cfg.engine.releaseOnAutoOff) releaseAll("auto off");
        }
        if (!wasAuto && op.auto) {
            event("AUTO on");
            needsRefresh = true;
        }
        if (wasHold && !op.hold) {
            event("HOLD released");
            needsRefresh = true;
        }
        if (!wasHold && op.hold) event("HOLD");
        wasAuto = op.auto;
        wasHold = op.hold;
        long now = sched.currentTimeMillis();
        checkSilence(cfg, now);
        op.layerLocks.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                event("Layer " + e.getKey() + " back to auto");
                needsRefresh = true;
                return true;
            }
            return false;
        });
        snapshot = buildSnapshot(cfg);
    }

    /**
     * Nothing drives the lights any more (deck paused or stopped, track ran out or was ejected, every fader down,
     * player gone from the network): after a few seconds stop everything the show started. Only in AUTO without
     * HOLD; the looks come back with the next beat that drives the lights.
     */
    private void checkSilence(Config cfg, long now) {
        DeckState d = primary >= 0 ? decks.get(primary) : null;
        boolean music = d != null && d.trackKey() != null
                && ((d.playing() && now - lastDeckMs.getOrDefault(d.player(), Long.MIN_VALUE / 2) < 3000)
                || now - lastBeatMs.getOrDefault(d.player(), Long.MIN_VALUE / 2) < 2000);
        if (music) {
            silentSinceMs = -1;
            return;
        }
        if (silentSinceMs < 0) silentSinceMs = now;
        double limit = cfg.engine.releaseWhenStoppedSec;
        if (limit <= 0 || stoppedForSilence || now - silentSinceMs < limit * 1000 || !op.auto || op.hold) return;
        boolean hazeOn = lastHazePct != null && lastHazePct > 0;
        if (active.isEmpty() && activeRiser == null && !hazeOn) return;
        event("No music playing: stopping the show's looks");
        Look riser = activeRiser;
        releaseAll("music stopped");
        if (riser != null) send(new Step(0, Ma3Action.fader(riser.page, riser.exec, riser.level, 0, "riser reset"), riser));
        if (hazeOn) {
            for (Look l : cfg.looks) {
                if (!l.enabled || l.role != Role.HAZE || locked(Role.HAZE.name())) continue;
                send(new Step(0, Ma3Action.fader(l.page, l.exec, 0, cfg.atmos.hazeFadeSec, "haze off, music stopped"), l));
            }
        }
        lastHazePct = null;
        dropUntilGlobal = -1;
        needsRefresh = true;
        stoppedForSilence = true;
    }

    private void releaseAll(String why) {
        for (Look l : new ArrayList<>(active.values())) send(new Step(0, stopAction(l, why), l));
        active.clear();
        if (activeRiser != null) {
            send(new Step(0, stopAction(activeRiser, why), activeRiser));
            activeRiser = null;
        }
    }

    private void onConfigChanged() {
        needsRefresh = true;
        event("Config updated");
    }

    /** Forget all players (the music source changed). Running looks stay until the next decision. */
    public void clearDecks(String why) {
        sched.execute(() -> {
            decks.clear();
            structures.clear();
            moments.clear();
            lastBeatNumber.clear();
            lastBeatMs.clear();
            lastDeckMs.clear();
            primary = -1;
            primaryReason = "no players found";
            currentTrackKey = null;
            lastSource = null;
            needsRefresh = true;
            event(why);
        });
    }

    /** Fire a look briefly so the operator can verify the executor mapping. */
    public void testLook(String id) {
        sched.execute(() -> {
            Look l = configStore.get().looks.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            if (l == null) return;
            event("Test: " + l.label());
            if (l.role == Role.HAZE) {
                send(new Step(0, Ma3Action.fader(l.page, l.exec, l.level, 0, "test"), l));
                sched.schedule(() -> send(new Step(0, Ma3Action.fader(l.page, l.exec, 0, 0, "test end"), l)), 2_000_000_000L);
                return;
            }
            send(new Step(0, startAction(l, "test"), l));
            sched.schedule(() -> send(new Step(0, stopAction(l, "test end"), l)), 2_000_000_000L);
        });
    }

    /** Incoming OSC from a console: operator controls and playback feedback. */
    public void onOsc(OscCodec.Message m) {
        sched.execute(() -> handleOsc(m));
    }

    private void handleOsc(OscCodec.Message m) {
        Config cfg = configStore.get();
        String addr = m.address();
        String prefix = cfg.oscIn.controlPrefix == null ? "/automa3" : cfg.oscIn.controlPrefix;
        int idx = addr.indexOf(prefix + "/");
        if (idx >= 0) {
            control(addr.substring(idx + prefix.length() + 1), m.num(0), "console");
            return;
        }
        Matcher mm = SEQ_FEEDBACK.matcher(addr);
        if (!mm.find()) return;
        String seq = mm.group(1);
        String function = m.str(0);
        String action = cfg.oscIn.controlSequences.get(seq);
        if (action != null) {
            Double value = m.num(m.args().size() - 1);
            boolean fader = m.args().size() >= 3;
            if (fader && "energy".equals(action)) control("energy", value == null ? null : value / 100.0, "console fader");
            else if (!fader && value != null && value > 0) control(action, null, "console key " + function);
            return;
        }
        long now = sched.currentTimeMillis();
        for (Look l : cfg.looks) {
            if (l.sequence == null || !seq.equals(Integer.toString(l.sequence))) continue;
            Long last = lastCommandMs.get(lookKey(l));
            if (last != null && now - last < ECHO_WINDOW_MS) return; // our own command echoed back
            String layer = l.role.sceneRole ? l.layerName() : l.role.name();
            long until = now + (long) (cfg.engine.operatorLockBars * 4 * lastPeriodMs);
            if (!locked(layer)) event("Operator took " + layer + " (" + l.label() + ") for " + cfg.engine.operatorLockBars + " bars");
            op.layerLocks.put(layer, until);
            if (l.role.sceneRole) active.remove(layer);
            return;
        }
    }

    /** Apply an operator control. value: null = toggle / trigger. */
    public void control(String action, Double value, String from) {
        sched.execute(() -> {
            String a = action.toLowerCase().replace("/", "");
            boolean on = value == null || value > 0;
            switch (a) {
                case "auto" -> op.auto = value == null ? !op.auto : on;
                case "hold" -> op.hold = value == null ? !op.hold : on;
                case "drop" -> {
                    if (on) op.dropRequested = true;
                }
                case "next" -> {
                    if (on) op.nextRequested = true;
                }
                case "strobe" -> op.strobeAllowed = value == null ? !op.strobeAllowed : on;
                case "arm" -> op.specialsArmed = value == null ? !op.specialsArmed : on;
                case "energy" -> {
                    if (value != null) op.setEnergyBias((value > 1 ? value / 100.0 : value) - 0.5);
                }
                case "follow" -> {
                    // value = player number, 0 or none = automatic
                    op.followPlayer = value == null ? 0 : Math.max(0, (int) Math.round(value));
                    if (op.followPlayer == 0) primaryReason = "automatic";
                    choosePrimary();
                }
                case "release" -> {
                    op.layerLocks.clear();
                    needsRefresh = true;
                }
                case "blackout" -> releaseAll("operator release");
                default -> {
                    event("Unknown control: " + action);
                    return;
                }
            }
            event("Control " + a + (value != null ? " " + value : "") + " (" + from + ")");
        });
    }

    // ------------------------------------------------------------------ status

    private void event(String text) {
        log.info(text);
        events.addFirst(String.format("%tT %s", sched.currentTimeMillis(), text));
        while (events.size() > 150) events.removeLast();
    }

    private EngineSnapshot buildSnapshot(Config cfg) {
        long now = sched.currentTimeMillis();
        List<EngineSnapshot.Deck> deckList = new ArrayList<>();
        decks.values().stream().sorted((x, y) -> Integer.compare(x.player(), y.player())).forEach(d -> {
            TrackStructure st = structureFor(d.player());
            Integer beat = lastBeatNumber.get(d.player());
            Section s = sectionOf(d.player());
            deckList.add(new EngineSnapshot.Deck(d.player(), d.deviceName(), isPlaying(d, now), d.onAir(), d.tempoMaster(),
                    d.player() == primary, Math.round(d.bpm() * 10) / 10.0, beat == null ? -1 : beat,
                    d.title(), d.artist(), d.trackKey(), s == null ? null : s.name(), st == null ? "none" : st.source(),
                    availableAnalyses(d.player()), st == null ? List.of() : st.segments(), st == null ? 0 : st.lastBeat(),
                    analysisRevision.getOrDefault(d.player(), 0), editFor(d.player()) != null));
        });
        Map<String, String> activeLabels = new LinkedHashMap<>();
        active.forEach((k, v) -> activeLabels.put(k, v.label()));
        if (activeRiser != null) activeLabels.put("RISER", activeRiser.label());
        Map<String, Long> locks = new LinkedHashMap<>();
        op.layerLocks.forEach((k, v) -> locks.put(k, Math.max(0, (v - now) / 1000)));
        return new EngineSnapshot(op.auto, op.hold, op.strobeAllowed, op.specialsArmed, op.energyBias,
                primary, primaryReason, op.followPlayer, decks.values().stream().anyMatch(DeckState::onAirKnown),
                currentSection == null ? null : currentSection.name(), sectionReason,
                Math.round(currentEnergy * 100) / 100.0, Math.round(lastBpm * 10) / 10.0,
                upcomingSection == null ? null : upcomingSection.name(), beatsToUpcoming,
                activeLabels, locks, deckList, new ArrayList<>(events), audio.get());
    }
}
