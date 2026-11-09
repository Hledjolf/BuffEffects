package buffeffects.core.format;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One game's layer of an effect (effects.md section 7). Ids inside are the game's own, verbatim.
 * The look maps hold the fields as read ({@code tint_top}, {@code particles}, {@code see_through}...);
 * the adapter turns them into the game's assets.
 */
public record Binding(String game, String icon, Map<String, Object> whileActive, Map<String, Object> onApply,
                      Map<String, Object> onRemove, String shape, List<String> natives, List<Part> parts,
                      Object endsWhen) {
    public Binding {
        whileActive = Collections.unmodifiableMap(new LinkedHashMap<>(whileActive));
        onApply = Collections.unmodifiableMap(new LinkedHashMap<>(onApply));
        onRemove = Collections.unmodifiableMap(new LinkedHashMap<>(onRemove));
        natives = List.copyOf(natives);
        parts = List.copyOf(parts);
    }

    public static Binding empty(String game) {
        return new Binding(game, null, Map.of(), Map.of(), Map.of(), null, List.of(), List.of(), null);
    }

    public boolean hasLook() {
        return icon != null || !whileActive.isEmpty() || !onApply.isEmpty() || !onRemove.isEmpty() || shape != null;
    }
}
