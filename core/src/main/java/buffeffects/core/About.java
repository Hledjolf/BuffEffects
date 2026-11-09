package buffeffects.core;

/**
 * The core's identity. The effect model, the ledger and the calling API come later, once the design settles
 * them (design/README.md, "To decide"). Until then the core only proves that the build and the boundary work.
 */
public final class About {
    public static final String NAME = "BuffEffects";

    private About() {
    }

    /** One line for an adapter's startup log: the mod name and the version the adapter was built as. */
    public static String startupLine(String version, String game) {
        return "[" + NAME + "] loaded: version " + version + ", game adapter " + game;
    }
}
