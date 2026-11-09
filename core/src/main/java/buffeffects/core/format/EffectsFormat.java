package buffeffects.core.format;

import buffeffects.core.format.EffectDef.Bounds;
import buffeffects.core.format.EffectDef.OnDeath;
import buffeffects.core.format.EffectDef.Reapply;
import buffeffects.core.json.Json;
import buffeffects.core.json.JsonException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates one {@code buffeffects.effects} sidecar (effects.md, draft 0.5). Refusals are per
 * effect: one bad effect doesn't take the rest of the file with it. A malformed document, an unknown major
 * version or a {@code mod.id} that isn't the shipping mod refuses the whole file. Cross-mod references are
 * checked later, against every loaded mod ({@link EffectLoader}).
 */
public final class EffectsFormat {
    public static final String FORMAT_ID = "buffeffects.effects";
    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");
    static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    static final Pattern MOD_ID = Pattern.compile("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+|default");
    static final BigDecimal LIMIT = new BigDecimal("1e11");

    private EffectsFormat() {
    }

    /** What a file gave: the effects that passed, and every finding. {@code modId} is null if the file was refused. */
    public record Result(String modId, List<EffectDef> effects, List<Issue> issues) {
        public boolean fileRefused() {
            return modId == null;
        }
    }

    /**
     * Reads a sidecar. {@code shippingMod} is the mod the file was found in (null to trust {@code mod.id},
     * for the editor and tests). {@code game} does the game-specific checks.
     */
    public static Result read(String file, String text, String shippingMod, GameProfile game) {
        Object doc;
        try {
            doc = Json.parse(text);
        } catch (JsonException e) {
            return refused(Issue.refusal("invalid", file, null, "", "not JSON: " + e.getMessage()));
        }
        return read(file, doc, shippingMod, game);
    }

    public static Result read(String file, Object doc, String shippingMod, GameProfile game) {
        if (!(doc instanceof Map)) return refused(Issue.refusal("invalid", file, null, "", "the document must be an object"));
        Map<?, ?> d = (Map<?, ?>) doc;
        List<Issue> issues = new ArrayList<>();
        if (!FORMAT_ID.equals(d.get("format"))) {
            return refused(Issue.refusal("invalid", file, null, "/format", "format must be \"" + FORMAT_ID + "\""));
        }
        Object fv = d.get("format_version");
        int[] ver = fv instanceof String ? version((String) fv) : null;
        if (ver == null) return refused(Issue.refusal("invalid", file, null, "/format_version", "format_version must be MAJOR.MINOR, such as \"1.0\""));
        // The 0.x drafts are read as 1.0: 0.5 is the same format (effects.md, 1.0 of 2026-11-08), and what
        // earlier drafts changed since (lines) is refused with its own message.
        if (ver[0] == 0) ver = new int[] {1, 0};
        if (ver[0] != MAJOR) {
            return refused(Issue.refusal("invalid", file, null, "/format_version",
                    "format_version " + fv + " has a major version this reader doesn't know (it reads " + MAJOR + "." + MINOR + ")"));
        }
        boolean newer = ver[1] > MINOR;
        Ctx top = new Ctx(file, null, newer, issues);
        top.allowed(d, "", Set.of("format", "format_version", "mod", "effects", "summons", "$schema"));
        Object mod = d.get("mod");
        if (!(mod instanceof Map)) return refused(Issue.refusal("invalid", file, null, "/mod", "mod must be an object with an id"));
        Map<?, ?> m = (Map<?, ?>) mod;
        top.allowed(m, "/mod", Set.of("id", "name", "version"));
        Object mid = m.get("id");
        if (!(mid instanceof String) || !MOD_ID.matcher((String) mid).matches()) {
            return refused(Issue.refusal("invalid", file, null, "/mod/id", "mod.id must be the mod's id, Group:Name"));
        }
        String modId = (String) mid;
        if (shippingMod != null && !shippingMod.equalsIgnoreCase(modId)) {
            return refused(Issue.refusal("mod_mismatch", file, null, "/mod/id",
                    "mod.id is " + modId + ", but the file ships in " + shippingMod));
        }
        if (!top.errors.isEmpty()) return refused(top.errors.get(0));
        Object effs = d.get("effects");
        if (!(effs instanceof List)) return refused(Issue.refusal("invalid", file, null, "/effects", "effects must be a list"));
        List<EffectDef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<?> list = (List<?>) effs;
        for (int k = 0; k < list.size(); k++) {
            String ptr = "/effects/" + k;
            Object e = list.get(k);
            Object rawId = e instanceof Map ? ((Map<?, ?>) e).get("id") : null;
            String id = rawId instanceof String ? (String) rawId : null;
            if (id != null && !seen.add(id)) {
                issues.add(Issue.refusal("duplicate_id", file, id, ptr + "/id", "the id " + id + " is used twice in this file"));
                continue;
            }
            EffectDef def = readEffect(file, e, ptr, modId, game, newer, issues);
            if (def != null) out.add(def);
        }
        return new Result(modId, out, issues);
    }

