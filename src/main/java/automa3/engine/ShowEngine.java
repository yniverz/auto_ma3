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
 * structure. All engine state is touched only on the scheduler thread.</p>
 */
public class ShowEngine implements MusicListener {

    private static final Logger log = LoggerFactory.getLogger(ShowEngine.class);
    private static final Pattern SEQ_FEEDBACK = Pattern.compile("13\\.13\\.1\\.6\\.(\\d+)$");
    private static final long ECHO_WINDOW_MS = 1000;

    /** A command to send {@code delayMs} after the planned beat time. */
    record Step(double delayMs, Ma3Action action, Look look) {
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
    private final Map<Integer, Integer> lastBeatNumber = new HashMap<>();
    private final Map<Integer, Long> lastBeatMs = new HashMap<>();
    private int primary = -1;
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
    private boolean needsRefresh = true;
    private boolean wasAuto = true;
    private boolean wasHold = false;
    private Integer lastHazePct;
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
            event("Player " + player + ": " + structure.segments().size() + " sections from " + structure.source());
        });
    }

    // ------------------------------------------------------------------ deck selection

    private void choosePrimary() {
        long now = sched.currentTimeMillis();
        boolean onAirKnown = decks.values().stream().anyMatch(DeckState::onAirKnown);
        List<DeckState> candidates = new ArrayList<>();
        for (DeckState d : decks.values()) {
            boolean fresh = now - d.updatedMs() < 3000 || now - lastBeatMs.getOrDefault(d.player(), 0L) < 3000;
            if (isPlaying(d, now) && fresh && (!onAirKnown || d.onAir())) candidates.add(d);
        }
        if (candidates.isEmpty()) return;
        DeckState current = decks.get(primary);
        boolean currentOk = current != null && candidates.stream().anyMatch(d -> d.player() == primary);
        if (currentOk) {
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
        setPrimary(pick.player(), current == null ? "first deck playing" : "player " + primary + " stopped / off air");
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

    private Section sectionOf(int player) {
        TrackStructure st = structureFor(player);
        Integer beat = lastBeatNumber.get(player);
        if (st == null || beat == null) return null;
        TrackStructure.Segment seg = st.segmentAt(beat);
        return seg == null ? null : seg.section();
    }

    /** The analysis of the player's current track selected by the analysis mode (auto / phrase / waveform). */
    private TrackStructure structureFor(int player) {
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
            reason = st.source() + ": " + seg.label();
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

        // kick back after a long lull = drop (also for analyses that do not label drops)
        boolean lullBefore = currentSection != null && !currentSection.isKicking() && currentSection != Section.INTRO;
        if (section.isKicking() && section != Section.DROP && lullBefore && lullStartGlobal >= 0
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
        if (!sectionChanged && barStart && barsIn > 0 && section != Section.BUILD) {
            int every = section == Section.DROP || section == Section.PEAK ? cfg.engine.rotateBarsPeak : cfg.engine.rotateBarsGroove;
            if ((nextGlobal - sectionStartGlobal) % (every * 4L) == 0) force.add(nextRotationLayer(cfg));
        }
        if (op.nextRequested) {
            op.nextRequested = false;
            force.add(nextRotationLayer(cfg));
        }
        force.remove(null);

        if (sectionChanged || trackChanged || needsRefresh || !force.isEmpty()) {
            needsRefresh = false;
            applyScene(cfg, section, energy, force, steps);
        }

        if (sectionChanged) sectionEvents(cfg, section, seg, next, nextGlobal, toDrop, period, steps);
        if (section == Section.DROP && !sectionChanged && barStart && cfg.engine.dropBlinderEveryBars > 0
                && barsIn > 0 && barsIn % cfg.engine.dropBlinderEveryBars == 0) {
            blinder(cfg, period, 0, steps, "drop phrase");
        }
        lookAhead(cfg, toDrop, nextGlobal, period, steps);
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

    private String nextRotationLayer(Config cfg) {
        List<String> rot = new ArrayList<>();
        sceneLayers(cfg).forEach((layer, looks) -> {
            boolean color = looks.get(0).role == Role.COLOR;
            if (looks.size() > 1 && (!color || !cfg.engine.colorPerTrack)) rot.add(layer);
        });
        if (rot.isEmpty()) return null;
        return rot.get(rotationIndex++ % rot.size());
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
            List<Look> cands = looks.get(0).role == Role.EFFECT
                    ? looks.stream().filter(l -> LookSelector.fits(l, section, energy)).toList()
                    : LookSelector.candidates(looks, section, energy);
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

    private void lookAhead(Config cfg, int toDrop, long nextGlobal, double period, List<Step> steps) {
        if (toDrop <= 0) return;
        if (toDrop == cfg.atmos.fogLeadBeats) fog(cfg, steps, "pre-drop (" + toDrop + " beats)");
        if (cfg.engine.buildStrobeBeats > 0 && toDrop == cfg.engine.buildStrobeBeats) {
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

    private void blinder(Config cfg, double period, double delayMs, List<Step> steps, String why) {
        Look l = pickEvent(cfg, Role.BLINDER, currentSection);
        if (l == null) return;
        double wanted = (l.flashBeats > 0 ? l.flashBeats : 1) * period;
        long ms = blinderLimiter.request(sched.currentTimeMillis(), (long) wanted, cfg.safety.blinderMaxOnSec,
                cfg.safety.blinderMinGapSec, 1.0);
        if (ms > 0) pulse(l, delayMs, ms, steps, "blinder " + why);
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
                    availableAnalyses(d.player()), st == null ? List.of() : st.segments(), st == null ? 0 : st.lastBeat()));
        });
        Map<String, String> activeLabels = new LinkedHashMap<>();
        active.forEach((k, v) -> activeLabels.put(k, v.label()));
        if (activeRiser != null) activeLabels.put("RISER", activeRiser.label());
        Map<String, Long> locks = new LinkedHashMap<>();
        op.layerLocks.forEach((k, v) -> locks.put(k, Math.max(0, (v - now) / 1000)));
        return new EngineSnapshot(op.auto, op.hold, op.strobeAllowed, op.specialsArmed, op.energyBias,
                primary, currentSection == null ? null : currentSection.name(), sectionReason,
                Math.round(currentEnergy * 100) / 100.0, Math.round(lastBpm * 10) / 10.0,
                upcomingSection == null ? null : upcomingSection.name(), beatsToUpcoming,
                activeLabels, locks, deckList, new ArrayList<>(events), audio.get());
    }
}
