package buffeffects.core.commands;

import buffeffects.core.engine.Settings;

/** What the {@code /bfx} commands need from the game. */
public interface CommandHost {
    /** A player's target id by name (online players), or null. */
    String findPlayer(String name);

    /** A target's display name, or the id itself. */
    String nameOf(String targetId);

    /** Whether a person holds a node. Operators hold every node; the console is an operator. */
    boolean hasPermission(String personId, String node);

    /** Opens the editor window for a person (commands-settings.md: {@code /bfx}, {@code /bfx editor}). */
    void openEditor(String personId);

    /** Writes BuffEffects' settings to its config file (Mod Settings Manager edits the same file). */
    void saveSettings(Settings settings);

    /** Reloads the effects, so changed opt-ins take effect. */
    default void reload() {
    }
}