    private static Result refused(Issue i) {
        return new Result(null, List.of(), List.of(i));
    }

    static int[] version(String s) {
        String[] p = s.split("\\.");
        if (p.length != 2) return null;
        try {
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (NumberFormatException x) {
            return null;
        }
    }

    /**
     * Validates one effect object. Returns null (with the refusal in {@code issues}) when it's refused.
     * Used for sidecars, for override replacements and by the editor's draft.
     */
    public static EffectDef readEffect(String file, Object raw, String ptr, String modId, GameProfile game,
                                       boolean newerMinor, List<Issue> issues) {
        String id = raw instanceof Map && ((Map<?, ?>) raw).get("id") instanceof String ? (String) ((Map<?, ?>) raw).get("id") : null;
        Ctx c = new Ctx(file, id, newerMinor, issues);
        if (!(raw instanceof Map)) {
            c.invalid(ptr, "an effect must be an object");
            return c.finish();
        }
        Map<?, ?> e = (Map<?, ?>) raw;
        c.allowed(e, ptr, Set.of("id", "name", "description", "harmful", "category", "tags", "duration", "reapply",
                "stacking", "parts", "bindings", "ends_when", "immune_if", "cure_tags", "on_death", "caller_may_set",
                "power", "permission"));
        if (id == null || !ID.matcher(id).matches()) c.invalid(ptr + "/id", "id is required: lower-case letters, digits, '_', '.', '-'");
        String name = c.str(e, "name", ptr, true);
        String description = c.str(e, "description", ptr, false);
        boolean harmful = c.bool(e, "harmful", ptr, false);
        String category = c.str(e, "category", ptr, false);
        List<String> tags = c.strings(e, "tags", ptr);
        DurationSpec duration = c.duration(e.get("duration"), ptr + "/duration", game);
        Reapply reapply = Reapply.REFRESH;
        Object ra = e.get("reapply");
        if (ra != null) {
            reapply = c.enumOf(ra, ptr + "/reapply", Reapply.class);
            if (reapply == null) reapply = Reapply.REFRESH;
        }
        String stackCat = null;
        int maxStacks = 1;
        List<String> stackNames = List.of();
        Object st = e.get("stacking");
        if (st != null) {
            if (!(st instanceof Map)) {
                c.invalid(ptr + "/stacking", "stacking must be an object");
            } else {
                Map<?, ?> sm = (Map<?, ?>) st;
                c.allowed(sm, ptr + "/stacking", Set.of("category", "max_stacks", "names"));
                stackCat = c.str(sm, "category", ptr + "/stacking", false);
                if (stackCat != null && !ID.matcher(stackCat).matches()) c.invalid(ptr + "/stacking/category", "a category is lower-case letters, digits, '_', '.', '-'");
                if (sm.containsKey("max_stacks")) {
                    Integer ms = c.integer(sm.get("max_stacks"), ptr + "/stacking/max_stacks", 1, 1000);
                    if (ms != null) maxStacks = ms;
                    if (reapply != Reapply.STACK) c.invalid(ptr + "/stacking/max_stacks", "max_stacks only goes with reapply \"stack\"");
                }
                if (sm.containsKey("names")) {
                    stackNames = c.strings(sm, "names", ptr + "/stacking");
                    if (reapply != Reapply.STACK) c.invalid(ptr + "/stacking/names", "names only go with reapply \"stack\"");
                    else if (stackNames.size() != maxStacks) c.invalid(ptr + "/stacking/names", "one name per stack: " + maxStacks + " names, not " + stackNames.size());
                }
            }
        }
        if (reapply == Reapply.STACK && (st == null || !((Map<?, ?>) st).containsKey("max_stacks"))) {
            c.invalid(ptr + "/stacking/max_stacks", "reapply \"stack\" needs stacking.max_stacks");
        }
        List<Part> parts = new ArrayList<>();
        Object ps = e.get("parts");
        if (!(ps instanceof List)) {
            c.invalid(ptr + "/parts", "parts is required (it may be an empty list)");
        } else {
            readParts((List<?>) ps, ptr + "/parts", c, game, true, parts);
            Set<String> moments = new HashSet<>();
            for (Part p : parts) {
                if (!p.type().equals("message")) continue;
                if (!moments.add(p.str("when") + "|" + p.str("to"))) {
                    c.refuse("duplicate_message", ptr + "/parts", "two message parts for when \"" + p.str("when") + "\" and to \"" + p.str("to") + "\"");
                }
            }
        }
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Object bs = e.get("bindings");
        if (!(bs instanceof Map)) {
            c.invalid(ptr + "/bindings", "bindings is required: one entry per game ({} for none)");
        } else {
            for (Map.Entry<?, ?> be : ((Map<?, ?>) bs).entrySet()) {
                String g = String.valueOf(be.getKey());
                String bp = ptr + "/bindings/" + Json.pointerToken(g);
                if (!ID.matcher(g).matches()) {
                    c.invalid(bp, "a binding's key is a game's profile name, such as \"hytale\"");
                    continue;
                }
                Binding b = c.binding(be.getValue(), bp, g, g.equals(game.name()) ? game : null);
                if (b != null) bindings.put(g, b);
            }
        }
        Object endsWhen = e.get("ends_when");
        if (endsWhen != null) c.condition(endsWhen, ptr + "/ends_when");
        List<String> immuneIf = new ArrayList<>();
        for (String r : c.strings(e, "immune_if", ptr)) {
            String q = c.effectRef(r, ptr + "/immune_if", modId, game);
            if (q != null) immuneIf.add(q);
        }
        List<String> cureTags = c.strings(e, "cure_tags", ptr);
        OnDeath onDeath = OnDeath.CLEAR;
        if (e.get("on_death") != null) {
            OnDeath od = c.enumOf(e.get("on_death"), ptr + "/on_death", OnDeath.class);
            if (od != null) onDeath = od;
        }
        Bounds db = null, sb = new Bounds(1, 1);
        if (duration != null && duration.hasRunTime()) db = new Bounds(duration.runMs() / 1000.0, duration.runMs() / 1000.0);
        Object cms = e.get("caller_may_set");
        if (cms != null) {
            if (!(cms instanceof Map)) {
                c.invalid(ptr + "/caller_may_set", "caller_may_set must be an object");
            } else {
                Map<?, ?> cm = (Map<?, ?>) cms;
                c.allowed(cm, ptr + "/caller_may_set", Set.of("duration", "strength"));
                if (cm.containsKey("duration")) {
                    Bounds b = c.bounds(cm.get("duration"), ptr + "/caller_may_set/duration");
                    if (b != null && duration != null && duration.isInstant()) {
                        c.invalid(ptr + "/caller_may_set/duration", "an instant effect (seconds 0) has no duration to set");
                    } else if (b != null && duration != null && !duration.hasRunTime()) {
                        c.invalid(ptr + "/caller_may_set/duration", "a caller can set the duration only of an effect with run time (seconds)");
                    } else if (b != null) {
                        db = b;
                    }
                }
                if (cm.containsKey("strength")) {
                    Bounds b = c.bounds(cm.get("strength"), ptr + "/caller_may_set/strength");
                    if (b != null) sb = b;
                }
            }
        }
        Double power = null;
        if (e.get("power") != null) {
            BigDecimal p = c.number(e.get("power"), ptr + "/power");
            if (p != null) power = p.doubleValue();
        }
        String permission = c.str(e, "permission", ptr, false);
        if (permission == null && id != null) permission = defaultPermission(modId, id);
        // intangible always comes with "can't attack" (effects.md section 6): the loader adds it.
        boolean intangible = false;
        for (Part p : parts) if (p.type().equals("intangible")) intangible = true;
        if (intangible) parts.add(Part.of("disable_actions", "actions", new ArrayList<>(Vocabulary.INTANGIBLE_ACTIONS), "added_by", "intangible"));
        if (!c.errors.isEmpty()) return c.finish();
        if (!bindings.containsKey(game.name()) && !game.name().equals("none")) {
            issues.add(Issue.log("no_binding", file, id, ptr + "/bindings",
                    "no binding for " + game.name() + ": the effect is dormant here"));
        }
        return new EffectDef(modId, id, name, description, harmful, category, tags, duration, reapply, stackCat,
                maxStacks, stackNames, parts, bindings, endsWhen, immuneIf, cureTags, onDeath, db, sb, power, permission);
    }

    /** {@code buffeffects.apply.<mod id>.<id>}, with the mod id's ':' as '.'. */
    public static String defaultPermission(String modId, String id) {
        return "buffeffects.apply." + modId.toLowerCase().replace(':', '.') + "." + id;
    }

    static void readParts(List<?> list, String ptr, Ctx c, GameProfile game, boolean core, List<Part> out) {
        for (int k = 0; k < list.size(); k++) {
            Part p = c.part(list.get(k), ptr + "/" + k, game, core);
            if (p != null) out.add(p);
        }
    }

    // ------------------------------------------------------------------------------------- the checks

    /** Collects one effect's findings. */
    static final class Ctx {
        final String file;
        final String effectId;
        final boolean newerMinor;
        final List<Issue> issues;
        final List<Issue> errors = new ArrayList<>();

        Ctx(String file, String effectId, boolean newerMinor, List<Issue> issues) {
            this.file = file;
            this.effectId = effectId;
            this.newerMinor = newerMinor;
            this.issues = issues;
        }

        EffectDef finish() {
            issues.addAll(errors.isEmpty() ? List.of() : List.of(errors.get(0)));
            return null;
        }

        void invalid(String ptr, String msg) {
            refuse("invalid", ptr, msg);
        }

        void refuse(String code, String ptr, String msg) {
            errors.add(Issue.refusal(code, file, effectId, ptr, msg));
        }

        void log(String code, String ptr, String msg) {
            issues.add(Issue.log(code, file, effectId, ptr, msg));
        }

        void allowed(Map<?, ?> m, String ptr, Set<String> keys) {
            for (Object k : m.keySet()) {
                if (keys.contains(String.valueOf(k))) continue;
                String kp = ptr + "/" + Json.pointerToken(String.valueOf(k));
                if (newerMinor) issues.add(Issue.warning("unknown_field", file, effectId, kp, "a field from a newer format version, ignored"));
                else invalid(kp, "unknown field \"" + k + "\"");
            }
        }

        String str(Map<?, ?> m, String key, String ptr, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) invalid(ptr + "/" + key, key + " is required");
                return null;
            }
            if (!(v instanceof String) || ((String) v).isBlank()) {
                invalid(ptr + "/" + key, key + " must be text");
                return null;
            }
            if (((String) v).length() > 500) invalid(ptr + "/" + key, key + " is longer than 500 characters");
            return (String) v;
        }

