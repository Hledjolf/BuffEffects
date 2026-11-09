package buffeffects.core.engine;

/**
 * A game's native ceilings (GTA-D004): a cap never goes below what the game itself allows. NaN means the
 * game sets no ceiling there, and BuffEffects' default applies.
 *
 * @param maxResist            the highest resistance per cause the game allows natively (1.0 = immunity)
 * @param maxRegenPerSecond    the strongest vanilla health regeneration, per second
 * @param maxInvulnerableSeconds the longest vanilla invulnerability
 */
public record CapProfile(double maxResist, double maxRegenPerSecond, double maxInvulnerableSeconds) {
    public static final CapProfile NONE = new CapProfile(Double.NaN, Double.NaN, Double.NaN);

    /** The cap in force: the setting, raised to the native ceiling. */
    static double atLeast(double setting, double ceiling) {
        return Double.isNaN(ceiling) ? setting : Math.max(setting, ceiling);
    }
}
