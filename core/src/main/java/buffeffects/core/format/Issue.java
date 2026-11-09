package buffeffects.core.format;

/**
 * One finding at load: a refusal (the effect, or the whole file, isn't loaded), a warning, or a log
 * line (dormant, excessive). Codes are effects.md section 14's.
 */
public record Issue(Severity severity, String code, String file, String effectId, String pointer, String message) {
    public enum Severity { REFUSAL, WARNING, LOG }

    public static Issue refusal(String code, String file, String effectId, String pointer, String message) {
        return new Issue(Severity.REFUSAL, code, file, effectId, pointer, message);
    }

    public static Issue warning(String code, String file, String effectId, String pointer, String message) {
        return new Issue(Severity.WARNING, code, file, effectId, pointer, message);
    }

    public static Issue log(String code, String file, String effectId, String pointer, String message) {
        return new Issue(Severity.LOG, code, file, effectId, pointer, message);
    }

    /** One line for the server log: {@code [refused invalid] file#/effects/0/duration (spooked): message}. */
    public String line() {
        String kind = severity == Severity.REFUSAL ? "refused " : severity == Severity.WARNING ? "warning " : "";
        return "[" + kind + code + "] " + (file == null ? "" : file) + (pointer == null ? "" : "#" + pointer)
                + (effectId == null ? "" : " (" + effectId + ")") + ": " + message;
    }
}
