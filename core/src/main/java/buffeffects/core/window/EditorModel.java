package buffeffects.core.window;

import buffeffects.api.ActiveApplication;
import buffeffects.core.engine.Engine;
import buffeffects.core.engine.Settings;
import buffeffects.core.format.Binding;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.EffectLoader.Loaded;
import buffeffects.core.format.EffectLoader.Status;
import buffeffects.core.format.EffectWriter;
import buffeffects.core.format.EffectsFormat;
import buffeffects.core.format.GameProfile;
import buffeffects.core.format.Issue;
import buffeffects.core.format.Part;
import buffeffects.core.format.Vocabulary;
import buffeffects.core.json.Json;
import buffeffects.core.rating.Power;
import buffeffects.core.rating.PowerRater;
import buffeffects.core.rating.PowerRater.Rating;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The editor window's model (window.md section 4), game-neutral and tested headless: the effect list and
 * its filters, drafts and their validation, the live preview (rating, excessive, caps), where a save
 * goes, the save itself, delete and duplicate. The adapter's renderer only draws it and passes input back.
 */
public final class EditorModel {
    public static final String DEFAULT_MOD = "default";
    public static final String FILE_NAME = "buffeffects.effects.json";

    /**
     * @param serverRoot the server's root folder ({@code ecosystem/} lives under it)
     * @param devMods    mods open from their source folders: mod id to folder (config file only)
     */
    public record Config(String serverRoot, Map<String, String> devMods) {
        public Config {
            devMods = Map.copyOf(devMods);
        }
    }

    /** One row of the Effects tab. */
    public record Row(String effect, String mod, String name, String category, String status, boolean harmful,
                      boolean cosmetic, Double steps) {
    }

    /** The Effects tab's filters. Null fields don't filter. */
    public record Filters(Boolean harmful, Boolean cosmetic, Boolean excessive, Boolean dormantHere, String category) {
        public static Filters none() {
            return new Filters(null, null, null, null, null);
        }
    }

    /** One open editor: the effect as a sidecar object (edited in place), and the mod it belongs to. */
    public static final class Draft {
        public String mod;
        public Map<String, Object> effect;
        /** The full id it was opened from, or null for a new one. */
        public final String openedFrom;

        Draft(String mod, Map<String, Object> effect, String openedFrom) {
            this.mod = mod;
            this.effect = effect;
            this.openedFrom = openedFrom;
        }

        public String id() {
            return effect.get("id") instanceof String ? (String) effect.get("id") : null;
        }

        public String fullId() {
            return mod + "/" + id();
        }
    }

    /**
     * The live side panel: validation (with the loader's messages and pointers), the rating, whether it'd
     * be excessive here, and the caps it would hit on a typical target at its maximum strength.
     */
    public record Preview(List<Issue> issues, EffectDef def, Rating rating, boolean excessive, List<String> caps) {
        public boolean valid() {
            return def != null;
        }
    }

    public record SaveResult(boolean saved, SaveTarget target, List<Issue> issues) {
    }

    private final Engine engine;
    private final GameProfile profile;
    private final PowerRater rater;
    private final Storage storage;
    private final Config config;
    private final Map<String, String> modVersions;
    private final Runnable reload;
    private Consumer<String> log = s -> { };

    public EditorModel(Engine engine, GameProfile profile, PowerRater rater, Storage storage, Config config,
                       Map<String, String> modVersions, Runnable reload) {
        this.engine = engine;
        this.profile = profile;
        this.rater = rater;
        this.storage = storage;
        this.config = config;
        this.modVersions = new HashMap<>(modVersions);
        this.reload = reload;
    }

    public void setLog(Consumer<String> l) {
        log = l;
    }

    // ------------------------------------------------------------------------------------- Effects tab

