package buffeffects.core.format;

import java.util.Set;

/**
 * What the running game's adapter tells the loader: which parts it can do, which of its own ids exist.
 * The loader's game-specific checks go through this, so the core never names a game API.
 */
public interface GameProfile {
    /** The profile name, the key of the game's binding: {@code hytale}. */
    String name();

    /** Whether {@code { "world": ... }} durations work here. */
    boolean supportsWorldClock();

    /** Whether the adapter can do this part (already validated, scaled to its maximum strength). */
    boolean supports(Part part);

    /** Whether {@code resist} above 1.0 (absorb) exists natively. */
    boolean supportsAbsorb();

    /** Neutral cause names the adapter adds, such as {@code shadow} once it's registered (P12). */
    Set<String> extraCauses();

    /** What a game id names. */
    enum Kind { STAT, CAUSE, EFFECT, ICON, PARTICLE, MODEL_VFX, SCREEN_EFFECT, ANIMATION, SOUND, MODEL }

    /**
     * Whether the game has this id (verbatim, without a prefix). {@code null} when the adapter can't tell
     * (the loader then accepts it).
     */
    Boolean knows(Kind kind, String id);

    /**
     * A native effect of this game, translated at load (effects.md section 9.1), or null when the game has
     * no such effect or the adapter doesn't translate.
     */
    default NativeEffect nativeEffect(String id) {
        return null;
    }

    /**
     * A native effect of this game as parts, for the rating and the caps (effects.md sections 9.1, 11),
     * including {@code untranslated} markers. Empty when unknown.
     */
    default java.util.List<Part> nativeParts(String id) {
        NativeEffect n = nativeEffect(id);
        return n == null ? java.util.List.of() : n.parts();
    }

    /**
     * The game asset an effect becomes (effects.md section 7: {@code BFX_<mod id>_<id>} on Hytale), or null
     * on a game without effect assets. Two effects with one name are both refused ({@code asset_name_clash}).
     */
    default String assetName(String fullId) {
        return null;
    }

    /** A profile that knows nothing and checks nothing: for structural validation only (the schema tests). */
    static GameProfile none() {
        return new GameProfile() {
            public String name() {
                return "none";
            }

            public boolean supportsWorldClock() {
                return true;
            }

            public boolean supports(Part part) {
                return true;
            }

            public boolean supportsAbsorb() {
                return true;
            }

            public Set<String> extraCauses() {
                return Set.of();
            }

            public Boolean knows(Kind kind, String id) {
                return null;
            }
        };
    }
}
