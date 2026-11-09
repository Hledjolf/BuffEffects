package buffeffects.api;

import java.util.Set;

/** Which events a listener gets (api.md section 6). Null or empty fields don't filter. */
public record EventFilter(String effect, String target, Set<String> sourceIds) {
    public EventFilter {
        sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
    }

    public static EventFilter all() {
        return new EventFilter(null, null, null);
    }
}
