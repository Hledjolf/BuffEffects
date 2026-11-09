package buffeffects.core.json;

/** Malformed JSON, with the line and column. */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
