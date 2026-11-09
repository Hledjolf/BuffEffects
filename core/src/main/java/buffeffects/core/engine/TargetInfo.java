package buffeffects.core.engine;

/**
 * A target as the game sees it now. {@code live} is "online, alive and in a loaded chunk": only then does
 * run time count (effects.md section 4).
 */
public record TargetInfo(String id, String name, Kind kind, boolean loaded, boolean alive) {
    public enum Kind { PLAYER, NPC }

    public boolean live() {
        return loaded && alive;
    }
}
