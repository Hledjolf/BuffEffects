package buffeffects.core.rating;

import buffeffects.core.format.Part;
import java.util.List;
import java.util.Set;

/**
 * The rating BuffEffects asks for (effects.md section 11). In 1.0, BuffEffects' own port of Game Tactics
 * Analysis's method implements it ({@link GtaRater}); CommandDeck's {@code commanddeck.power} replaces it
 * later, behind this same interface.
 */
public interface PowerRater {
    /**
     * Rates an effect's parts. Never returns null: an effect that can't be rated comes back
     * {@link Rating#unrated(String)} with a reason.
     *
     * @param request the parts already scaled to the strength being rated, their duration, and the game
     */
    Rating rate(Request request);

    /**
     * What's rated. {@code parts} are the core parts plus the game binding's, with strength applied, and
     * the game's native effects already turned into parts by the adapter. {@code durationSeconds} is null
     * for the whole fight (infinite, or an in-game clock duration). {@code harmful} turns the rating to the
     * side that applies the effect (effects.md section 11).
     */
    record Request(List<Part> parts, Double durationSeconds, String game, boolean harmful) {
        public Request {
            parts = List.copyOf(parts);
        }

        public Request(List<Part> parts, Double durationSeconds, String game) {
            this(parts, durationSeconds, game, false);
        }
    }

    /**
     * The answer: a finite number of steps, a cliff ({@code steps} null, {@code cliff} true), or unrated
     * ({@code steps} null, {@code cliff} false). {@code note} is {@code cliff:<part>} for a cliff and the
     * reason for an unrated effect ({@code untranslated:<field>}). {@code flags}
     * holds {@code mobility}, {@code escape}, {@code cosmetic} and anything else the method flags.
     */
    record Rating(Double steps, boolean cliff, Set<String> flags, String note) {
        public Rating {
            flags = Set.copyOf(flags);
            if (steps != null && !Double.isFinite(steps)) throw new IllegalArgumentException("a rating is finite: " + steps);
        }

        public Rating(Double steps, boolean cliff, Set<String> flags) {
            this(steps, cliff, flags, null);
        }

        public static Rating cosmetic() {
            return new Rating(0.0, false, Set.of("cosmetic"));
        }

        public static Rating unrated(String reason) {
            return new Rating(null, false, Set.of(), reason);
        }

        public static Rating cliffOf(String part, Set<String> flags) {
            return new Rating(null, true, flags, "cliff:" + part);
        }

        public boolean isCosmetic() {
            return flags.contains("cosmetic");
        }

        /** Neither a number nor a cliff. */
        public boolean isUnrated() {
            return steps == null && !cliff;
        }

        /** A number of steps, possibly negative. */
        public boolean isNumber() {
            return steps != null;
        }
    }
}
