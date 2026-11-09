package buffeffects.api;

/** {@code list}'s filter (api.md section 4). Null fields don't filter. */
public record ListFilter(String mod, String category, String tag, Boolean harmful, Boolean cosmetic, Boolean usableHere) {
    public static ListFilter all() {
        return new ListFilter(null, null, null, null, null, null);
    }

    public static ListFilter ofMod(String mod) {
        return new ListFilter(mod, null, null, null, null, null);
    }
}
