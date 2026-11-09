package buffeffects.api;

/** What {@code remove} removes: exactly one of the four (api.md section 3). */
public record Selector(Kind kind, String value) {
    public enum Kind { APPLICATION_ID, EFFECT, SOURCE_ID, CALLER }

    public static Selector application(String id) {
        return new Selector(Kind.APPLICATION_ID, id);
    }

    public static Selector effect(String ref) {
        return new Selector(Kind.EFFECT, ref);
    }

    public static Selector source(String sourceId) {
        return new Selector(Kind.SOURCE_ID, sourceId);
    }

    /** Everything the calling mod applied to the target (its own only). */
    public static Selector mine() {
        return new Selector(Kind.CALLER, null);
    }
}
