package automa3.config;

import automa3.model.Role;
import automa3.model.Section;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete application configuration. Serialized as JSON (config.json).
 * All fields are public with defaults so a partial file is valid.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {

    /** Port of the web UI. 8081 because grandMA3's own web interface uses 8080. */
    public int webPort = 8081;
    /** 127.0.0.1 = only this Mac. Set to 0.0.0.0 to open the UI from a tablet on the show network. */
    public String webHost = "127.0.0.1";
    public List<ConsoleConfig> consoles = new ArrayList<>();
    public OscInConfig oscIn = new OscInConfig();
    public List<Look> looks = new ArrayList<>();
    public EngineConfig engine = new EngineConfig();
    public SafetyConfig safety = new SafetyConfig();
    public AtmosConfig atmos = new AtmosConfig();
    public SpeedConfig speed = new SpeedConfig();
    public AudioConfig audio = new AudioConfig();
    public DjLinkConfig djLink = new DjLinkConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConsoleConfig {
        public String name = "MA3";
        public boolean enabled = true;
        public String host = "127.0.0.1";
        /** OSC port configured in the console (Menu - In & Out - OSC). */
        public int port = 8000;
        /** Optional OSC prefix configured in the console, without slashes (e.g. "gma3"). */
        public String prefix = "";
        /** grandMA3 software version, e.g. "2.3" or "2.5.1.2". Selects the command profile. */
        public String version = "2.5";
        /** Per-console command template overrides, keyed by command name (see Ma3Profile). */
        public Map<String, String> commandOverrides = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OscInConfig {
        public boolean enabled = true;
        /** Port this app listens on for console feedback and operator controls. */
        public int port = 8001;
        /** Address prefix for operator controls sent from the console with SendOSC. */
        public String controlPrefix = "/automa3";
        /**
         * Optional: sequence number -> control action. Uses MA3 playback feedback (2.1+),
         * e.g. a sequence on an executor used as "AUTO" button. Actions: auto, hold, drop, next,
         * strobe, arm, energy (fader).
         */
        public Map<String, String> controlSequences = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Look {
        public String id;
        public String name = "";
        public Role role = Role.BASE;
        /** Layer name. Scene roles keep one active look per layer. Defaults to the role name. */
        public String layer;
        public int page = 1;
        public int exec = 101;
        /** Activation mode, defaults to the role's default mode. */
        public Role.Mode mode;
        /** Sections this look may be used in. Empty = all. */
        public List<Section> sections = new ArrayList<>();
        public double energyMin = 0.0;
        public double energyMax = 1.0;
        public double weight = 1.0;
        /** Sequence number of the executor's sequence; enables operator-touch detection (MA3 2.1+). */
        public Integer sequence;
        /** For flash looks: how many beats to keep it on (0 = engine default for the role). */
        public double flashBeats = 0;
        /** For fader/haze looks: maximum fader level 0..100. */
        public int level = 100;
        public boolean enabled = true;
        /** What the look does, free text (e.g. "fast circle on the spots"). */
        public String description = "";
        /** Free tags, kept as they are (e.g. from a show generator). */
        public List<String> tags = new ArrayList<>();
        /** Arbitrary data from other tools, kept untouched and exported again. */
        public java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();

        public String layerName() {
            return layer == null || layer.isBlank() ? role.name() : layer;
        }

        public Role.Mode modeOrDefault() {
            return mode == null ? role.defaultMode : mode;
        }

        public String label() {
            return (name == null || name.isBlank() ? role.name() : name) + " [" + page + "." + exec + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EngineConfig {
        /** Commands are sent this many ms early so the light lands on the beat. */
        public int latencyMs = 30;
        /** Change one look layer every N bars in GROOVE. */
        public int rotateBarsGroove = 32;
        /** Change one look layer every N bars in PEAK / DROP. */
        public int rotateBarsPeak = 16;
        /** Bars after a drop that count as the "drop" (rest becomes PEAK). */
        public int dropBars = 16;
        /** Keep the same colour look for a whole track. */
        public boolean colorPerTrack = true;
        /** Pick a new colour on every drop. */
        public boolean colorChangeOnDrop = false;
        /** Blackout on the last beat before a drop (needs a BLACKOUT look). */
        public boolean preDropBlackout = true;
        /** Strobe during the last N beats of a build (0 = off). */
        public int buildStrobeBeats = 4;
        /** Strobe for N beats starting on the drop (0 = off). */
        public int dropStrobeBeats = 4;
        /** Blinder hit every N bars while in DROP (0 = only on the drop beat). */
        public int dropBlinderEveryBars = 8;
        /** Default flash length in beats for ACCENT looks. */
        public double accentBeats = 2;
        /** Blackout during short breaks: the kick out for half a bar to two bars, or the music stopping. */
        public boolean breakBlackout = true;
        /** Accent hit when the kick comes back after a short break. */
        public boolean breakReturnAccent = true;
        /** Accent flash on each single bass hit in builds and breakdowns. */
        public boolean bassHitFlash = true;
        /** Length of a bass hit flash (beats). */
        public double bassHitBeats = 1;
        /** Bars the engine keeps its hands off a layer the operator touched. */
        public int operatorLockBars = 16;
        /** Delay (ms) between starting a new look and stopping the old one on the same layer. */
        public int crossfadeOffDelayMs = 0;
        /** Stop the show's looks (and haze) this many seconds after the music stopped (0 = never). */
        public double releaseWhenStoppedSec = 3;
        /** Stop all auto looks when AUTO is switched off. */
        public boolean releaseOnAutoOff = false;
        /** Keep BPM sync running when AUTO is off. */
        public boolean bpmSyncWhenManual = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SafetyConfig {
        public boolean strobeAllowedAtStart = true;
        public double strobeMaxOnSec = 4.0;
        public double strobeMinGapSec = 8.0;
        /** Maximum fraction of any 60 s window the strobe may be on. */
        public double strobeMaxDutyPerMinute = 0.25;
        public double blinderMaxOnSec = 2.0;
        public double blinderMinGapSec = 4.0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AtmosConfig {
        public boolean hazeEnabled = true;
        /** Haze fader level per section in percent of the look's level. */
        public Map<Section, Integer> hazeLevels = defaultHaze();
        public double hazeFadeSec = 10;
        public boolean fogEnabled = true;
        /** Fire fog this many beats before a drop so it is in the air when it hits. */
        public int fogLeadBeats = 16;
        public double fogBurstSec = 3;
        public double fogCooldownSec = 90;
        public boolean fogInBreakdown = true;
        /** Specials (CO2 etc.) only fire on drops, when armed. */
        public double specialBurstSec = 1.5;
        public double specialCooldownSec = 180;
        public int specialMaxPerHour = 8;

        private static Map<Section, Integer> defaultHaze() {
            Map<Section, Integer> m = new EnumMap<>(Section.class);
            m.put(Section.INTRO, 40);
            m.put(Section.GROOVE, 40);
            m.put(Section.BUILD, 60);
            m.put(Section.BREAKDOWN, 70);
            m.put(Section.DROP, 50);
            m.put(Section.PEAK, 45);
            m.put(Section.OUTRO, 35);
            return m;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpeedConfig {
        public boolean enabled = true;
        /** Speed master number (Master 3.N). */
        public int master = 1;
        /** Multiply the DJ BPM before sending (0.5 = half time, 2 = double time). */
        public double multiplier = 1.0;
        /** Minimum BPM change before an update is sent. */
        public double minChange = 0.1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AudioConfig {
        public boolean enabled = false;
        /** Part of the input device name, empty = default input. */
        public String device = "";
        public float sampleRate = 44100f;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DjLinkConfig {
        /**
         * Which track analysis drives the show: "auto" (rekordbox phrases when the track has them, else the
         * waveform), "phrase" (rekordbox phrases, waveform only as fallback) or "waveform" (own waveform analysis,
         * rekordbox phrases only as fallback).
         */
        public String analysisMode = "auto";
        /**
         * Which deck drives the lights when a mixer reports on-air state: "mixer" (only a deck whose fader is up;
         * all faders down = no deck) or "playing" (decks on air first, else any playing deck). Without a mixer
         * the playing deck is used in both modes; the Follow button overrides both.
         */
        public String deckSelection = "mixer";
        /**
         * Read rekordbox data straight off the players' USB media over the network (beat-link "Crate Digger").
         * Off: everything is requested from the players directly. Some rekordbox 7 exports make the file route
         * fail with retries (several seconds delay per track), so it is off by default.
         */
        public boolean readUsbFiles = false;
        /** Use the waveform to detect sections when a track has no rekordbox phrase analysis. */
        public boolean waveformAnalysis = true;
    }
}
