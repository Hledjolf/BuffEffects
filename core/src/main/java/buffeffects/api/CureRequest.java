package buffeffects.api;

import java.util.List;

/**
 * A cure (api.md section 3): every active effect matching any of {@code harmful}, {@code tags} (against
 * {@code cure_tags}) or {@code effects} (references) is removed.
 *
 * @param asPerson a player curing on their own behalf: needs {@code buffeffects.cure}, or the cure is
 *                 limited to what the caller itself applied
 */
public record CureRequest(boolean harmful, List<String> tags, List<String> effects, String asPerson, String sourceId,
                          String actor) {
    public CureRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    public static CureRequest harmfulOnes() {
        return new CureRequest(true, List.of(), List.of(), null, null, null);
    }

    public static CureRequest tagged(String... tags) {
        return new CureRequest(false, List.of(tags), List.of(), null, null, null);
    }

    public static CureRequest of(String... effects) {
        return new CureRequest(false, List.of(), List.of(effects), null, null, null);
    }

    public CureRequest asPerson(String person) {
        return new CureRequest(harmful, tags, effects, person, sourceId, actor);
    }
}
