package automa3.ma3;

/**
 * A console-independent action. {@link ConsoleHub} renders it into the command syntax
 * of each connected console's version profile.
 */
public record Ma3Action(Kind kind, int page, int exec, double value, double fadeSec, int master, double bpm,
                        String raw, String reason) {

    public enum Kind { GO, OFF, FLASH_ON, FLASH_OFF, TEMP_ON, TEMP_OFF, FADER, SPEED_BPM, RAW }

    public static Ma3Action exec(Kind kind, int page, int exec, String reason) {
        return new Ma3Action(kind, page, exec, 0, 0, 0, 0, null, reason);
    }

    public static Ma3Action fader(int page, int exec, double value, double fadeSec, String reason) {
        return new Ma3Action(Kind.FADER, page, exec, value, fadeSec, 0, 0, null, reason);
    }

    public static Ma3Action bpm(int master, double bpm, String reason) {
        return new Ma3Action(Kind.SPEED_BPM, 0, 0, 0, 0, master, bpm, null, reason);
    }

    public static Ma3Action raw(String command, String reason) {
        return new Ma3Action(Kind.RAW, 0, 0, 0, 0, 0, 0, command, reason);
    }

    /** Render with a profile and optional per-console overrides. Returns null if not applicable. */
    public String render(Ma3Profile profile, java.util.Map<String, String> overrides) {
        String key = switch (kind) {
            case GO -> Ma3Profile.GO;
            case OFF -> Ma3Profile.OFF;
            case FLASH_ON -> Ma3Profile.FLASH_ON;
            case FLASH_OFF -> Ma3Profile.FLASH_OFF;
            case TEMP_ON -> Ma3Profile.TEMP_ON;
            case TEMP_OFF -> Ma3Profile.TEMP_OFF;
            case FADER -> fadeSec > 0 ? Ma3Profile.FADER_FADE : Ma3Profile.FADER;
            case SPEED_BPM -> Ma3Profile.SPEED_BPM;
            case RAW -> null;
        };
        if (key == null) return raw;
        String template = overrides != null && overrides.containsKey(key) && !overrides.get(key).isBlank()
                ? overrides.get(key) : profile.templates.get(key);
        double outBpm = bpm;
        if (kind == Kind.SPEED_BPM) {
            if (!profile.speedMasterUsable(master)) return null;
            while (outBpm > profile.maxBpm) outBpm /= 2;
        }
        return template
                .replace("{page}", Integer.toString(page))
                .replace("{exec}", Integer.toString(exec))
                .replace("{value}", fmt(value))
                .replace("{fade}", fmt(fadeSec))
                .replace("{master}", Integer.toString(master))
                .replace("{bpm}", fmt(outBpm));
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
