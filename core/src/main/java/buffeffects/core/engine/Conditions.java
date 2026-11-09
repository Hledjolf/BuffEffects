package buffeffects.core.engine;

/**
 * Evaluates {@code ends_when} conditions (effects.md section 8). The adapter answers the game's native
 * ones itself and hands the rest to CommandDeck's condition library when it's installed. UNKNOWN never
 * ends an effect.
 */
public interface Conditions {
    enum Tri { TRUE, FALSE, UNKNOWN }

    /** Evaluates a condition (as read from the file) with the bearer as the default subject. */
    Tri evaluate(Object condition, String bearerId);

    /**
     * The same, with the effect's actor as {@code {caster}} (effects.md section 8). {@code casterId} may be null.
     */
    default Tri evaluate(Object condition, String bearerId, String casterId) {
        return evaluate(condition, bearerId);
    }

    /**
     * Whether the game checks this condition natively (on Hytale: in a fluid, has an effect, game mode). Those
     * are checked at once (every tick); the rest every 5 s of run time (effects.md section 8).
     */
    default boolean isNative(Object condition) {
        return false;
    }

    /** Whether this condition can be evaluated at all here (else the effect loads with a warning). */
    boolean canEvaluate(Object condition);

    Conditions NONE = new Conditions() {
        public Tri evaluate(Object condition, String bearerId) {
            return Tri.UNKNOWN;
        }

        public boolean canEvaluate(Object condition) {
            return false;
        }
    };
}