    /** Every loaded effect matching the search and filters, grouped by mod (mods sorted, effects as loaded). */
    public Map<String, List<Row>> browse(String search, Filters f) {
        Map<String, List<Row>> out = new TreeMap<>();
        String q = search == null ? "" : search.toLowerCase(Locale.ROOT);
        for (Loaded l : engine.effects().values()) {
            EffectDef d = l.def();
            boolean cosmetic = l.rating() != null && l.rating().isCosmetic();
            if (!q.isEmpty() && !(d.fullId().toLowerCase(Locale.ROOT).contains(q) || d.name().toLowerCase(Locale.ROOT).contains(q))) continue;
            if (f.harmful() != null && f.harmful() != d.harmful()) continue;
            if (f.cosmetic() != null && f.cosmetic() != cosmetic) continue;
            if (f.excessive() != null && f.excessive() != (l.status() == Status.EXCESSIVE)) continue;
            if (f.dormantHere() != null && f.dormantHere() != (l.status() == Status.DORMANT)) continue;
            if (f.category() != null && !f.category().equalsIgnoreCase(String.valueOf(d.category()))) continue;
            out.computeIfAbsent(d.mod(), k -> new ArrayList<>()).add(new Row(d.fullId(), d.mod(), d.name(), d.category(),
                    l.status().name().toLowerCase(Locale.ROOT), d.harmful(), cosmetic, l.rating() == null ? null : l.rating().steps()));
        }
        return out;
    }

    /** The Active tab: every target carrying something. */
    public Map<String, List<ActiveApplication>> active() {
        return engine.allActive();
    }

    // ------------------------------------------------------------------------------------------ drafts

