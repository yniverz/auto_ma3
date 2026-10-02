package automa3.model;

/**
 * Musical section types the engine reacts to. Tuned for techno / electronic music.
 */
public enum Section {
    INTRO(0.30),
    GROOVE(0.55),
    BUILD(0.70),
    BREAKDOWN(0.20),
    DROP(1.00),
    PEAK(0.85),
    OUTRO(0.30);

    /** Default energy (0..1) associated with this section. */
    public final double baseEnergy;

    Section(double baseEnergy) {
        this.baseEnergy = baseEnergy;
    }

    /** True for sections where the kick drum is normally running. */
    public boolean isKicking() {
        return this == GROOVE || this == DROP || this == PEAK;
    }
}
