package automa3.engine;

import automa3.audio.AudioAnalyzer;
import automa3.config.Config;
import automa3.config.ConfigStore;
import automa3.ma3.Ma3Action;
import automa3.ma3.Ma3Action.Kind;
import automa3.ma3.OscCodec;
import automa3.model.Role;
import automa3.music.BeatEvent;
import automa3.music.DeckState;
import automa3.music.SimulatedSource;
import automa3.music.TrackStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ShowEngineTest {

    record Sent(long timeNanos, Ma3Action action) {
    }

    private VirtualScheduler sched;
    private ConfigStore store;
    private List<Sent> sent;
    private ShowEngine engine;
    private OperatorState op;
    private final double bpm = 133;
    private final double periodMs = 60000 / 133.0;
    /** virtual time (ns) of each played beat number */
    private final Map<Integer, Long> beatTimes = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("automa3-test");
        store = new ConfigStore(dir.resolve("config.json")); // default config: one+ look per role
        sched = new VirtualScheduler();
        sent = new ArrayList<>();
        op = new OperatorState();
        engine = new ShowEngine(store, sched, a -> sent.add(new Sent(sched.nanoTime(), a)), op,
                () -> AudioAnalyzer.AudioState.OFF, new Random(42));
    }

    private Config.Look look(Role role) {
        return store.get().looks.stream().filter(l -> l.role == role).findFirst().orElseThrow();
    }

    /** Play a track on player 1 from beat 1 to lastBeat with the given structure (or none). */
    private void play(String key, TrackStructure structure, int lastBeat) {
        long t = sched.nanoTime() + 100_000_000L;
        sched.runUntil(t);
        engine.onDeck(new DeckState(1, "CDJ-3000", true, true, true, true, bpm, 1, 1, key, "Test", "Artist", sched.currentTimeMillis()));
        if (structure != null) engine.onStructure(1, structure);
        sched.runUntil(t + 1);
        long start = t + 50_000_000L;
        for (int beat = 1; beat <= lastBeat; beat++) {
            long bt = start + (long) ((beat - 1) * periodMs * 1_000_000L);
            beatTimes.put(beat, bt);
            sched.runUntil(bt);
            if (beat % 8 == 0) {
                engine.onDeck(new DeckState(1, "CDJ-3000", true, true, true, true, bpm, beat, ((beat - 1) % 4) + 1,
                        key, "Test", "Artist", sched.currentTimeMillis()));
            }
            engine.onBeat(new BeatEvent(1, beat, ((beat - 1) % 4) + 1, bpm, bt));
            sched.runUntil(bt + 1);
        }
        sched.runUntil(sched.nanoTime() + 5_000_000_000L);
    }

    private List<Sent> commandsFor(Config.Look l, Kind kind) {
        return sent.stream().filter(s -> s.action.kind() == kind && s.action.page() == l.page && s.action.exec() == l.exec).toList();
    }

    @Test
    void fullTrackWithPhraseAnalysis() {
        SimulatedSource.Template t = SimulatedSource.TEMPLATES.get(0);
        TrackStructure st = SimulatedSource.exactStructure("track1", t, 64);
        int lastBeat = t.bars() * 4;
        play("track1", st, lastBeat);

        int latency = store.get().engine.latencyMs;
        int firstDrop = st.segments().stream().filter(s -> s.section() == automa3.model.Section.DROP).findFirst().orElseThrow().startBeat();

        // drop hit lands latency ms before the drop beat
        Config.Look accent = look(Role.ACCENT);
        List<Sent> hits = commandsFor(accent, Kind.FLASH_ON);
        assertEquals(2, hits.size(), "one hit per drop");
        long expected = beatTimes.get(firstDrop) - latency * 1_000_000L;
        assertEquals(expected, hits.get(0).timeNanos, 2_000_000L, "drop hit timing");
        assertEquals(1, commandsFor(accent, Kind.FLASH_OFF).stream().filter(s -> s.timeNanos > expected && s.timeNanos < expected + 2_000_000_000L).count());

        // riser starts at the build and ramps with a fade, then stops at the drop
        Config.Look riser = look(Role.RISER);
        assertFalse(commandsFor(riser, Kind.GO).isEmpty());
        assertTrue(commandsFor(riser, Kind.FADER).stream().anyMatch(s -> s.action.fadeSec() > 10), "riser ramp with fade");
        assertEquals(commandsFor(riser, Kind.GO).size(), commandsFor(riser, Kind.OFF).size());

        // fog 16 beats before the first drop
        Config.Look fog = look(Role.FOG);
        List<Sent> fogs = commandsFor(fog, Kind.FLASH_ON);
        assertFalse(fogs.isEmpty());
        assertEquals(beatTimes.get(firstDrop - 16) - latency * 1_000_000L, fogs.get(0).timeNanos, 2_000_000L);

        // blackout on the last beat before the drop
        Config.Look bo = look(Role.BLACKOUT);
        assertEquals(beatTimes.get(firstDrop - 1) - latency * 1_000_000L, commandsFor(bo, Kind.FLASH_ON).get(0).timeNanos, 2_000_000L);

        // BPM sent to the speed master
        assertTrue(sent.stream().anyMatch(s -> s.action.kind() == Kind.SPEED_BPM && Math.abs(s.action.bpm() - 133) < 0.01));

        // one continuous strobe from the last 4 beats of each build into the drop (8 beats = 3.6 s at 133 BPM)
        List<Sent> strobes = commandsFor(look(Role.STROBE), Kind.FLASH_ON);
        assertEquals(2, strobes.size(), "one strobe burst per drop");
        assertEquals(beatTimes.get(firstDrop - 4) - latency * 1_000_000L, strobes.get(0).timeNanos, 2_000_000L);
        long burstMs = (commandsFor(look(Role.STROBE), Kind.FLASH_OFF).get(0).timeNanos - strobes.get(0).timeNanos) / 1_000_000;
        assertEquals(8 * periodMs, burstMs, 2);

        // every scene layer has exactly one look running at the end
        assertSceneConsistent();
        assertStrobeSafe();
    }

    /** February 7 (real CDJ data): blackout during the 1-bar breaks, accent when the kick returns, flash on the bass hits. */
    @Test
    void breaksAndBassHitsFromTheWaveform() throws Exception {
        automa3.music.AnalysisDump d = automa3.music.AnalysisDump.load(
                Path.of(ShowEngineTest.class.getResource("/tracks/february-7.json").toURI()));
        TrackStructure st = automa3.music.WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), 64);
        // a second movement look for the loud parts, so there is something to change to
        Config c = ConfigStore.copy(store.get());
        Config.Look extra = ConfigStore.copy(store.get()).looks.stream().filter(l -> l.name.equals("Move fast")).findFirst().orElseThrow();
        extra.id = "move-wild";
        extra.name = "Move wild";
        extra.exec = 199;
        c.looks.add(extra);
        store.replace(c);
        play(d.trackKey(), st, 520);
        long latencyNs = store.get().engine.latencyMs * 1_000_000L;
        Config.Look bo = look(Role.BLACKOUT);
        Config.Look accent = look(Role.ACCENT);

        // the bar without bass from beat 162: dark for exactly that bar
        long breakAt = beatTimes.get(162) - latencyNs;
        Sent on = commandsFor(bo, Kind.FLASH_ON).stream().filter(s -> Math.abs(s.timeNanos - breakAt) < 2_000_000L).findFirst().orElseThrow();
        Sent off = commandsFor(bo, Kind.FLASH_OFF).stream().filter(s -> s.timeNanos > on.timeNanos).findFirst().orElseThrow();
        assertEquals(4 * periodMs, (off.timeNanos - on.timeNanos) / 1e6, 2, "blackout for the whole break");
        // kick back on 166: accent
        assertTrue(flashedAt(accent, 166), "accent when the kick returns");
        // single bass hits in the build before the drop at 406: one flash each
        for (int beat : new int[]{358, 362, 366, 370, 374, 382, 390}) assertTrue(flashedAt(accent, beat), "bass hit at " + beat);
        // the kick running in the peak: no accents in between
        for (int beat = 167; beat < 194; beat++) assertFalse(flashedAt(accent, beat), "no accent at " + beat);
        // something changes when the kick comes back, and on each step of the build (beats 86 and 118)
        for (int beat : new int[]{166, 198, 230, 86, 118}) assertTrue(newLookAt(beat), "look change at " + beat);
        // the build ends in a silent bar (130): dark there, the strobe waits for the drop on 134
        Config.Look strobe = look(Role.STROBE);
        assertFalse(flashedAt(strobe, 130), "no strobe into the stop");
        assertTrue(flashedAt(strobe, 134), "strobe on the drop");
        assertStrobeSafe();
    }

    /** A second copy of every scene look but the colours, so every layer has something to change to. */
    private void secondLookOnEveryLayer() throws Exception {
        Config c = ConfigStore.copy(store.get());
        int exec = 190;
        for (Config.Look l : ConfigStore.copy(store.get()).looks) {
            if (!l.role.sceneRole || l.role == Role.COLOR) continue;
            l.id = "second-" + l.id;
            l.name = l.name + " 2";
            l.exec = exec++;
            c.looks.add(l);
        }
        store.replace(c);
    }

    private List<String> sceneLayersWithChoice() {
        return store.get().looks.stream().filter(l -> l.role.sceneRole && l.role != Role.COLOR).map(Config.Look::layerName).distinct()
                .filter(layer -> store.get().looks.stream().filter(l -> l.layerName().equals(layer)).count() > 1).sorted().toList();
    }

    /** February 7: after the 1-bar breaks in the peak every layer gets a new look; changes never come right after each other. */
    @Test
    void newLookOnEveryLayerAfterALongBreak() throws Exception {
        automa3.music.AnalysisDump d = automa3.music.AnalysisDump.load(
                Path.of(ShowEngineTest.class.getResource("/tracks/february-7.json").toURI()));
        TrackStructure st = automa3.music.WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), 64);
        secondLookOnEveryLayer();
        play(d.trackKey(), st, 520);
        Map<Integer, List<String>> changes = changes();
        for (int beat : new int[]{166, 198, 230, 438, 502}) { // the kick back after a bar without bass
            assertEquals(sceneLayersWithChoice(), changes.get(beat).stream().sorted().toList(), "every layer at " + beat);
        }
        List<Integer> beats = new ArrayList<>(changes.keySet());
        beats.remove(Integer.valueOf(2)); // the track's colour
        for (int i = 1; i < beats.size(); i++) {
            assertTrue(beats.get(i) - beats.get(i - 1) >= 16, "changes too close: " + beats.get(i - 1) + " and " + beats.get(i) + " in " + changes);
        }
        assertSceneConsistent();
    }

    @Test
    void withTheFreshLookOffOnlyTwoLayersChange() throws Exception {
        automa3.music.AnalysisDump d = automa3.music.AnalysisDump.load(
                Path.of(ShowEngineTest.class.getResource("/tracks/february-7.json").toURI()));
        TrackStructure st = automa3.music.WaveformAnalyzer.analyzeBeats(d.trackKey(), d.beatBands(), d.firstDownbeat(), 64);
        secondLookOnEveryLayer();
        Config c = ConfigStore.copy(store.get());
        c.engine.freshLookAfterLongBreak = false;
        store.replace(c);
        play(d.trackKey(), st, 240);
        assertEquals(2, changes().get(166).size(), changes().toString());
    }

    /** A drop without breaks: a new look when the blinder hit on bar 8 is over, then the timed change counts from there. */
    @Test
    void changeWhenTheDropsBlinderHitIsOver() throws Exception {
        SimulatedSource.Template t = SimulatedSource.TEMPLATES.get(0);
        TrackStructure st = SimulatedSource.exactStructure("track1", t, 64);
        secondLookOnEveryLayer();
        play("track1", st, t.bars() * 4);
        Map<Integer, List<String>> changes = changes();
        Config.Look blinder = look(Role.BLINDER);
        int flash = (int) Math.max(1, Math.round(blinder.flashBeats > 0 ? blinder.flashBeats : 1));
        for (TrackStructure.Segment drop : st.segments().stream().filter(s -> s.section() == automa3.model.Section.DROP).toList()) {
            if (drop.lengthBeats() <= 32 + flash) continue;
            int bar8 = drop.startBeat() + 32;
            assertTrue(flashedAt(blinder, bar8), "blinder on bar 8 of the drop at " + drop.startBeat());
            assertTrue(changes.containsKey(bar8 + flash), "new look when the blinder is over (beat " + (bar8 + flash) + "): " + changes);
            for (int b = drop.startBeat() + 1; b < bar8 + flash; b++) assertFalse(changes.containsKey(b), "no change at " + b);
        }
        // a peak without breaks: the timed change every 16 bars from the peak's start
        int every = store.get().engine.rotateBarsPeak * 4;
        for (TrackStructure.Segment peak : st.segments().stream().filter(s -> s.section() == automa3.model.Section.PEAK).toList()) {
            if (peak.lengthBeats() <= every) continue;
            for (int b = peak.startBeat() + 1; b < peak.startBeat() + every; b++) assertFalse(changes.containsKey(b), "no change at " + b);
            assertTrue(changes.containsKey(peak.startBeat() + every), "timed change in the peak: " + changes);
        }

        // switched off: nothing changes after the blinder
        sent.clear();
        Config c = ConfigStore.copy(store.get());
        c.engine.changeAfterDropFlash = false;
        store.replace(c);
        play("track2", SimulatedSource.exactStructure("track2", t, 64), t.bars() * 4);
        TrackStructure.Segment drop = SimulatedSource.exactStructure("track2", t, 64).segments().stream()
                .filter(s -> s.section() == automa3.model.Section.DROP).findFirst().orElseThrow();
        assertFalse(changes().containsKey(drop.startBeat() + 32 + flash), changes().toString());
    }

    /** Beats on which scene looks changed (forced changes, not section starts), with the layers that changed. */
    private Map<Integer, List<String>> changes() {
        Map<Integer, List<String>> out = new java.util.TreeMap<>();
        long latencyNs = store.get().engine.latencyMs * 1_000_000L;
        for (Sent s : sent) {
            if (s.action.kind() != Kind.GO || s.action.reason() == null || !s.action.reason().contains("(change)")) continue;
            for (Map.Entry<Integer, Long> b : beatTimes.entrySet()) {
                if (Math.abs(b.getValue() - latencyNs - s.timeNanos) >= 2_000_000L) continue;
                for (Config.Look l : store.get().looks) {
                    if (l.role.sceneRole && l.page == s.action.page() && l.exec == s.action.exec()) {
                        out.computeIfAbsent(b.getKey(), k -> new ArrayList<>()).add(l.layerName());
                    }
                }
            }
        }
        return out;
    }

    /** A scene look started (Go) on this beat. */
    private boolean newLookAt(int beat) {
        long at = beatTimes.get(beat) - store.get().engine.latencyMs * 1_000_000L;
        return sent.stream().anyMatch(s -> s.action.kind() == Kind.GO && Math.abs(s.timeNanos - at) < 2_000_000L);
    }

    private boolean flashedAt(Config.Look l, int beat) {
        long at = beatTimes.get(beat) - store.get().engine.latencyMs * 1_000_000L;
        return commandsFor(l, Kind.FLASH_ON).stream().anyMatch(s -> Math.abs(s.timeNanos - at) < 2_000_000L);
    }

    /** Deck stopped (or the track ran out) in a build: everything the show started stops, and comes back on play. */
    @Test
    void stopsItsLooksWhenTheMusicStopsAndRestoresThem() {
        SimulatedSource.Template t = SimulatedSource.TEMPLATES.get(0);
        TrackStructure st = SimulatedSource.exactStructure("track1", t, 64);
        int build = st.segments().stream().filter(s -> s.section() == automa3.model.Section.BUILD).findFirst().orElseThrow().startBeat();
        play("track1", st, build + 8);
        // the player reports stopped; play() already ran 5 s without beats
        engine.onDeck(new DeckState(1, "CDJ-3000", false, true, true, true, bpm, build + 8, 1, "track1", "Test", "Artist", sched.currentTimeMillis()));
        sched.runUntil(sched.nanoTime() + 5_000_000_000L);

        Map<String, Kind> lastByExec = new HashMap<>();
        for (Sent x : sent) {
            Kind k = x.action.kind();
            if (k == Kind.GO || k == Kind.OFF) lastByExec.put(x.action.page() + "." + x.action.exec(), k);
        }
        assertFalse(lastByExec.isEmpty());
        lastByExec.forEach((exec, k) -> assertEquals(Kind.OFF, k, "look " + exec + " stopped"));
        Config.Look haze = look(Role.HAZE);
        Sent lastHaze = commandsFor(haze, Kind.FADER).get(commandsFor(haze, Kind.FADER).size() - 1);
        assertEquals(0, lastHaze.action.value(), 0.01, "haze off");

        // play again: the scene, the riser of the build and the haze come back
        int before = sent.size();
        long start = sched.nanoTime() + 100_000_000L;
        for (int beat = build + 9; beat <= build + 12; beat++) {
            long bt = start + (long) ((beat - build - 9) * periodMs * 1_000_000L);
            sched.runUntil(bt);
            engine.onDeck(new DeckState(1, "CDJ-3000", true, true, true, true, bpm, beat, ((beat - 1) % 4) + 1, "track1", "Test", "Artist", sched.currentTimeMillis()));
            engine.onBeat(new BeatEvent(1, beat, ((beat - 1) % 4) + 1, bpm, bt));
            sched.runUntil(bt + 1);
        }
        sched.runUntil(sched.nanoTime() + 1_000_000_000L);
        List<Sent> after = sent.subList(before, sent.size());
        assertTrue(after.stream().filter(x -> x.action.kind() == Kind.GO).count() >= 3, "scene back");
        Config.Look riser = look(Role.RISER);
        assertTrue(after.stream().anyMatch(x -> x.action.kind() == Kind.GO && x.action.exec() == riser.exec), "riser back in the build");
        assertTrue(after.stream().anyMatch(x -> x.action.kind() == Kind.FADER && x.action.exec() == haze.exec && x.action.value() > 0), "haze back");
        assertSceneConsistent();
    }

    /** A groove after a long breakdown is a groove: no drop hit, strobe or blinder there. Full energy after it is a drop. */
    @Test
    void grooveAfterBreakdownIsNoDrop() {
        List<TrackStructure.Segment> segs = List.of(
                new TrackStructure.Segment(1, 65, automa3.model.Section.GROOVE, "bass running"),
                new TrackStructure.Segment(65, 129, automa3.model.Section.BREAKDOWN, "no bass"),
                new TrackStructure.Segment(129, 193, automa3.model.Section.GROOVE, "bass running"),
                new TrackStructure.Segment(193, 257, automa3.model.Section.BREAKDOWN, "no bass"),
                new TrackStructure.Segment(257, 321, automa3.model.Section.PEAK, "full energy"));
        play("grooves", new TrackStructure("grooves", "waveform", segs, List.of(), 1), 320);
        assertFalse(flashedAt(look(Role.ACCENT), 129), "no drop hit on the groove");
        assertFalse(flashedAt(look(Role.STROBE), 129), "no strobe on the groove");
        assertTrue(flashedAt(look(Role.ACCENT), 257), "kick back at full energy after a breakdown: drop");
    }

    @Test
    void trackWithoutAnalysisStillRunsAScene() {
        play("plain", null, 64 * 4);
        long gos = sent.stream().filter(s -> s.action.kind() == Kind.GO).count();
        assertTrue(gos >= 3, "base, colour and movement started");
        assertSceneConsistent();
        assertEquals("GROOVE", engine.snapshot().section());
    }

    @Test
    void holdFreezesLooks() {
        op.hold = true;
        play("held", SimulatedSource.exactStructure("held", SimulatedSource.TEMPLATES.get(1), 64), 200);
        assertTrue(sent.stream().allMatch(s -> s.action.kind() == Kind.SPEED_BPM), "only BPM sync in HOLD");
    }

    @Test
    void operatorTouchLocksLayer() {
        Config c = ConfigStore.copy(store.get());
        Config.Look move = c.looks.stream().filter(l -> l.role == Role.MOVEMENT).findFirst().orElseThrow();
        move.sequence = 55;
        try {
            store.replace(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        sched.runUntil(sched.nanoTime() + 1);
        engine.onOsc(new OscCodec.Message("/13.13.1.6.55", List.of("Go+", 1)));
        sched.runUntil(sched.nanoTime() + 1);
        assertTrue(op.layerLocks.containsKey("MOVEMENT"));
        play("t", SimulatedSource.exactStructure("t", SimulatedSource.TEMPLATES.get(0), 64), 32);
        List<Config.Look> movement = store.get().looks.stream().filter(l -> l.role == Role.MOVEMENT).toList();
        for (Config.Look l : movement) assertTrue(commandsFor(l, Kind.GO).isEmpty(), "engine keeps off a locked layer");
    }

    @Test
    void analysisModeSelectsPhraseOrWaveform() throws Exception {
        SimulatedSource.Template t = SimulatedSource.TEMPLATES.get(0);
        engine.onDeck(new DeckState(1, "CDJ-3000", true, true, true, true, bpm, 1, 1, "k", "T", "A", sched.currentTimeMillis()));
        engine.onStructure(1, SimulatedSource.exactStructure("k", t, 64));
        engine.onStructure(1, automa3.music.WaveformAnalyzer.analyzeBeats("k",
                SimulatedSource.syntheticWaveform(t, new Random(1)).stream()
                        .flatMap(b -> java.util.stream.Stream.of(b, b, b, b)).toList(), 1, 64));
        sched.runUntil(sched.nanoTime() + 100_000_000L);
        assertEquals("phrase", engine.snapshot().decks().get(0).analysis(), "auto prefers rekordbox phrases");
        assertEquals(List.of("phrase", "waveform"), engine.snapshot().decks().get(0).availableAnalyses());

        Config c = ConfigStore.copy(store.get());
        c.djLink.analysisMode = "waveform";
        store.replace(c);
        sched.runUntil(sched.nanoTime() + 100_000_000L);
        assertEquals("waveform", engine.snapshot().decks().get(0).analysis(), "manual waveform mode");
        assertEquals("waveform", engine.structure(1).source());

        // a new track on the deck drops the old track's analyses
        engine.onDeck(new DeckState(1, "CDJ-3000", true, true, true, true, bpm, 1, 1, "k2", "T2", "A", sched.currentTimeMillis()));
        engine.onStructure(1, SimulatedSource.exactStructure("k2", t, 64));
        sched.runUntil(sched.nanoTime() + 100_000_000L);
        assertEquals("phrase", engine.snapshot().decks().get(0).analysis(), "waveform mode falls back to phrases");
        assertEquals(List.of("phrase"), engine.snapshot().decks().get(0).availableAnalyses());
    }

    private void deck(int player, boolean playing, boolean onAir, boolean mixer) {
        engine.onDeck(new DeckState(player, "CDJ-3000", playing, onAir, mixer, false, bpm, 10, 2, "k" + player,
                "T" + player, "A", sched.currentTimeMillis()));
        sched.runUntil(sched.nanoTime() + 100_000_000L);
    }

    @Test
    void withAMixerTheDeckOnAirDrivesTheLights() {
        deck(3, true, false, true);
        deck(4, true, true, true);
        assertEquals(4, engine.snapshot().primaryPlayer());
        assertEquals("on air", engine.snapshot().primaryReason());
        assertTrue(engine.snapshot().mixerPresent());
    }

    private void deckSelection(String mode) throws Exception {
        Config c = ConfigStore.copy(store.get());
        c.djLink.deckSelection = mode;
        store.replace(c);
        sched.runUntil(sched.nanoTime() + 1);
    }

    @Test
    void mixerModeFollowsOnlyDecksWithTheFaderUp() {
        assertEquals("mixer", store.get().djLink.deckSelection, "default");
        deck(3, true, false, true);
        assertEquals(-1, engine.snapshot().primaryPlayer(), "playing but every fader down: no deck");
        assertTrue(engine.snapshot().primaryReason().contains("no deck on air"));
        deck(3, true, true, true);
        assertEquals(3, engine.snapshot().primaryPlayer(), "fader up");
        deck(3, true, false, true);
        assertEquals(-1, engine.snapshot().primaryPlayer(), "fader down again");
    }

    @Test
    void withoutAMixerThePlayingDeckDrivesTheLights() {
        deck(3, true, false, false);
        assertEquals(3, engine.snapshot().primaryPlayer());
        assertEquals("playing", engine.snapshot().primaryReason());
    }

    @Test
    void playingDeckDrivesTheLightsWhenTheMixerReportsNoneOnAir() throws Exception {
        deckSelection("playing");
        deck(3, true, false, true);
        deck(4, false, false, true);
        assertEquals(3, engine.snapshot().primaryPlayer(), "faders down / channel mismatch must not leave the lights dead");
        assertTrue(engine.snapshot().primaryReason().contains("no deck on air"));
    }

    @Test
    void operatorCanChooseTheDeck() {
        deck(3, true, true, true);
        deck(4, true, false, true);
        assertEquals(3, engine.snapshot().primaryPlayer());
        engine.control("follow", 4.0, "test");
        sched.runUntil(sched.nanoTime() + 100_000_000L);
        assertEquals(4, engine.snapshot().primaryPlayer(), "follows the chosen deck even though it is off air");
        assertEquals(4, engine.snapshot().followPlayer());
        deck(3, true, true, true);
        assertEquals(4, engine.snapshot().primaryPlayer(), "stays on the chosen deck");
        engine.control("follow", 0.0, "test");
        sched.runUntil(sched.nanoTime() + 100_000_000L);
        deck(3, true, true, true);
        assertEquals(3, engine.snapshot().primaryPlayer(), "automatic again: the deck on air");
        assertEquals(0, engine.snapshot().followPlayer());
    }

    @Test
    void controlsFromConsole() {
        engine.onOsc(new OscCodec.Message("/automa3/auto", List.of(0)));
        engine.onOsc(new OscCodec.Message("/gma3/automa3/strobe", List.of(0)));
        engine.onOsc(new OscCodec.Message("/automa3/energy", List.of(1.0f)));
        sched.runUntil(sched.nanoTime() + 1);
        assertFalse(op.auto);
        assertFalse(op.strobeAllowed);
        assertEquals(0.5, op.energyBias, 1e-9);
    }

    private void assertSceneConsistent() {
        Map<String, Integer> running = new HashMap<>();
        for (Sent s : sent) {
            if (s.action.kind() != Kind.GO && s.action.kind() != Kind.OFF) continue;
            for (Config.Look l : store.get().looks) {
                if (!l.role.sceneRole || l.page != s.action.page() || l.exec != s.action.exec()) continue;
                running.merge(l.layerName(), s.action.kind() == Kind.GO ? 1 : -1, Integer::sum);
            }
        }
        running.forEach((layer, n) -> {
            if (layer.equals("EFFECT")) assertTrue(n == 0 || n == 1, "effect layer runs at most one look, was " + n);
            else assertEquals(1, n, "layer " + layer + " should have exactly one running look");
        });
    }

    private void assertStrobeSafe() {
        Config.Look strobe = look(Role.STROBE);
        List<Sent> on = commandsFor(strobe, Kind.FLASH_ON);
        List<Sent> off = commandsFor(strobe, Kind.FLASH_OFF);
        assertEquals(on.size(), off.size());
        for (int i = 0; i < on.size(); i++) {
            long durMs = (off.get(i).timeNanos - on.get(i).timeNanos) / 1_000_000;
            assertTrue(durMs <= store.get().safety.strobeMaxOnSec * 1000 + 1, "strobe burst too long: " + durMs);
            if (i > 0) {
                long gap = (on.get(i).timeNanos - off.get(i - 1).timeNanos) / 1_000_000;
                assertTrue(gap >= store.get().safety.strobeMinGapSec * 1000 - 1, "strobe gap too short: " + gap);
            }
        }
    }
}
