package buffeffects.core.format;

/**
 * An effect's duration (effects.md section 4). {@code runMs} is run time in milliseconds, or -1 when the
 * effect has none. {@code worldPoint} is {@code dawn}, {@code dusk} or {@code end_of_day}, or null;
 * {@code worldDays} is a number of in-game days, or 0. With both run time and a world point, whichever
 * comes first ends it.
 */
public record DurationSpec(long runMs, boolean infinite, String worldPoint, int worldDays) {
    public static DurationSpec seconds(double s) {
        return new DurationSpec(Math.round(s * 1000), false, null, 0);
    }

    public static DurationSpec forever() {
        return new DurationSpec(-1, true, null, 0);
    }

    public boolean hasRunTime() {
        return runMs >= 0;
    }

    /** {@code { "seconds": 0 }}: the once-only parts happen and the application ends at once. */
    public boolean isInstant() {
        return runMs == 0 && !infinite && !usesWorldClock();
    }

    public boolean usesWorldClock() {
        return worldPoint != null || worldDays > 0;
    }

    /** The run time a rating should assume: run time if any, else null (infinite or the world clock). */
    public Long ratedMs() {
        return hasRunTime() ? runMs : null;
    }
}
