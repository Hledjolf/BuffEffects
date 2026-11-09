package buffeffects.core.format;

import buffeffects.core.json.Json;
import buffeffects.core.json.JsonException;
import buffeffects.core.rating.Power;
import buffeffects.core.rating.PowerRater;
import buffeffects.core.rating.PowerRater.Rating;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The load pipeline (effects.md section 1): qualify, apply server overrides, validate, power check, hand
 * over what passed. CommandDeck's shared loader will run the finding and the override steps for every
 * engine's format (UME-D085); until it ships, the adapter's local discovery feeds this class the same
 * inputs, so the swap touches nothing else.
 */
public final class EffectLoader {
    private EffectLoader() {
    }

    /** One sidecar as found: the file (for messages), the mod that ships it, its text. */
    public record Sidecar(String file, String shippingMod, String modVersion, String text) {
    }

    /** One override file, for the mod {@code targetMod}. */
    public record OverrideFile(String file, String targetMod, String text) {
    }

    /** Where an effect stands on this server. */
    public enum Status { ACTIVE, DORMANT, EXCESSIVE, DISABLED }

    /** One loaded effect, its standing and its load-time rating (null when unrated). */
    public record Loaded(EffectDef def, Status status, String why, Rating rating, boolean overridden, boolean optedIn) {
        public boolean usable() {
            return status == Status.ACTIVE;
        }
    }

    /**
     * The power check's inputs. {@code enforced} is false in 1.0: ratings are shown, and nothing is switched
     * off for being above the curve (effects.md section 11). The 1.1 power check sets it.
     */
    public record PowerCheck(double maxStepsPerEffect, Set<String> optedInEffects, Set<String> optedInMods, boolean enforced) {
        public PowerCheck(double maxStepsPerEffect, Set<String> optedInEffects, Set<String> optedInMods) {
            this(maxStepsPerEffect, optedInEffects, optedInMods, false);
        }

        public boolean optedIn(EffectDef d) {
            return optedInEffects.contains(d.fullId()) || optedInMods.contains(d.mod());
        }
    }

    /** Everything that loaded, by full id, and every finding. */
    public record LoadedSet(Map<String, Loaded> effects, List<Issue> issues) {
    }

