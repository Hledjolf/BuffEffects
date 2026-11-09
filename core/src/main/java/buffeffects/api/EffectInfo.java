package buffeffects.api;

import java.util.List;
import java.util.Set;

/**
 * {@code describe}'s answer (api.md section 4): the definition as loaded (after overrides) as JSON text,
 * plus what BuffEffects knows about it here.
 *
 * @param status        {@code active}, {@code dormant}, {@code excessive} or {@code disabled}
 * @param derivedSteps  the load-time rating at maximum strength; null for a cliff or when unrated
 * @param flags         {@code mobility}, {@code escape}, {@code cosmetic}...
 * @param definitionJson the definition as JSON text; for a game's own effect ({@code hytale:Burn}), the
 *                      adapter's translation (effects.md section 9.1). Never null
 * @param ratingNote    {@code cliff:<part>} for a cliff, the reason when unrated
 *                      ({@code untranslated:<field>}), else null
 */
public record EffectInfo(String effect, String mod, String name, String description, boolean harmful, String category,
                         List<String> tags, List<String> games, String status, String statusWhy, Double derivedSteps,
                         boolean cliff, boolean rated, Double declaredPower, Set<String> flags, boolean optedIn,
                         boolean overridden, String definitionJson, String ratingNote) {
    public EffectInfo {
        tags = List.copyOf(tags);
        games = List.copyOf(games);
        flags = Set.copyOf(flags);
    }

    public boolean cosmetic() {
        return flags.contains("cosmetic");
    }
}
