package buffeffects.api;

/**
 * One event (api.md section 6), delivered after the change is made.
 *
 * @param application the application as {@code active} would show it (for {@code REFUSED}: null)
 * @param detail      APPLIED: {@code new}, {@code refreshed}, {@code extended}, {@code stacked};
 *                    REMOVED: the reason ({@code removed}, {@code cured}, {@code expired}, {@code ends_when},
 *                    {@code death}, {@code replaced}, {@code disabled}, {@code unloaded_effect}, {@code hit});
 *                    REFUSED: the code; SLOT_CHANGED: {@code <slot>:taken} or {@code <slot>:lost}
 * @param effect      the effect reference (also for REFUSED)
 * @param target      the target
 */
public record BfxEvent(Type type, ActiveApplication application, String detail, String effect, String target) {
    public enum Type { APPLIED, REMOVED, REFUSED, PAUSED, RESUMED, SLOT_CHANGED }
}