    /** A new effect. With no mod it belongs to {@code default}. */
    public Draft newDraft(String mod, String id) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("name", id);
        e.put("duration", new LinkedHashMap<>(Map.of("seconds", 30L)));
        e.put("parts", new ArrayList<>());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put(profile.name(), new LinkedHashMap<>());
        e.put("bindings", b);
        return new Draft(mod == null ? DEFAULT_MOD : mod, e, null);
    }

    /** Opens a loaded effect for editing, as it's loaded (after overrides). */
    public Draft open(String fullId) {
        Loaded l = engine.effects().get(fullId);
        if (l == null) return null;
        return new Draft(l.def().mod(), mutable(EffectWriter.toMap(l.def())), fullId);
    }

    /** A copy under a new id, in {@code default} unless the draft's mod is open from its source folder. */
    public Draft duplicate(Draft d, String newId) {
        Map<String, Object> e = mutable(d.effect);
        e.put("id", newId);
        String mod = config.devMods().containsKey(d.mod) ? d.mod : DEFAULT_MOD;
        return new Draft(mod, e, null);
    }

    /** Revert: the draft as it's loaded now (null for a new draft that was never saved). */
    public Draft revert(Draft d) {
        return d.openedFrom == null ? null : open(d.openedFrom);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mutable(Map<String, Object> m) {
        return (Map<String, Object>) Json.parse(Json.write(m, false));
    }

    /** Copies the binding's look from another loaded effect (window.md: "Copy look from..."). */
    @SuppressWarnings("unchecked")
    public boolean copyLookFrom(Draft d, String fromFullId) {
        Loaded l = engine.effects().get(fromFullId);
        if (l == null) return false;
        Binding src = l.def().binding(profile.name());
        if (src == null) return false;
        Map<String, Object> bindings = (Map<String, Object>) d.effect.computeIfAbsent("bindings", k -> new LinkedHashMap<>());
        Map<String, Object> mine = (Map<String, Object>) bindings.computeIfAbsent(profile.name(), k -> new LinkedHashMap<>());
        Map<String, Object> written = mutable(EffectWriter.toMap(l.def()));
        Map<String, Object> theirs = (Map<String, Object>) ((Map<String, Object>) written.get("bindings")).get(profile.name());
        for (String k : List.of("icon", "look")) {
            if (theirs.containsKey(k)) mine.put(k, theirs.get(k));
            else mine.remove(k);
        }
        return true;
    }

    // ----------------------------------------------------------------------------------------- preview

    public Preview preview(Draft d) {
        List<Issue> issues = new ArrayList<>();
        EffectDef def = EffectsFormat.readEffect("editor", d.effect, "", d.mod, profile, false, issues);
        if (def == null) return new Preview(issues, null, null, false, List.of());
        List<Part> natives = new ArrayList<>();
        Binding b = def.binding(profile.name());
        if (b != null) for (String n : b.natives()) natives.addAll(profile.nativeParts(n));
        Rating r = Power.rate(def, profile.name(), natives, def.strengthBounds().max(), Power.ratedSeconds(def), rater);
        Settings s = engine.settings();
        boolean optedIn = s.optedInEffects.contains(def.fullId()) || s.optedInMods.contains(def.mod());
        boolean excessive = !r.isUnrated() && !r.isCosmetic() && !optedIn && (r.cliff() || r.steps() > s.maxStepsPerEffect + 1e-9);
        return new Preview(issues, def, r, excessive, caps(def, r, s));
    }

    /** The caps an effect would hit on its own, on a typical target, at its maximum strength and duration. */
    static List<String> caps(EffectDef def, Rating r, Settings s) {
        List<String> out = new ArrayList<>();
        double strength = def.strengthBounds().max();
        Double seconds = Power.ratedSeconds(def);
        double secs = seconds == null ? Double.POSITIVE_INFINITY : seconds;
        Map<String, Double> resist = new TreeMap<>();
        for (Part p : def.parts()) {
            if (p.type().equals("resist")) {
                for (String leaf : Vocabulary.leaves(p.str("cause"))) resist.merge(leaf, p.scaled(strength).num("amount", 0), Double::sum);
            }
        }
        for (Map.Entry<String, Double> e : resist.entrySet()) if (e.getValue() > s.maxResistPlayers + 1e-9) out.add("resist." + e.getKey());
        if (def.has("invulnerable") && secs > s.invulnerableMaxSeconds + 1e-9) out.add("flag.invulnerable");
        boolean stun = false, root = false;
        for (Part p : def.parts()) {
            if (p.type().equals("disable_actions") && !"intangible".equals(p.fields().get("added_by")) && p.strings("actions").contains("attack")) stun = true;
            if (p.type().equals("lock_movement") && p.bool("all", false)) root = true;
        }
        if (stun && !def.has("intangible") && secs > s.stunMaxSeconds + 1e-9) out.add("flag.stun");
        if (root && secs > s.rootMaxSeconds + 1e-9) out.add("flag.root");
        if (r != null && !r.isUnrated() && !r.isCosmetic() && (r.cliff() || r.steps() > s.maxStepsPerEffect + 1e-9)) out.add("steps.effect");
        return out;
    }

    // --------------------------------------------------------------------------------------------- save

    /** Where saving this draft would write, and why (window.md section 3). */
    public SaveTarget target(Draft d) {
        String root = trimSlash(config.serverRoot());
        if (DEFAULT_MOD.equals(d.mod)) {
            return new SaveTarget(SaveTarget.Kind.DEFAULT, DEFAULT_MOD, root + "/ecosystem/default/" + FILE_NAME,
                    "saved to the default mod (local to this server; publishing it asks for a real mod name)");
        }
        String folder = config.devMods().get(d.mod);
        if (folder != null) {
            return new SaveTarget(SaveTarget.Kind.SOURCE, d.mod, sidecarInSource(trimSlash(folder.replace('\\', '/')), d.mod),
                    "exported to " + d.mod + "'s sidecar in its source folder");
        }
        if (engine.effects().containsKey(d.fullId())) {
            return new SaveTarget(SaveTarget.Kind.OVERRIDE, d.mod, root + "/ecosystem/overrides/" + d.mod.replace(':', '_') + "/" + FILE_NAME,
                    "saved as this server's override of " + d.fullId() + " (the mod itself isn't changed)");
        }
        return new SaveTarget(SaveTarget.Kind.DEFAULT, DEFAULT_MOD, root + "/ecosystem/default/" + FILE_NAME,
                d.mod + " isn't open from its source folder, and a new id can't go in another mod's override: saved to the default mod");
    }

    /**
     * Where a mod's sidecar lives in its source folder: {@code Effects/<Group>_<Name>.effects.json} under the
     * Hytale adapter's resources ({@code adapters/hytale/src/main/resources}) when the folder has that
     * layout, else under {@code src/main/resources}.
     */
    String sidecarInSource(String folder, String mod) {
        String rel = "Effects/" + mod.replace(':', '_') + ".effects.json";
        String adapter = folder + "/adapters/" + profile.name() + "/src/main/resources";
        if (storage.isDirectory(adapter)) return adapter + "/" + rel;
        return folder + "/src/main/resources/" + rel;
    }

    static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Validates and writes the draft where it belongs, then reloads. Refused while it doesn't validate. */
    public SaveResult save(Draft d, String who) {
        Preview p = preview(d);
        SaveTarget t = target(d);
        if (!p.valid()) return new SaveResult(false, t, p.issues());
        if (t.kind() == SaveTarget.Kind.DEFAULT && !DEFAULT_MOD.equals(d.mod)) {
            d.mod = DEFAULT_MOD;
            List<Issue> issues = new ArrayList<>();
            if (EffectsFormat.readEffect("editor", d.effect, "", d.mod, profile, false, issues) == null) {
                return new SaveResult(false, t, issues);
            }
        }
        Map<String, Object> effect = mutable(d.effect);
        String existing = storage.read(t.path());
        String text;
        if (t.kind() == SaveTarget.Kind.OVERRIDE) {
            text = SidecarFiles.upsertOverride(existing, d.mod, d.id(), effect, modVersions.get(d.mod));
        } else {
            text = SidecarFiles.upsertEffect(existing, t.mod(), null, effect);
        }
        storage.write(t.path(), text);
        log.accept("editor: " + who + " saved " + d.fullId() + " to " + t.path());
        if (reload != null) reload.run();
        return new SaveResult(true, t, p.issues());
    }

    /** Disables someone else's effect on this server (an override with {@code "disabled": true}). */
    public SaveTarget disable(String fullId, String who) {
        int slash = fullId.indexOf('/');
        String mod = fullId.substring(0, slash);
        String id = fullId.substring(slash + 1);
        Draft d = new Draft(mod, new LinkedHashMap<>(Map.of("id", id)), fullId);
        SaveTarget t = target(d);
        if (t.kind() != SaveTarget.Kind.OVERRIDE) return null;
        storage.write(t.path(), SidecarFiles.upsertOverride(storage.read(t.path()), mod, id, null, modVersions.get(mod)));
        log.accept("editor: " + who + " disabled " + fullId + " in " + t.path());
        if (reload != null) reload.run();
        return t;
    }

    /**
     * Deletes a draft: only effects in {@code default}, and this server's overrides (which brings the
     * mod's own definition back). Returns false for anything else.
     */
    public boolean delete(Draft d, String who) {
        SaveTarget t = target(d);
        if (t.kind() == SaveTarget.Kind.SOURCE) return false;
        if (t.kind() == SaveTarget.Kind.DEFAULT && !DEFAULT_MOD.equals(d.mod)) return false;
        String existing = storage.read(t.path());
        if (existing == null) return false;
        String text = SidecarFiles.remove(existing, d.id(), t.kind() == SaveTarget.Kind.OVERRIDE);
        if (text == null) return false;
        storage.write(t.path(), text);
        log.accept("editor: " + who + " deleted " + d.fullId() + " from " + t.path());
        if (reload != null) reload.run();
        return true;
    }
}
