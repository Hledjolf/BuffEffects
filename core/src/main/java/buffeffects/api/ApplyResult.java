package buffeffects.api;

import java.util.List;

/**
 * {@code apply}'s answer (api.md section 2). Applied: the application id, what was used, how it combined.
 * Refused: a code (api.md section 7) and a message; nothing changed on the target.
 *
 * @param durationSeconds the run time used; null for infinite or an in-game clock duration
 */
public record ApplyResult(boolean applied, String applicationId, Double durationSeconds, double strength,
                          List<String> droppedParts, How how, String code, String message) {
    public enum How { NEW, REFRESHED, EXTENDED, STACKED }

    public ApplyResult {
        droppedParts = droppedParts == null ? List.of() : List.copyOf(droppedParts);
    }

    public static ApplyResult refused(String code, String message) {
        return new ApplyResult(false, null, null, 0, List.of(), null, code, message);
    }

    /** One chat line: "applied (new, 30 s)" or "refused capped:resist.fire: ...". */
    public String line() {
        if (!applied) return "refused " + code + ": " + message;
        String d = durationSeconds == null ? "no run-time limit" : trim(durationSeconds) + " s";
        return "applied (" + how.name().toLowerCase() + ", " + d + (strength != 1.0 ? ", strength " + trim(strength) : "") + ")"
                + (droppedParts.isEmpty() ? "" : ", dropped here: " + String.join(", ", droppedParts));
    }

    static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