    public static LoadedSet load(Collection<Sidecar> sidecars, Collection<OverrideFile> overrides, Set<String> installedMods,
                            GameProfile game, PowerRater rater, PowerCheck check) {
        List<Issue> issues = new ArrayList<>();
        Map<String, EffectDef> defs = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (Sidecar s : sidecars) {
            EffectsFormat.Result r = EffectsFormat.read(s.file(), s.text(), s.shippingMod(), game);
            issues.addAll(r.issues());
            if (r.fileRefused()) continue;
            versions.put(r.modId(), s.modVersion());
            for (EffectDef d : r.effects()) {
                if (defs.containsKey(d.fullId())) {
                    issues.add(Issue.refusal("duplicate_id", s.file(), d.id(), null, d.fullId() + " is defined by two sidecars"));
                    continue;
                }
                defs.put(d.fullId(), d);
            }
        }
        // Server overrides: replace or disable, by full id; never a new id under another mod's name.
        Map<String, Boolean> overridden = new LinkedHashMap<>();
        for (OverrideFile o : overrides) applyOverride(o, defs, versions, game, issues, overridden);
        // Cross-mod references: into a mod that isn't installed, dormant; an unknown id in an installed mod, refused.
        Map<String, Loaded> out = new LinkedHashMap<>();
        for (EffectDef d : defs.values()) {
            String dormantWhy = null;
            boolean refused = false;
            for (String ref : d.immuneIf()) {
                int slash = ref.indexOf('/');
                if (slash < 0) continue;
                String mod = ref.substring(0, slash);
                if (!installedMods.contains(mod) && !defs.containsKey(ref)) {
                    issues.add(Issue.log("dormant_ref", null, d.fullId(), "/immune_if", "names " + ref + ", whose mod isn't installed: that reference is ignored"));
                } else if (!defs.containsKey(ref)) {
                    issues.add(Issue.refusal("unknown_ref", null, d.fullId(), "/immune_if", "names " + ref + ", which " + mod + " doesn't define"));
                    refused = true;
                }
            }
            if (refused) continue;
            Binding b = d.binding(game.name());
            if (b == null) dormantWhy = "no binding for " + game.name();
            List<Part> natives = new ArrayList<>();
            if (b != null) for (String n : b.natives()) natives.addAll(game.nativeParts(n));
            Rating rating = Power.rate(d, game.name(), natives, d.strengthBounds().max(), Power.ratedSeconds(d), rater);
            boolean optedIn = check.optedIn(d);
            Status st = Status.ACTIVE;
            String why = null;
            if (dormantWhy != null) {
                st = Status.DORMANT;
                why = dormantWhy;
            } else if (!rating.isUnrated() && !rating.isCosmetic()
                    && (rating.cliff() || rating.steps() > check.maxStepsPerEffect()) && !optedIn) {
                String above = rating.cliff() ? "rated as a cliff (" + rating.note() + ")" : "rated " + round(rating.steps()) + " steps, above " + round(check.maxStepsPerEffect());
                if (check.enforced()) {
                    st = Status.EXCESSIVE;
                    why = above;
                    issues.add(Issue.log("excessive", null, d.fullId(), null, why + ": off until an operator opts in (/bfx optin " + d.fullId() + ")"));
                } else {
                    // 1.0: ratings are shown, not enforced (effects.md section 11). The power check is 1.1's.
                    issues.add(Issue.log("above_curve", null, d.fullId(), null, above + ": shown only; BuffEffects 1.0 doesn't switch effects off"));
                }
            }
            if (rating.isUnrated()) {
                issues.add(Issue.log("unrated", null, d.fullId(), null, "unrated (" + rating.note() + ")"));
            } else {
                String shown = rating.isCosmetic() ? "cosmetic" : rating.cliff() ? "a cliff (" + rating.note() + ")" : round(rating.steps()) + " steps";
                issues.add(Issue.log("rated", null, d.fullId(), null, "rated " + shown + (rating.flags().isEmpty() ? "" : ", flags " + new java.util.TreeSet<>(rating.flags()))));
            }
            out.put(d.fullId(), new Loaded(d, st, why, rating, overridden.containsKey(d.fullId()), optedIn));
        }
        // Every effect's game asset name must be its own (effects.md section 7): two that map to one are both refused.
        Map<String, List<String>> byAsset = new LinkedHashMap<>();
        for (Loaded l : out.values()) {
            String a = game.assetName(l.def().fullId());
            if (a != null) byAsset.computeIfAbsent(a.toLowerCase(), k -> new ArrayList<>()).add(l.def().fullId());
        }
        for (Map.Entry<String, List<String>> e : byAsset.entrySet()) {
            if (e.getValue().size() < 2) continue;
            for (String id : e.getValue()) {
                out.remove(id);
                issues.add(Issue.refusal("asset_name_clash", null, id, null, "its " + game.name() + " asset name is the same as "
                        + String.join(" and ", e.getValue().stream().filter(x -> !x.equals(id)).toList()) + "'s"));
            }
        }
        for (Map.Entry<String, Boolean> e : overridden.entrySet()) {
            if (!e.getValue()) continue;
            Loaded l = out.get(e.getKey());
            if (l != null) out.put(e.getKey(), new Loaded(l.def(), Status.DISABLED, "disabled by a server override", l.rating(), true, l.optedIn()));
        }
        return new LoadedSet(out, issues);
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    /**
     * An override file: {@code { "format": "buffeffects.effects", "format_version": "0.2",
     * "mod": { "id": <the mod it changes> }, "overrides": [ { "id": ..., "based_on": "1.1.0",
     * "disabled": true } or { "based_on": "1.1.0", "effect": { a complete effect } } ] }}.
     */
    static void applyOverride(OverrideFile o, Map<String, EffectDef> defs, Map<String, String> versions, GameProfile game,
                              List<Issue> issues, Map<String, Boolean> overridden) {
        Object doc;
        try {
            doc = Json.parse(o.text());
        } catch (JsonException e) {
            issues.add(Issue.refusal("invalid", o.file(), null, "", "not JSON: " + e.getMessage()));
            return;
        }
        if (!(doc instanceof Map) || !EffectsFormat.FORMAT_ID.equals(((Map<?, ?>) doc).get("format"))
                || !(((Map<?, ?>) doc).get("overrides") instanceof List)) {
            issues.add(Issue.refusal("invalid", o.file(), null, "", "an override file has format \"buffeffects.effects\" and an \"overrides\" list"));
            return;
        }
        List<?> list = (List<?>) ((Map<?, ?>) doc).get("overrides");
        for (int k = 0; k < list.size(); k++) {
            String ptr = "/overrides/" + k;
            if (!(list.get(k) instanceof Map)) {
                issues.add(Issue.refusal("invalid", o.file(), null, ptr, "an override is an object"));
                continue;
            }
            Map<?, ?> ov = (Map<?, ?>) list.get(k);
            Object effect = ov.get("effect");
            Object rawId = effect instanceof Map ? ((Map<?, ?>) effect).get("id") : ov.get("id");
            if (!(rawId instanceof String)) {
                issues.add(Issue.refusal("invalid", o.file(), null, ptr, "an override names the effect's id"));
                continue;
            }
            String full = o.targetMod() + "/" + rawId;
            if (!defs.containsKey(full)) {
                issues.add(Issue.refusal("unknown_ref", o.file(), full, ptr, "overrides can only replace or disable an existing effect; " + full + " isn't loaded"));
                continue;
            }
            Object based = ov.get("based_on");
            String now = versions.get(o.targetMod());
            if (based instanceof String && now != null && !based.equals(now)) {
                issues.add(Issue.warning("based_on_changed", o.file(), full, ptr + "/based_on",
                        "your override of " + rawId + " is based on " + based + "; " + o.targetMod() + " is now " + now + ". The override still wins"));
            }
            if (Boolean.TRUE.equals(ov.get("disabled"))) {
                overridden.put(full, true);
                issues.add(Issue.log("override", o.file(), full, ptr, "disabled by a server override"));
                continue;
            }
            if (!(effect instanceof Map)) {
                issues.add(Issue.refusal("invalid", o.file(), full, ptr, "an override either has \"disabled\": true or a complete \"effect\""));
                continue;
            }
            EffectDef d = EffectsFormat.readEffect(o.file(), effect, ptr + "/effect", o.targetMod(), game, false, issues);
            if (d == null) continue;
            defs.put(full, d);
            overridden.put(full, false);
            issues.add(Issue.log("override", o.file(), full, ptr, "replaced by a server override"));
        }
    }
}
