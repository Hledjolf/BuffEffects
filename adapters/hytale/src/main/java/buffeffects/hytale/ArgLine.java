package buffeffects.hytale;

/** The text after {@code /bfx} (kept apart from the command class, which needs a running server to load). */
final class ArgLine {
    private ArgLine() {
    }

    /** The text after {@code /bfx}: the input, minus a leading slash and the command word. */
    static String of(String input) {
        String s = input == null ? "" : input.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        String first = sp < 0 ? s : s.substring(0, sp);
        if (first.equalsIgnoreCase("bfx") || first.equalsIgnoreCase("buffeffects")) s = sp < 0 ? "" : s.substring(sp + 1);
        return s.trim();
    }
}
