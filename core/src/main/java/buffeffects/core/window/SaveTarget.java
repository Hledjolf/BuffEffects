package buffeffects.core.window;

/**
 * Where a saved effect goes (window.md section 3): the {@code default} mod, a mod's own sidecar in its
 * source folder ("export to mod"), or the server's override folder (a replacement of someone else's effect).
 *
 * @param mod     the mod whose sidecar or override file is written ({@code default} for the default mod)
 * @param path    the file written
 * @param message why it went there, for the window
 */
public record SaveTarget(Kind kind, String mod, String path, String message) {
    public enum Kind { DEFAULT, SOURCE, OVERRIDE }
}