        boolean bool(Map<?, ?> m, String key, String ptr, boolean fallback) {
            Object v = m.get(key);
            if (v == null) return fallback;
            if (!(v instanceof Boolean)) {
                invalid(ptr + "/" + key, key + " must be true or false");
                return fallback;
            }
            return (Boolean) v;
        }

        List<String> strings(Map<?, ?> m, String key, String ptr) {
            Object v = m.get(key);
            List<String> out = new ArrayList<>();
            if (v == null) return out;
            if (!(v instanceof List)) {
                invalid(ptr + "/" + key, key + " must be a list of text");
                return out;
            }
            List<?> l = (List<?>) v;
            for (int k = 0; k < l.size(); k++) {
                if (l.get(k) instanceof String && !((String) l.get(k)).isBlank()) out.add((String) l.get(k));
                else invalid(ptr + "/" + key + "/" + k, "must be text");
            }
            return out;
        }

        BigDecimal number(Object v, String ptr) {
            BigDecimal b;
            if (v instanceof BigDecimal) {
                b = (BigDecimal) v;
            } else if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    invalid(ptr, "must be a number");
                    return null;
                }
                b = BigDecimal.valueOf(d);
            } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                b = BigDecimal.valueOf(((Number) v).longValue());
            } else {
                invalid(ptr, "must be a number");
                return null;
            }
            if (b.stripTrailingZeros().scale() > 3) {
                invalid(ptr, "at most 3 decimals");
                return null;
            }
            if (b.abs().compareTo(LIMIT) > 0) {
                invalid(ptr, "must be within ±10^11");
                return null;
            }
            return b;
        }

        Double positive(Object v, String ptr) {
            BigDecimal b = number(v, ptr);
            if (b == null) return null;
            if (b.signum() <= 0) {
                invalid(ptr, "must be more than 0");
                return null;
            }
            return b.doubleValue();
        }

        Integer integer(Object v, String ptr, int min, int max) {
            BigDecimal b = number(v, ptr);
            if (b == null) return null;
            if (b.stripTrailingZeros().scale() > 0 || b.intValue() < min || b.intValue() > max) {
                invalid(ptr, "must be a whole number from " + min + " to " + max);
                return null;
            }
            return b.intValue();
        }

        <E extends Enum<E>> E enumOf(Object v, String ptr, Class<E> type) {
            if (v instanceof String) {
                for (E c : type.getEnumConstants()) if (c.name().equalsIgnoreCase((String) v) && ((String) v).equals(((String) v).toLowerCase())) return c;
            }
            StringBuilder opts = new StringBuilder();
            for (E c : type.getEnumConstants()) opts.append(opts.length() == 0 ? "" : ", ").append(c.name().toLowerCase());
            invalid(ptr, "must be one of " + opts);
            return null;
        }

        String oneOf(Map<?, ?> m, String key, String ptr, List<String> options, boolean required) {
            Object v = m.get(key);
            if (v == null) {
                if (required) invalid(ptr + "/" + key, key + " is required: " + String.join(", ", options));
                return null;
            }
            if (!(v instanceof String) || !options.contains(v)) {
                invalid(ptr + "/" + key, key + " must be one of " + String.join(", ", options));
                return null;
            }
            return (String) v;
        }

        Bounds bounds(Object v, String ptr) {
            if (!(v instanceof Map)) {
                invalid(ptr, "must be { \"min\": ..., \"max\": ... }");
                return null;
            }
            Map<?, ?> m = (Map<?, ?>) v;
            allowed(m, ptr, Set.of("min", "max"));
            Double min = positive(m.get("min"), ptr + "/min");
            Double max = positive(m.get("max"), ptr + "/max");
            if (min == null || max == null) return null;
            if (min > max) {
                invalid(ptr, "min is above max");
                return null;
            }
            return new Bounds(min, max);
        }

        DurationSpec duration(Object v, String ptr, GameProfile game) {
            if (!(v instanceof Map)) {
                invalid(ptr, "duration is required: { \"seconds\": 30 }, { \"infinite\": true } or { \"world\": \"dawn\" }");
                return null;
            }
            Map<?, ?> m = (Map<?, ?>) v;
            allowed(m, ptr, Set.of("seconds", "infinite", "world", "world_days"));
            long ms = -1;
            if (m.containsKey("seconds")) {
                BigDecimal s = number(m.get("seconds"), ptr + "/seconds");
                if (s != null && s.signum() < 0) invalid(ptr + "/seconds", "seconds can't be negative");
                else if (s != null) ms = Math.round(s.doubleValue() * 1000);
            }
            boolean inf = false;
            if (m.containsKey("infinite")) {
                if (!Boolean.TRUE.equals(m.get("infinite"))) invalid(ptr + "/infinite", "infinite is only ever true");
                inf = true;
            }
            String world = null;
            if (m.containsKey("world")) world = oneOf(m, "world", ptr, List.of("dawn", "dusk", "end_of_day"), true);
            int days = 0;
            if (m.containsKey("world_days")) {
                Integer d = integer(m.get("world_days"), ptr + "/world_days", 1, 3650);
                if (d != null) days = d;
            }
            if (inf && m.size() > 1) invalid(ptr, "infinite goes alone");
            if (ms == 0 && m.size() > 1) invalid(ptr, "an instant effect (seconds 0) goes alone");
            if (world != null && days > 0) invalid(ptr, "world and world_days don't go together");
            if (!inf && ms < 0 && world == null && days == 0 && errors.isEmpty()) invalid(ptr, "the duration says nothing: give seconds, infinite or a world point");
            DurationSpec d = new DurationSpec(ms, inf, world, days);
            if (d.usesWorldClock() && !game.supportsWorldClock()) {
                refuse("unsupported_part", ptr, game.name() + " has no in-game clock for BuffEffects");
            }
            return d;
        }

        void condition(Object v, String ptr) {
            if (!(v instanceof Map) || !(((Map<?, ?>) v).get("type") instanceof String)) {
                invalid(ptr, "a condition is an object with a \"type\", in CommandDeck's format (conditions.md)");
            }
        }

        /** Qualifies an effect reference: own short id, {@code <mod>/<id>}, or {@code <game>:<id>}. */
        String effectRef(String r, String ptr, String modId, GameProfile game) {
            if (r.indexOf('/') >= 0) {
                String m = r.substring(0, r.indexOf('/'));
                String id = r.substring(r.indexOf('/') + 1);
                if (!MOD_ID.matcher(m).matches() || !ID.matcher(id).matches()) {
                    invalid(ptr, "\"" + r + "\" isn't an effect reference (<mod id>/<id>)");
                    return null;
                }
                return r;
            }
            int colon = r.indexOf(':');
            if (colon > 0) {
                String g = r.substring(0, colon);
                String gid = r.substring(colon + 1);
                if (g.equals(game.name()) && Boolean.FALSE.equals(game.knows(GameProfile.Kind.EFFECT, gid))) {
                    refuse("unknown_game_id", ptr, game.name() + " has no effect " + gid);
                    return null;
                }
                return r;
            }
            if (!ID.matcher(r).matches()) {
                invalid(ptr, "\"" + r + "\" isn't an effect id");
                return null;
            }
            return modId + "/" + r;
        }

        /**
         * A stat or cause: a neutral name, or {@code <game>:<id>}. Returns false when the part should be
         * dropped on this game (another game's id), true otherwise; refusals go to the errors.
         */
        boolean gameName(String v, String ptr, GameProfile.Kind kind, GameProfile game, boolean neutralOk) {
            int colon = v.indexOf(':');
            if (colon > 0) {
                String g = v.substring(0, colon);
                if (!g.equals(game.name())) return game.name().equals("none");
                if (Boolean.FALSE.equals(game.knows(kind, v.substring(colon + 1)))) {
                    refuse("unknown_game_id", ptr, game.name() + " has no " + kind.name().toLowerCase() + " " + v.substring(colon + 1));
                }
                return true;
            }
            if (!neutralOk) invalid(ptr, "\"" + v + "\" isn't a known " + kind.name().toLowerCase() + " name; a game's own is written <game>:<id>");
            return true;
        }

        Part part(Object raw, String ptr, GameProfile game, boolean core) {
            if (!(raw instanceof Map) || !(((Map<?, ?>) raw).get("type") instanceof String)) {
                invalid(ptr, "a part is an object with a \"type\"");
                return null;
            }
            Map<?, ?> m = (Map<?, ?>) raw;
            String type = (String) m.get("type");
            boolean required = bool(m, "required", ptr, true);
            Map<String, Object> f = new LinkedHashMap<>();
            int before = errors.size();
            boolean keep = true;
            switch (type) {
                case "stat": {
                    allowed(m, ptr, Set.of("type", "required", "stat", "op", "amount"));
                    keep = stat(m, ptr, game, f);
                    String op = oneOf(m, "op", ptr, List.of("add", "multiply"), true);
                    f.put("op", op);
                    BigDecimal a = number(m.get("amount"), ptr + "/amount");
                    f.put("amount", a);
                    if (a != null && "multiply".equals(op) && a.signum() <= 0) invalid(ptr + "/amount", "a multiplier must be more than 0");
                    break;
                }
                case "instant": {
                    allowed(m, ptr, Set.of("type", "required", "stat", "amount", "unit"));
                    keep = stat(m, ptr, game, f);
                    f.put("amount", number(m.get("amount"), ptr + "/amount"));
                    f.put("unit", oneOf(m, "unit", ptr, List.of("absolute", "percent"), true));
                    break;
                }
                case "regen": {
                    allowed(m, ptr, Set.of("type", "required", "stat", "amount", "unit", "interval", "breaks_on_hit"));
                    keep = stat(m, ptr, game, f);
                    f.put("amount", number(m.get("amount"), ptr + "/amount"));
                    f.put("unit", oneOf(m, "unit", ptr, List.of("absolute", "percent"), true));
                    f.put("interval", positive(m.get("interval"), ptr + "/interval"));
                    f.put("breaks_on_hit", bool(m, "breaks_on_hit", ptr, true));
                    break;
                }
                case "damage_over_time": {
                    allowed(m, ptr, Set.of("type", "required", "cause", "amount", "interval"));
                    keep = cause(m, ptr, game, f);
                    f.put("amount", positive(m.get("amount"), ptr + "/amount"));
                    f.put("interval", positive(m.get("interval"), ptr + "/interval"));
                    break;
                }
                case "resist": {
                    allowed(m, ptr, Set.of("type", "required", "cause", "amount"));
                    keep = cause(m, ptr, game, f);
                    BigDecimal a = number(m.get("amount"), ptr + "/amount");
                    f.put("amount", a);
                    if (a != null && a.compareTo(BigDecimal.ONE) > 0 && !game.supportsAbsorb()) {
                        refuse("absorb_not_supported", ptr + "/amount", "resistance above 1.0 (absorb) isn't supported on " + game.name());
                    }
                    if (a != null && a.compareTo(BigDecimal.valueOf(-10)) < 0) invalid(ptr + "/amount", "resistance below -10 (1000% more damage)");
                    break;
                }
                case "outgoing": {
                    allowed(m, ptr, Set.of("type", "required", "cause", "multiplier"));
                    keep = cause(m, ptr, game, f);
                    BigDecimal a = number(m.get("multiplier"), ptr + "/multiplier");
                    if (a != null && a.signum() < 0) invalid(ptr + "/multiplier", "a multiplier can't be negative");
                    f.put("multiplier", a);
                    break;
                }
                case "speed":
                case "knockback": {
                    allowed(m, ptr, Set.of("type", "required", "multiplier"));
                    BigDecimal a = number(m.get("multiplier"), ptr + "/multiplier");
                    if (a != null && a.signum() < 0) invalid(ptr + "/multiplier", "a multiplier can't be negative");
                    f.put("multiplier", a);
                    break;
                }
                case "lock_movement": {
                    allowed(m, ptr, Set.of("type", "required", "all", "jump", "sprint", "crouch"));
                    boolean any = false;
                    for (String mv : Vocabulary.MOVEMENTS) {
                        boolean b = bool(m, mv, ptr, false);
                        if (b) {
                            f.put(mv, true);
                            any = true;
                        }
                    }
                    if (!any) invalid(ptr, "lock_movement locks nothing: set all, jump, sprint or crouch to true");
                    break;
                }
                case "disable_actions": {
                    allowed(m, ptr, Set.of("type", "required", "actions"));
                    List<String> acts = strings(m, "actions", ptr);
                    if (acts.isEmpty()) invalid(ptr + "/actions", "actions is required: " + String.join(", ", Vocabulary.ACTIONS));
                    for (String a : acts) if (!Vocabulary.ACTIONS.contains(a)) invalid(ptr + "/actions", "unknown action \"" + a + "\"");
                    f.put("actions", acts);
                    break;
                }
                case "invulnerable":
                case "intangible":
                    allowed(m, ptr, Set.of("type", "required"));
                    break;
                case "light": {
                    allowed(m, ptr, Set.of("type", "required", "color", "radius"));
                    Object c = m.get("color");
                    if (!(c instanceof String cs) || !cs.matches("#[0-9A-Fa-f]{6}")) invalid(ptr + "/color", "color is required, as #RRGGBB");
                    else f.put("color", cs.toUpperCase(java.util.Locale.ROOT));
                    BigDecimal r = number(m.get("radius"), ptr + "/radius");
                    if (r != null && (r.compareTo(BigDecimal.ONE) < 0 || r.compareTo(BigDecimal.valueOf(15)) > 0)) invalid(ptr + "/radius", "radius is in blocks, from 1 to 15");
                    f.put("radius", r);
                    break;
                }
                case "behavior":
                    refuse("not_yet", ptr, "the " + type + " part isn't in this version of BuffEffects (effects.md section 6)");
                    return null;
                case "hit": {
                    allowed(m, ptr, Set.of("type", "required", "cause", "amount"));
                    keep = cause(m, ptr, game, f);
                    f.put("amount", positive(m.get("amount"), ptr + "/amount"));
                    break;
                }
                case "message":
                    if (!message(m, ptr, f)) return null;
                    break;
                case "lines":
                    invalid(ptr + "/type", "the lines part was replaced by message in format 0.3: { \"type\": \"message\", \"when\": \"every\", \"every\": 10, \"pick\": [...] }");
                    return null;
                default:
                    invalid(ptr + "/type", "unknown part type \"" + type + "\"");
                    return null;
            }
            if (errors.size() > before) return null;
            if (!keep) {
                log("dropped_part", ptr, "names another game's id: dropped on " + game.name());
                return null;
            }
            Part p = new Part(type, required, f);
            if (!game.supports(p)) {
                if (required) {
                    refuse("unsupported_part", ptr, game.name() + " can't do this " + type + " part");
                    return null;
                }
                log("dropped_part", ptr, game.name() + " can't do this " + type + " part; it's not required, so it's dropped here");
                return null;
            }
            return p;
        }

        static final List<String> WHEN = List.of("apply", "refresh", "expire", "cure", "remove", "death", "every");
        static final List<String> TO = List.of("self", "actor", "nearby");
        static final List<String> SENSES = List.of("taste", "smell", "sound", "sight", "touch", "feeling");

        /** The message part (effects.md section 6, "Messages"). False when it's refused. */
        private boolean message(Map<?, ?> m, String ptr, Map<String, Object> f) {
            int before = errors.size();
            allowed(m, ptr, Set.of("type", "required", "when", "every", "to", "radius", "text", "pick", "channel", "sense", "variants", "once"));
            String when = oneOf(m, "when", ptr, WHEN, true);
            f.put("when", when);
            if ("every".equals(when)) f.put("every", positive(m.get("every"), ptr + "/every"));
            else if (m.containsKey("every")) invalid(ptr + "/every", "every only goes with when \"every\"");
            String to = m.containsKey("to") ? oneOf(m, "to", ptr, TO, true) : "self";
            f.put("to", to);
            if (m.containsKey("radius")) {
                if (!"nearby".equals(to)) invalid(ptr + "/radius", "radius only goes with to \"nearby\"");
                f.put("radius", positive(m.get("radius"), ptr + "/radius"));
            }
            text(m, ptr, f);
            if (m.containsKey("channel")) {
                String ch = oneOf(m, "channel", ptr, List.of("chat", "notice"), true);
                if ("notice".equals(ch)) refuse("not_yet", ptr + "/channel", "the notice channel is reserved (effects.md section 6)");
                f.put("channel", ch);
            }
            if (m.containsKey("sense")) f.put("sense", oneOf(m, "sense", ptr, SENSES, true));
            f.put("once", bool(m, "once", ptr, false));
            Object vs = m.get("variants");
            if (vs != null) {
                if (!(vs instanceof List)) {
                    invalid(ptr + "/variants", "variants must be a list of { \"if\": <condition>, \"text\" or \"pick\" }");
                } else {
                    List<Object> out = new ArrayList<>();
                    List<?> l = (List<?>) vs;
                    for (int k = 0; k < l.size(); k++) {
                        String vp = ptr + "/variants/" + k;
                        if (!(l.get(k) instanceof Map)) {
                            invalid(vp, "a variant is an object");
                            continue;
                        }
                        Map<?, ?> vm = (Map<?, ?>) l.get(k);
                        allowed(vm, vp, Set.of("if", "text", "pick"));
                        if (vm.get("if") == null) invalid(vp + "/if", "a variant needs an if condition");
                        else condition(vm.get("if"), vp + "/if");
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("if", vm.get("if"));
                        text(vm, vp, v);
                        out.add(v);
                    }
                    f.put("variants", out);
                }
            }
            return errors.size() == before;
        }

        /** Exactly one of text (one line) or pick (a list), into {@code f}. */
        private void text(Map<?, ?> m, String ptr, Map<String, Object> f) {
            boolean t = m.containsKey("text"), p = m.containsKey("pick");
            if (t && p) invalid(ptr, "text and pick don't go together");
            else if (!t && !p) invalid(ptr, "a message needs text or pick");
            if (t) f.put("text", str(m, "text", ptr, true));
            if (p) {
                List<String> ls = strings(m, "pick", ptr);
                if (ls.isEmpty()) invalid(ptr + "/pick", "pick needs at least one line");
                f.put("pick", ls);
            }
        }

        private boolean stat(Map<?, ?> m, String ptr, GameProfile game, Map<String, Object> f) {
            String s = str(m, "stat", ptr, true);
            f.put("stat", s);
            if (s == null) return true;
            return gameName(s, ptr + "/stat", GameProfile.Kind.STAT, game, Vocabulary.STATS.contains(s));
        }

        private boolean cause(Map<?, ?> m, String ptr, GameProfile game, Map<String, Object> f) {
            String s = str(m, "cause", ptr, true);
            f.put("cause", s);
            if (s == null) return true;
            boolean neutral = Vocabulary.isNeutralCause(s, game.extraCauses());
            if (!neutral && s.indexOf(':') < 0 && ID.matcher(s).matches()) {
                refuse("unknown_game_id", ptr + "/cause", "no damage cause \"" + s + "\" on " + game.name());
                return true;
            }
            return gameName(s, ptr + "/cause", GameProfile.Kind.CAUSE, game, neutral);
        }

        /** {@code game} is the running game's profile when the binding is for it, else null (structure only). */
        Binding binding(Object raw, String ptr, String g, GameProfile game) {
            if (!(raw instanceof Map)) {
                invalid(ptr, "a binding is an object ({} for none)");
                return null;
            }
            Map<?, ?> m = (Map<?, ?>) raw;
            allowed(m, ptr, Set.of("icon", "look", "shape", "native", "parts", "ends_when"));
            String icon = str(m, "icon", ptr, false);
            known(game, GameProfile.Kind.ICON, icon, ptr + "/icon");
            Map<String, Object> wa = new LinkedHashMap<>(), oa = new LinkedHashMap<>(), or = new LinkedHashMap<>();
            Object look = m.get("look");
            if (look != null) {
                if (!(look instanceof Map)) {
                    invalid(ptr + "/look", "look must be an object");
                } else {
                    Map<?, ?> lm = (Map<?, ?>) look;
                    allowed(lm, ptr + "/look", Set.of("while_active", "on_apply", "on_remove"));
                    lookPart(lm.get("while_active"), ptr + "/look/while_active", true, game, wa);
                    lookPart(lm.get("on_apply"), ptr + "/look/on_apply", false, game, oa);
                    lookPart(lm.get("on_remove"), ptr + "/look/on_remove", false, game, or);
                }
            }
            String shape = str(m, "shape", ptr, false);
            known(game, GameProfile.Kind.MODEL, shape, ptr + "/shape");
            List<String> natives = strings(m, "native", ptr);
            for (String n : natives) known(game, GameProfile.Kind.EFFECT, n, ptr + "/native");
            List<Part> parts = new ArrayList<>();
            Object ps = m.get("parts");
            if (ps != null) {
                if (!(ps instanceof List)) invalid(ptr + "/parts", "parts must be a list");
                else if (game != null) readParts((List<?>) ps, ptr + "/parts", this, game, false, parts);
                else readParts((List<?>) ps, ptr + "/parts", this, GameProfile.none(), false, new ArrayList<>());
            }
            Object ew = m.get("ends_when");
            if (ew != null) condition(ew, ptr + "/ends_when");
            return new Binding(g, icon, wa, oa, or, shape, natives, parts, ew);
        }

        private void lookPart(Object raw, String ptr, boolean whileActive, GameProfile game, Map<String, Object> out) {
            if (raw == null) return;
            if (!(raw instanceof Map)) {
                invalid(ptr, "must be an object");
                return;
            }
            Map<?, ?> m = (Map<?, ?>) raw;
            if (whileActive) {
                allowed(m, ptr, Set.of("tint_top", "tint_bottom", "particles", "model_vfx", "screen_effect", "animation", "see_through"));
                for (String t : List.of("tint_top", "tint_bottom")) {
                    String col = str(m, t, ptr, false);
                    if (col != null && !COLOR.matcher(col).matches()) invalid(ptr + "/" + t, "a colour is #RRGGBB");
                    if (col != null) out.put(t, col);
                }
                put(out, "model_vfx", str(m, "model_vfx", ptr, false));
                known(game, GameProfile.Kind.MODEL_VFX, (String) out.get("model_vfx"), ptr + "/model_vfx");
                put(out, "screen_effect", str(m, "screen_effect", ptr, false));
                known(game, GameProfile.Kind.SCREEN_EFFECT, (String) out.get("screen_effect"), ptr + "/screen_effect");
                if (m.containsKey("see_through")) {
                    BigDecimal st = number(m.get("see_through"), ptr + "/see_through");
                    if (st != null && (st.signum() <= 0 || st.compareTo(BigDecimal.ONE) >= 0)) {
                        invalid(ptr + "/see_through", "see_through is a share between 0 and 1: 0.6 is 60% see-through");
                    } else if (st != null) {
                        out.put("see_through", st);
                    }
                    if (m.containsKey("model_vfx")) invalid(ptr + "/see_through", "see_through is a model VFX of its own: it doesn't go with model_vfx");
                }
            } else {
                allowed(m, ptr, Set.of("animation", "sound", "particles"));
                put(out, "sound", str(m, "sound", ptr, false));
                known(game, GameProfile.Kind.SOUND, (String) out.get("sound"), ptr + "/sound");
            }
            put(out, "animation", str(m, "animation", ptr, false));
            known(game, GameProfile.Kind.ANIMATION, (String) out.get("animation"), ptr + "/animation");
            List<String> ps = strings(m, "particles", ptr);
            for (String p : ps) known(game, GameProfile.Kind.PARTICLE, p, ptr + "/particles");
            if (!ps.isEmpty()) out.put("particles", ps);
        }

        private static void put(Map<String, Object> m, String k, Object v) {
            if (v != null) m.put(k, v);
        }

        private void known(GameProfile game, GameProfile.Kind kind, String id, String ptr) {
            if (game == null || id == null) return;
            if (Boolean.FALSE.equals(game.knows(kind, id))) {
                refuse("unknown_game_id", ptr, game.name() + " has no " + kind.name().toLowerCase().replace('_', ' ') + " " + id);
            }
        }
    }
}
