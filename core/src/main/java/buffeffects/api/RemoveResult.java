package buffeffects.api;

import java.util.List;

/** What {@code remove} or {@code cure} removed. Removing what's already gone isn't an error: the list is empty. */
public record RemoveResult(List<ActiveApplication> removed) {
    public RemoveResult {
        removed = List.copyOf(removed);
    }
}
