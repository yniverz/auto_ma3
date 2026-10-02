package automa3.ma3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command syntax and capabilities for a range of grandMA3 software versions.
 *
 * <p>Sources: the official grandMA3 user manuals 2.0 - 2.5 (help.malighting.com), compared page by page:
 * <ul>
 *   <li>"/cmd" OSC command line access: documented in all of 2.0 - 2.5.</li>
 *   <li>Go+, Off, Flash On/Off, Temp On/Off, Top, Toggle, FaderMaster ... At ... Fade, Master 3.x At BPM:
 *       identical syntax and examples in 2.0 - 2.5.</li>
 *   <li>OSC playback feedback of pool objects (e.g. /13.13.1.6.X for sequences): documented from 2.1.</li>
 *   <li>Speed masters: 15 in 2.0 - 2.3; 16 from 2.4 where speed master 16 is the audio driven BPM master
 *       (range 0 - 225 BPM).</li>
 * </ul>
 * 1.x manuals are no longer published, so the 1.x profile reuses the 2.0 syntax and is marked unverified.
 * Every template can be overridden per console in the config.</p>
 */
public final class Ma3Profile {

    /** Template keys. Placeholders: {page} {exec} {value} {fade} {master} {bpm}. */
    public static final String GO = "go";
    public static final String OFF = "off";
    public static final String FLASH_ON = "flashOn";
    public static final String FLASH_OFF = "flashOff";
    public static final String TEMP_ON = "tempOn";
    public static final String TEMP_OFF = "tempOff";
    public static final String FADER = "fader";
    public static final String FADER_FADE = "faderFade";
    public static final String SPEED_BPM = "speedBpm";

    public final String id;
    public final String label;
    public final int minMajor, minMinor, maxMajor, maxMinor;
    public final boolean verified;
    /** Console sends OSC playback feedback for pool objects (needed for operator touch detection). */
    public final boolean playbackFeedback;
    /** Address of sequence X in the OSC feedback, "%d" = sequence number. */
    public final String sequenceFeedbackAddress;
    public final int speedMasterCount;
    /** Speed masters the app must not drive (e.g. the audio BPM master). */
    public final List<Integer> reservedSpeedMasters;
    public final double maxBpm;
    public final Map<String, String> templates;
    public final List<String> notes;

    private Ma3Profile(String id, String label, int minMajor, int minMinor, int maxMajor, int maxMinor,
                       boolean verified, boolean playbackFeedback, int speedMasterCount,
                       List<Integer> reservedSpeedMasters, Map<String, String> templates, List<String> notes) {
        this.id = id;
        this.label = label;
        this.minMajor = minMajor;
        this.minMinor = minMinor;
        this.maxMajor = maxMajor;
        this.maxMinor = maxMinor;
        this.verified = verified;
        this.playbackFeedback = playbackFeedback;
        this.sequenceFeedbackAddress = "/13.13.1.6.%d";
        this.speedMasterCount = speedMasterCount;
        this.reservedSpeedMasters = reservedSpeedMasters;
        this.maxBpm = 225;
        this.templates = Collections.unmodifiableMap(templates);
        this.notes = notes;
    }

    private static Map<String, String> baseTemplates() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put(GO, "Go+ Page {page}.{exec}");
        t.put(OFF, "Off Page {page}.{exec}");
        t.put(FLASH_ON, "Flash On Page {page}.{exec}");
        t.put(FLASH_OFF, "Flash Off Page {page}.{exec}");
        t.put(TEMP_ON, "Temp On Page {page}.{exec}");
        t.put(TEMP_OFF, "Temp Off Page {page}.{exec}");
        t.put(FADER, "FaderMaster Page {page}.{exec} At {value}");
        t.put(FADER_FADE, "FaderMaster Page {page}.{exec} At {value} Fade {fade}");
        t.put(SPEED_BPM, "Master 3.{master} At BPM {bpm}");
        return t;
    }

    public static final List<Ma3Profile> ALL;

    static {
        List<Ma3Profile> all = new ArrayList<>();
        all.add(new Ma3Profile("1.x", "grandMA3 1.x (unverified)", 1, 0, 1, 99, false, false, 15, List.of(),
                baseTemplates(), List.of(
                "MA no longer publishes 1.x manuals; syntax assumed equal to 2.0. Test every look with the Test button.",
                "No playback feedback: operator touch detection off. Use the SendOSC control macros.")));
        all.add(new Ma3Profile("2.0", "grandMA3 2.0", 2, 0, 2, 0, true, false, 15, List.of(),
                baseTemplates(), List.of(
                "Pool-object OSC feedback is not documented in 2.0: operator touch detection off.",
                "Use the SendOSC control macros for AUTO / HOLD / DROP etc.")));
        all.add(new Ma3Profile("2.1-2.3", "grandMA3 2.1 - 2.3", 2, 1, 2, 3, true, true, 15, List.of(),
                baseTemplates(), List.of(
                "15 speed masters (Master 3.1 - 3.15).")));
        all.add(new Ma3Profile("2.4-2.5", "grandMA3 2.4 - 2.5", 2, 4, 2, 5, true, true, 16, List.of(16),
                baseTemplates(), List.of(
                "Speed master 16 is the audio BPM master and is never driven by this app.")));
        all.add(new Ma3Profile("2.6+", "grandMA3 2.6 and newer (assumes 2.5 syntax)", 2, 6, 99, 99, false, true, 16, List.of(16),
                baseTemplates(), List.of(
                "Newer than the latest manual checked (2.5). Syntax assumed unchanged; test with the Test buttons.",
                "If a command changed, override it for this console in the config.")));
        ALL = Collections.unmodifiableList(all);
    }

    public boolean matches(int major, int minor) {
        int v = major * 1000 + minor;
        return v >= minMajor * 1000 + minMinor && v <= maxMajor * 1000 + maxMinor;
    }

    /** Pick the profile for a version string such as "2.3", "2.5.1.2" or a profile id. */
    public static Ma3Profile forVersion(String version) {
        if (version != null) {
            for (Ma3Profile p : ALL) if (p.id.equalsIgnoreCase(version.trim())) return p;
            String[] parts = version.trim().split("\\.");
            try {
                int major = Integer.parseInt(parts[0].replaceAll("\\D", ""));
                int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("\\D", "")) : 0;
                for (Ma3Profile p : ALL) if (p.matches(major, minor)) return p;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return ALL.get(ALL.size() - 2); // latest verified
    }

    /** True if the app may drive this speed master on this version. */
    public boolean speedMasterUsable(int master) {
        return master >= 1 && master <= speedMasterCount && !reservedSpeedMasters.contains(master);
    }
}
