package automa3.model;

/**
 * What an executor is used for. One executor per role is enough for a minimal show;
 * more executors (and extra layers) make the show richer.
 */
public enum Role {
    /** Base look / intensity state. Always one running. */
    BASE(Mode.TOGGLE, true),
    /** Colour look. Held for a whole track (palette). */
    COLOR(Mode.TOGGLE, true),
    /** Movement (pan/tilt phasers etc.). */
    MOVEMENT(Mode.TOGGLE, true),
    /** Dimmer / beam / gobo effects. */
    EFFECT(Mode.TOGGLE, true),
    /** Build-up riser, its fader is ramped up over the build. */
    RISER(Mode.TOGGLE, false),
    /** Accent / hit on the drop beat. */
    ACCENT(Mode.FLASH, false),
    /** Strobe. Safety limited. */
    STROBE(Mode.FLASH, false),
    /** Audience blinder. Safety limited. */
    BLINDER(Mode.FLASH, false),
    /** Blackout right before the drop. */
    BLACKOUT(Mode.FLASH, false),
    /** Hazer: level set by fader per section. */
    HAZE(Mode.FADER, false),
    /** Fog machine: short bursts. */
    FOG(Mode.FLASH, false),
    /** Special effects (CO2, sparkular, confetti, lasers...). Only fires when armed. */
    SPECIAL(Mode.FLASH, false);

    public enum Mode {
        /** Go+ to start, Off to stop. */
        TOGGLE,
        /** Flash On / Flash Off. */
        FLASH,
        /** Temp On / Temp Off. */
        TEMP,
        /** FaderMaster level only. */
        FADER
    }

    public final Mode defaultMode;
    /** Scene roles are part of the continuously running look (one active look per layer). */
    public final boolean sceneRole;

    Role(Mode defaultMode, boolean sceneRole) {
        this.defaultMode = defaultMode;
        this.sceneRole = sceneRole;
    }
}
