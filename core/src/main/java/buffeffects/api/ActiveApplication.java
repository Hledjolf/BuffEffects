package buffeffects.api;

import java.util.List;

/**
 * One application on a target, as {@code active} reports it (api.md section 4).
 *
 * @param name        the name to show for its stacks (effects.md section 5): plain text, or a {@code lang:} key
 * @param secondsLeft run time left; null when it has none (infinite, or only an in-game clock end)
 * @param worldEnd    the in-game clock point it ends at ({@code dawn}, {@code 3 days}...), or null
 * @param stacks      how many applications of this effect are on the target
 * @param dormant     null when it counts; else why not ({@code category:poison}, {@code slot:model} for a
 *                    part waiting for a slot, {@code paused})
 */
public record ActiveApplication(String applicationId, String effect, String name, String target, Double secondsLeft, boolean infinite,
                                String worldEnd, int stacks, double strength, String sourceId, String caller,
                                String actor, String dormant, boolean paused, List<String> droppedParts) {
    public ActiveApplication {
        droppedParts = List.copyOf(droppedParts);
    }
}
