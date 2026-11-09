package buffeffects.core.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The neutral names every game maps onto (effects.md section 6): stats, damage causes, actions. */
public final class Vocabulary {
    private Vocabulary() {
    }

    public static final Set<String> STATS = Set.of("health", "stamina", "mana");

    public static final List<String> CAUSE_LEAVES = List.of("slashing", "bludgeoning", "crush", "projectile",
            "fire", "ice", "lightning", "poison", "earth", "water", "wind", "fall", "drowning", "suffocation",
            "environmental", "shadow", "holy", "arcane", "mind", "sonic", "disease", "chaos");

    /** Causes that aren't the games' own (effects.md 1.0): each game's adapter registers the ones in use. */
    public static final List<String> ADDED_CAUSES = List.of("shadow", "holy", "arcane", "mind", "sonic", "disease", "chaos");

    public static final Map<String, List<String>> CAUSE_PARENTS = Map.of(
            "physical", List.of("slashing", "bludgeoning", "crush"),
            "elemental", List.of("earth", "fire", "ice", "lightning", "poison", "water", "wind"));

    public static final List<String> ACTIONS =
            List.of("attack", "secondary", "ability1", "ability2", "ability3", "use", "dodge", "pickup");

    /** What {@code intangible} always brings (effects.md section 6, decided 2026-11-05). */
    public static final List<String> INTANGIBLE_ACTIONS =
            List.of("attack", "secondary", "ability1", "ability2", "ability3");

    public static final List<String> MOVEMENTS = List.of("all", "jump", "sprint", "crouch");

    public static boolean isNeutralCause(String c, Set<String> extra) {
        return CAUSE_LEAVES.contains(c) || CAUSE_PARENTS.containsKey(c) || extra.contains(c);
    }

    /** A parent's leaves, or the cause itself. */
    public static List<String> leaves(String cause) {
        List<String> l = CAUSE_PARENTS.get(cause);
        return l != null ? l : new ArrayList<>(List.of(cause));
    }
}
