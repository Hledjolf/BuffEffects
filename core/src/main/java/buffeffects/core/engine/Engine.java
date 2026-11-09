package buffeffects.core.engine;

import buffeffects.api.ActiveApplication;
import buffeffects.api.ApplyOptions;
import buffeffects.api.ApplyResult;
import buffeffects.api.BfxEvent;
import buffeffects.api.BuffEffects;
import buffeffects.api.CureRequest;
import buffeffects.api.EffectInfo;
import buffeffects.api.EventFilter;
import buffeffects.api.ListFilter;
import buffeffects.api.RemoveResult;
import buffeffects.api.Selector;
import buffeffects.core.format.Binding;
import buffeffects.core.format.DurationSpec;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.EffectLoader;
import buffeffects.core.format.EffectLoader.Loaded;
import buffeffects.core.format.EffectWriter;
import buffeffects.core.format.GameProfile;
import buffeffects.core.format.NativeEffect;
import buffeffects.core.format.Part;
import buffeffects.core.format.Vocabulary;
import buffeffects.core.json.Json;
import buffeffects.core.rating.Power;
import buffeffects.core.rating.PowerRater;
import buffeffects.core.rating.PowerRater.Rating;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The ledger and everything around it (api.md): applications, run-time timers that pause while the target
 * is away, {@code reapply}, stacks, stacking categories, the {@code model} slot, {@code on_death}, caps,
 * cures, messages, events and persistence. The ledger is the truth; the game only shows it, through the
 * {@link GamePort}. Plain and single-locked: every public method is synchronized.
 */
public final class Engine {
    public static final String API_VERSION = "1.0";
    /** How often native {@code ends_when} conditions are checked (wall ticks), and the rest (run time). */
    static final long CONDITION_MS = 250;
    static final long LIBRARY_CONDITION_MS = 5_000;
    static final long DR_WINDOW_MS = 15_000;

    private final GamePort port;
    private final GameProfile profile;
    private Conditions conditions = Conditions.NONE;
    private PowerRater rater;
    private Settings settings = new Settings();
    private Random random = new Random();
    private Consumer<String> log = s -> { };

    private Map<String, Loaded> effects = new LinkedHashMap<>();
    private final Map<String, EffectDef> nativeDefs = new HashMap<>();
    private final Map<String, List<Application>> byTarget = new LinkedHashMap<>();
    private final Map<String, Long> immuneUntil = new HashMap<>();
    private final Map<String, List<Long>> drHistory = new HashMap<>();
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Messages messages;
    /** The multipliers last sent per target, so the game is told only of changes. */
    private final Map<String, Map<String, Double>> sentMultipliers = new HashMap<>();
    private long nextId = 1;
    private long seq = 1;
    private long nextSub = 1;
    private long nowMs;
    private long conditionMs;

    record Sub(String caller, EventFilter filter, BuffEffects.Listener listener) {
    }

    public Engine(GamePort port, GameProfile profile) {
        this.port = port;
        this.profile = profile;
        this.messages = new Messages(port);
    }

    public synchronized void setConditions(Conditions c) {
        conditions = c == null ? Conditions.NONE : c;
    }

    public synchronized void setRater(PowerRater r) {
        rater = r;
    }

    public synchronized void setRandom(Random r) {
        random = r;
    }

    public synchronized void setLog(Consumer<String> l) {
        log = l;
    }

    public synchronized Settings settings() {
        return settings;
    }

    public synchronized void setSettings(Settings s) {
        settings = s;
    }

    public synchronized long nowMs() {
        return nowMs;
    }

    /** The caller-bound API instance. */
    public BuffEffects api(String caller) {
        return new Handle(caller);
    }

    // ------------------------------------------------------------------------------------- the registry

    /**
     * Installs what loaded (at startup and on a reload). Applications whose effect is gone end with
     * {@code unloaded_effect}; those an override or opt-in turned off end with {@code disabled}.
     */
    public synchronized void setEffects(EffectLoader.LoadedSet set) {
        effects = new LinkedHashMap<>(set.effects());
        for (List<Application> apps : new ArrayList<>(byTarget.values())) {
            for (Application a : new ArrayList<>(apps)) {
                if (isNative(a.effect)) continue;
                Loaded l = effects.get(a.effect);
                if (l == null) removeApp(a, "unloaded_effect", false);
                else if (!l.usable() || settings.disabledMods.contains(l.def().mod())) removeApp(a, "disabled", false);
            }
        }
    }

    public synchronized Map<String, Loaded> effects() {
        return new LinkedHashMap<>(effects);
    }

    /**
     * Resolves what a person typed: a full id, a game id, or a short id that only one loaded mod defines.
     * Returns the full reference, or a list of candidates (more than one) or none (unknown).
     */
    public synchronized List<String> resolve(String typed) {
        if (typed.indexOf('/') >= 0 || typed.indexOf(':') >= 0) return List.of(typed);
        List<String> out = new ArrayList<>();
        for (Loaded l : effects.values()) if (l.def().id().equals(typed)) out.add(l.def().fullId());
        return out;
    }

    static boolean isNative(String ref) {
        return ref.indexOf('/') < 0 && ref.indexOf(':') > 0;
    }

    /**
     * A game's own effect, applied by id: a definition with only that native in its binding, with the
     * game's own duration, {@code harmful} and overlap rule from the adapter's translation (effects.md
     * section 9.1). Forever only when the game's effect is infinite, or the adapter doesn't translate.
     */
    private EffectDef nativeDef(String ref) {
        return nativeDefs.computeIfAbsent(ref, r -> {
            String id = r.substring(r.indexOf(':') + 1);
            NativeEffect n = profile.nativeEffect(id);
            DurationSpec d = n == null || n.seconds() == null ? DurationSpec.forever() : DurationSpec.seconds(n.seconds());
            double min = n == null || n.seconds() == null ? 1.0 : Math.min(1.0, n.seconds());
            Binding b = new Binding(port.game(), null, Map.of(), Map.of(), Map.of(), null, List.of(id), List.of(), null);
            return new EffectDef(port.game(), id, id, null, n != null && n.harmful(), null, List.of(), d,
                    n == null ? EffectDef.Reapply.REFRESH : n.reapply(), null, 1, List.of(), List.of(), Map.of(port.game(), b), null,
                    List.of(), List.of(), EffectDef.OnDeath.CLEAR, new EffectDef.Bounds(min, 86_400), new EffectDef.Bounds(1, 1),
                    null, "buffeffects.apply." + port.game() + "." + id.toLowerCase());
        });
    }

    /** Forgets the native definitions, after the game's assets reload. */
    public synchronized void nativesChanged() {
        nativeDefs.clear();
    }

    private EffectDef defOf(String ref) {
        if (isNative(ref)) return nativeDef(ref);
        Loaded l = effects.get(ref);
        return l == null ? null : l.def();
    }

    // ---------------------------------------------------------------------------------------- applying

    synchronized ApplyResult apply(String caller, String target, String ref, ApplyOptions o, boolean dryRun) {
        if (o == null) o = ApplyOptions.none();
        ApplyResult r = applyInner(caller, target, ref, o, dryRun);
        log.accept((dryRun ? "check " : "apply ") + caller + " -> " + target + " " + ref + ": " + r.line()
                + (o.sourceId() != null ? " source_id=" + o.sourceId() : "") + (o.actor() != null ? " actor=" + o.actor() : "")
                + (o.asPerson() != null ? " as_person=" + o.asPerson() : ""));
        if (!r.applied() && !dryRun) emit(caller, new BfxEvent(BfxEvent.Type.REFUSED, null, r.code(), ref, target));
        return r;
    }

    private ApplyResult applyInner(String caller, String target, String ref, ApplyOptions o, boolean dryRun) {
        // 1. loaded, not dormant, excessive or disabled
        EffectDef def;
        Loaded loaded = null;
        if (isNative(ref)) {
            if (!ref.startsWith(port.game() + ":")) return ApplyResult.refused("dormant", ref + " is another game's effect");
            if (Boolean.FALSE.equals(profile.knows(GameProfile.Kind.EFFECT, ref.substring(ref.indexOf(':') + 1)))) {
                return ApplyResult.refused("unknown_effect", port.game() + " has no effect " + ref.substring(ref.indexOf(':') + 1));
            }
            def = nativeDef(ref);
        } else {
            loaded = effects.get(ref);
            if (loaded == null) return ApplyResult.refused("unknown_effect", "no effect " + ref + " is loaded");
            def = loaded.def();
            switch (loaded.status()) {
                case DORMANT: return ApplyResult.refused("dormant", ref + ": " + loaded.why());
                case EXCESSIVE: return ApplyResult.refused("excessive", ref + " is above the power curve, and this server hasn't opted in");
                case DISABLED: return ApplyResult.refused("disabled", ref + " is disabled by a server override");
                default: break;
            }
            if (settings.disabledMods.contains(def.mod())) return ApplyResult.refused("disabled", def.mod() + "'s effects are off on this server");
        }
        TargetInfo info = null;
        if (target != null) {
            info = port.target(target);
            if (info == null || !info.loaded()) return ApplyResult.refused("target_not_loaded", "the target isn't in a loaded world");
        } else if (!dryRun) {
            return ApplyResult.refused("target_not_loaded", "no target");
        }
        // 2. permission
        if (o.asPerson() != null && !port.hasPermission(o.asPerson(), def.permission())) {
            return ApplyResult.refused("no_permission", "applying " + ref + " needs " + def.permission());
        }
        // 3. immune_if
        if (target != null) {
            for (String im : def.immuneIf()) {
                boolean carries = isNative(im) ? im.startsWith(port.game() + ":") && port.carriesNative(target, im.substring(im.indexOf(':') + 1))
                        : hasActive(target, im);
                if (carries) return ApplyResult.refused("immune", "the target carries " + im);
            }
        }
        // 4. bounds
        DurationSpec d = def.duration();
        long ms = d.hasRunTime() ? d.runMs() : -1;
        boolean infinite = d.infinite();
        if (Boolean.TRUE.equals(o.infinite())) {
            if (isNative(ref)) infinite = true;
            else if (!d.infinite()) return ApplyResult.refused("out_of_bounds", ref + " can't be applied without an end");
        } else if (o.durationSeconds() != null) {
            double s = o.durationSeconds();
            boolean nativeTimed = isNative(ref);
            if (!nativeTimed && (!d.hasRunTime() || def.durationBounds() == null)) {
                return ApplyResult.refused("out_of_bounds", ref + "'s duration can't be set by a caller");
            }
            if (!def.durationBounds().contains(s)) {
                return ApplyResult.refused("out_of_bounds", "duration " + s + " s is outside " + def.durationBounds().min() + "-" + def.durationBounds().max() + " s");
            }
            ms = Math.round(s * 1000);
            if (nativeTimed) infinite = false;
        }
        double strength = o.strength() == null ? 1.0 : o.strength();
        if (!def.strengthBounds().contains(strength)) {
            return ApplyResult.refused("out_of_bounds", "strength " + strength + " is outside " + def.strengthBounds().min() + "-" + def.strengthBounds().max());
        }
        String worldPoint = d.worldPoint();
        int worldDays = d.worldDays();
        if (o.worldClock() != null) {
            if (!d.usesWorldClock() || !List.of("dawn", "dusk", "end_of_day").contains(o.worldClock())) {
                return ApplyResult.refused("out_of_bounds", ref + " doesn't take an in-game clock point " + o.worldClock());
            }
            worldPoint = o.worldClock();
            worldDays = 0;
        }
        if (o.sourceId() != null && o.sourceId().length() > 120) return ApplyResult.refused("out_of_bounds", "source_id is longer than 120 characters");
        strength = port.quantizeStrength(def, strength);
        // 5. reapply
        List<Application> existing = target == null ? List.of() : appsOf(target, ref);
        if (def.reapply() == EffectDef.Reapply.IGNORE && !existing.isEmpty()) {
            return ApplyResult.refused("already_active", ref + " is already on the target");
        }
        // A refresh never lowers the strength (effects.md section 5).
        if ((def.reapply() == EffectDef.Reapply.REFRESH || def.reapply() == EffectDef.Reapply.EXTEND) && !existing.isEmpty()
                && strength < existing.get(0).strength - 1e-9) {
            return ApplyResult.refused("weaker_than_active", ref + " is already active at strength " + existing.get(0).strength);
        }
        // 6. caps
        Rating rating = rate(def, strength, ms >= 0 && !infinite ? ms / 1000.0 : null);
        // Vanilla effects applied by their own id pass the caps, for the game's own duration or shorter
        // (effects.md sections 9.1, 11). Longer, they're capped like a mod's effect.
        boolean exempt = isNative(ref) && (d.infinite() || (!infinite && d.hasRunTime() && ms <= d.runMs()));
        if (info != null && !exempt) {
            String cap = capCheck(info, def, strength, infinite ? -1 : ms, existing);
            if (cap != null) return ApplyResult.refused("capped:" + cap, capMessage(cap));
        }
        if (target != null) {
            Object cond = endsWhen(def);
            if (cond != null && conditions.evaluate(cond, target, o.actor()) == Conditions.Tri.TRUE) {
                return ApplyResult.refused("ends_when_true", ref + "'s ends_when is already true for the target");
            }
        }
        List<String> dropped = port.droppedParts(def);
        Double usedSeconds = infinite || ms < 0 ? null : ms / 1000.0;
        if (dryRun) {
            ApplyResult.How how = existing.isEmpty() ? ApplyResult.How.NEW : switch (def.reapply()) {
                case EXTEND -> ApplyResult.How.EXTENDED;
                case STACK -> ApplyResult.How.STACKED;
                default -> ApplyResult.How.REFRESHED;
            };
            return new ApplyResult(true, null, usedSeconds, strength, dropped, how, null, "check only: nothing applied");
        }
        // 7. apply
        Long worldEnd = null;
        String worldLabel = null;
        if (worldPoint != null || worldDays > 0) {
            worldEnd = port.worldTimeAt(target, worldPoint, worldDays);
            worldLabel = worldPoint != null ? worldPoint : worldDays + (worldDays == 1 ? " day" : " days");
        }
        boolean suppress = Boolean.TRUE.equals(o.suppressOnApply());
        if (!existing.isEmpty() && def.reapply() != EffectDef.Reapply.STACK) {
            Application a = existing.get(0);
            ApplyResult.How how;
            if (def.reapply() == EffectDef.Reapply.EXTEND && a.msLeft >= 0 && ms >= 0) {
                long cap = def.durationBounds() != null ? Math.round(def.durationBounds().max() * 1000) : Long.MAX_VALUE;
                a.msLeft = Math.min(cap, a.msLeft + ms);
                how = ApplyResult.How.EXTENDED;
            } else {
                if (ms >= 0 && a.msLeft >= 0 && ms > a.msLeft) a.msLeft = ms;
                how = ApplyResult.How.REFRESHED;
            }
            if (worldEnd != null && (a.worldEnd == null || worldEnd > a.worldEnd)) {
                a.worldEnd = worldEnd;
                a.worldEndLabel = worldLabel;
            }
            a.caller = caller;
            a.sourceId = o.sourceId();
            a.actor = o.actor();
            a.strength = strength;
            a.steps = rating == null ? null : rating.steps();
            a.appliedMs = Math.max(a.appliedMs, ms);
            if (!o.messages().isEmpty()) a.messages = new ArrayList<>(o.messages());
            if (a.shown) port.activate(a, def, a.msLeft, false, a.slotWaiting);
            say(a, def, "refresh");
            emit(caller, new BfxEvent(BfxEvent.Type.APPLIED, snapshot(a), how.name().toLowerCase(), ref, target));
            return new ApplyResult(true, a.id, a.msLeft >= 0 ? a.msLeft / 1000.0 : null, strength, dropped, how, null, null);
        }
        ApplyResult.How how = ApplyResult.How.NEW;
        if (def.reapply() == EffectDef.Reapply.STACK && !existing.isEmpty()) {
            how = ApplyResult.How.STACKED;
            if (existing.size() >= def.maxStacks()) {
                existing.sort(Comparator.comparingLong(x -> x.seq));
                removeApp(existing.get(0), "replaced", false);
            }
        }
        Application a = new Application("bfx-" + Long.toString(nextId++, 36), ref, target, seq++);
        a.caller = caller;
        a.sourceId = o.sourceId();
        a.actor = o.actor();
        a.strength = strength;
        a.msLeft = infinite ? -1 : ms;
        a.appliedMs = a.msLeft;
        a.infinite = infinite;
        a.worldEnd = worldEnd;
        a.worldEndLabel = worldLabel;
        a.droppedParts = new ArrayList<>(dropped);
        a.steps = rating == null ? null : rating.steps();
        a.messages = new ArrayList<>(o.messages());
        byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(a);
        if (isFlag(def, "stun") || isFlag(def, "root")) {
            drHistory.computeIfAbsent(target + "|" + flagCategory(def, isFlag(def, "stun") ? "stun" : "root"), k -> new ArrayList<>()).add(nowMs);
        }
        recompute(target, a, suppress);
        if (!info.live()) a.paused = true;
        if (!suppress) say(a, def, how == ApplyResult.How.STACKED ? "refresh" : "apply");
        emit(caller, new BfxEvent(BfxEvent.Type.APPLIED, snapshot(a), how.name().toLowerCase(), ref, target));
        // An instant effect (seconds 0): its once-only parts happened; it ends at once (effects.md section 4).
        if (def.duration().isInstant() && o.durationSeconds() == null) removeApp(a, "expired", false);
        return new ApplyResult(true, a.id, usedSeconds, strength, dropped, how, null, null);
    }

    private Rating rate(EffectDef def, double strength, Double seconds) {
        List<Part> natives = new ArrayList<>();
        Binding b = def.binding(port.game());
        if (b != null) for (String n : b.natives()) natives.addAll(profile.nativeParts(n));
        return Power.rate(def, port.game(), natives, strength, seconds, rater);
    }

    // -------------------------------------------------------------------------------------------- caps

    static String capMessage(String cap) {
        if (cap.startsWith("resist.")) return "the target's resistance to " + cap.substring(7) + " would pass the cap";
        switch (cap) {
            case "flag.invulnerable": return "invulnerability would last longer than the cap";
            case "immunity_window.invulnerable": return "the target was invulnerable too recently";
            case "flag.stun": return "the stun would last longer than the cap (diminishing returns count)";
            case "immunity_window.stun": return "the target was stunned too often recently";
            case "flag.root": return "the root would last longer than the cap (diminishing returns count)";
            case "immunity_window.root": return "the target was rooted too often recently";
            case "regen": return "the target's regeneration would pass the zone's incoming damage";
            case "steps.effect": return "the effect is stronger than one effect may be";
            case "steps.total": return "the target's effects together would be stronger than allowed";
            default: return "a cap would be broken";
        }
    }

    /**
     * The parts the caps see: the effect's own, plus those of the game effects its binding wraps
     * ({@code native}), from the adapter's translation of the game's own definitions (effects.md sections
     * 9.1, 11). A vanilla effect applied by its own id is checked only beyond its own duration.
     */
    private List<Part> capParts(EffectDef def) {
        List<Part> out = new ArrayList<>(def.partsOn(port.game()));
        Binding b = def.binding(port.game());
        if (b != null) for (String n : b.natives()) out.addAll(profile.nativeParts(n));
        return out;
    }

    private boolean isFlag(EffectDef def, String flag) {
        if (def.has("intangible") && flag.equals("stun")) return false;
        for (Part p : capParts(def)) {
            switch (flag) {
                case "invulnerable":
                    if (p.type().equals("invulnerable")) return true;
                    break;
                case "stun":
                    if (p.type().equals("disable_actions") && p.strings("actions").contains("attack")) return true;
                    break;
                case "root":
                    if (p.type().equals("lock_movement") && p.bool("all", false)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private static String flagCategory(EffectDef def, String flag) {
        return def.stackingCategory() != null ? def.stackingCategory() : flag;
    }

    /** Returns the cap that would be broken, or null. {@code ms} is -1 for no run-time end. */
    private String capCheck(TargetInfo info, EffectDef def, double strength, long ms, List<Application> sameEffect) {
        CapProfile native_ = port.capProfile();
        GamePort.Zone zone = port.zone(info.id());
        boolean replaces = def.reapply() != EffectDef.Reapply.STACK;
        List<Application> counting = new ArrayList<>();
        for (Application a : byTarget.getOrDefault(info.id(), List.of())) {
            if (a.categoryDormant) continue;
            if (replaces && sameEffect.contains(a)) continue;
            counting.add(a);
        }
        // Resistance per cause, on the combined state.
        Map<String, Double> resist = new HashMap<>();
        for (Application a : counting) addResist(defOf(a.effect), a.strength, resist, 1);
        Map<String, Double> mine = new HashMap<>();
        addResist(def, strength, mine, 1);
        double cap = CapProfile.atLeast(settings.maxResist(info.kind()), native_.maxResist());
        if (settings.capAtZoneVanillaMax && !Double.isNaN(zone.maxVanillaResist())) {
            cap = Math.min(cap, CapProfile.atLeast(zone.maxVanillaResist(), native_.maxResist()));
        }
        for (Map.Entry<String, Double> e : mine.entrySet()) {
            if (e.getValue() <= 0) continue;
            double total = e.getValue() + resist.getOrDefault(e.getKey(), 0.0);
            if (total > cap + 1e-9) return "resist." + e.getKey();
        }
        double seconds = ms < 0 ? Double.POSITIVE_INFINITY : ms / 1000.0;
        // Flags: invulnerable, then stun and root with diminishing returns.
        if (isFlag(def, "invulnerable")) {
            String key = info.id() + "|" + flagCategory(def, "invulnerable");
            if (immuneUntil.getOrDefault(key, Long.MIN_VALUE) > nowMs) return "immunity_window.invulnerable";
            if (seconds > CapProfile.atLeast(settings.invulnerableMaxSeconds, native_.maxInvulnerableSeconds()) + 1e-9) return "flag.invulnerable";
        }
        for (String flag : List.of("stun", "root")) {
            if (!isFlag(def, flag)) continue;
            double max = flag.equals("stun") ? settings.stunMaxSeconds : settings.rootMaxSeconds;
            if (settings.diminishingReturns) {
                List<Long> h = drHistory.getOrDefault(info.id() + "|" + flagCategory(def, flag), List.of());
                int recent = 0;
                for (long t : h) if (nowMs - t < DR_WINDOW_MS) recent++;
                if (recent >= 3) return "immunity_window." + flag;
                max = max / (1 << recent);
            }
            if (seconds > max + 1e-9) return "flag." + flag;
        }
        // Regeneration below the zone's incoming damage.
        if (settings.capRegen) {
            double mineRegen = regenPerSecond(def, strength, info.id(), Set.of());
            if (mineRegen > 0) {
                double total = mineRegen;
                for (Application a : counting) total += regenPerSecond(defOf(a.effect), a.strength, info.id(), a.brokenParts);
                if (total >= CapProfile.atLeast(zone.incomingDps(), native_.maxRegenPerSecond())) return "regen";
            }
        }
        // Rating caps (+2 steps per effect, +3 in total) are shown, not enforced, in 1.0 (effects.md section 11).
        return null;
    }

    private void addResist(EffectDef def, double strength, Map<String, Double> into, double sign) {
        if (def == null) return;
        for (Part p : capParts(def)) {
            if (!p.type().equals("resist")) continue;
            String cause = p.str("cause");
            double amt = p.scaled(strength).num("amount", 0) * sign;
            for (String leaf : Vocabulary.leaves(cause)) into.merge(leaf, amt, Double::sum);
        }
    }

    private double regenPerSecond(EffectDef def, double strength, String target, Set<Integer> broken) {
        if (def == null) return 0;
        double total = 0;
        List<Part> parts = def.partsOn(port.game());
        for (int k = 0; k < parts.size(); k++) {
            Part p = parts.get(k);
            if (!p.type().equals("regen") || !"health".equals(p.str("stat")) || broken.contains(k)) continue;
            double amt = p.scaled(strength).num("amount", 0);
            if (amt <= 0) continue;
            if ("percent".equals(p.str("unit"))) {
                double max = port.statMax(target, "health");
                if (Double.isNaN(max)) continue;
                amt = amt * max / 100.0;
            }
            total += amt / p.num("interval", 1);
        }
        return total;
    }

    // ---------------------------------------------------------------------- categories and the slot

    /**
     * Re-decides, for one target, which applications count (the strongest effect of each stacking
     * category) and who holds the {@code model} slot (the first to arrive), and tells the game.
     * {@code fresh} is a new application, shown with its on_apply unless {@code suppress}.
     */
    private void recompute(String target, Application fresh, boolean suppress) {
        List<Application> apps = byTarget.getOrDefault(target, List.of());
        TargetInfo info = port.target(target);
        boolean live = info != null && info.live();
        Map<String, String> bestByCategory = new HashMap<>();
        Map<String, double[]> bestScore = new HashMap<>();
        for (Application a : apps) {
            EffectDef d = defOf(a.effect);
            if (d == null || d.stackingCategory() == null) continue;
            double s = a.steps == null ? (isCliff(a) ? Double.POSITIVE_INFINITY : 0) : a.steps;
            double[] cur = bestScore.get(d.stackingCategory());
            if (cur == null || s > cur[0] + 1e-9 || (Math.abs(s - cur[0]) <= 1e-9 && (a.strength > cur[1] || (a.strength == cur[1] && a.seq < cur[2])))) {
                bestScore.put(d.stackingCategory(), new double[] {s, a.strength, a.seq});
                bestByCategory.put(d.stackingCategory(), a.effect);
            }
        }
        List<Application> sorted = new ArrayList<>(apps);
        sorted.sort(Comparator.comparingLong(x -> x.seq));
        Application slotHolder = null;
        for (Application a : sorted) {
            EffectDef d = defOf(a.effect);
            if (d == null) continue;
            boolean wasDormant = a.categoryDormant;
            a.categoryDormant = d.stackingCategory() != null && !a.effect.equals(bestByCategory.get(d.stackingCategory()));
            Binding b = d.binding(port.game());
            boolean wasWaiting = a.slotWaiting;
            if (b != null && b.shape() != null && !a.categoryDormant) {
                if (slotHolder == null) {
                    slotHolder = a;
                    a.slotWaiting = false;
                } else {
                    a.slotWaiting = true;
                }
            } else {
                a.slotWaiting = false;
            }
            if (a == fresh) {
                if (live && !a.categoryDormant) {
                    port.activate(a, d, a.msLeft, !suppress, a.slotWaiting);
                    a.shown = true;
                }
                continue;
            }
            if (!live) continue;
            if (a.categoryDormant && !wasDormant && a.shown) {
                port.deactivate(a, d, false);
                a.shown = false;
            } else if (!a.categoryDormant && (wasDormant || !a.shown)) {
                port.activate(a, d, a.msLeft, false, a.slotWaiting);
                a.shown = true;
            } else if (wasWaiting != a.slotWaiting && a.shown) {
                port.activate(a, d, a.msLeft, false, a.slotWaiting);
                emit(null, new BfxEvent(BfxEvent.Type.SLOT_CHANGED, snapshot(a), "model:" + (a.slotWaiting ? "lost" : "taken"), a.effect, target));
            }
        }
        if (live) updateMultipliers(target);
    }

    /**
     * Tells the game the combined {@code multiply} factor of every stat on {@code target}: the bonuses of the
     * applications that count (not dormant) summed, {@code 1 + Σ(m − 1)}. Vanilla effects applied by id keep
     * the game's own rule, so they aren't counted.
     */
    private void updateMultipliers(String target) {
        Map<String, Double> f = new LinkedHashMap<>();
        for (Application a : byTarget.getOrDefault(target, List.of())) {
            if (a.categoryDormant || isNative(a.effect)) continue;
            EffectDef d = defOf(a.effect);
            if (d == null) continue;
            for (Part p : d.partsOn(port.game())) {
                if (!p.type().equals("stat") || !"multiply".equals(p.str("op"))) continue;
                f.merge(p.str("stat"), p.scaled(a.strength).num("amount", 1) - 1, Double::sum);
            }
        }
        Map<String, Double> factors = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : f.entrySet()) factors.put(e.getKey(), Math.max(0, 1 + e.getValue()));
        if (factors.equals(sentMultipliers.getOrDefault(target, Map.of()))) return;
        if (factors.isEmpty()) sentMultipliers.remove(target);
        else sentMultipliers.put(target, factors);
        port.setStatMultipliers(target, factors);
    }

    private boolean isCliff(Application a) {
        Loaded l = effects.get(a.effect);
        return l != null && l.rating() != null && l.rating().cliff();
    }

    // ----------------------------------------------------------------------------------------- removal

    private void removeApp(Application a, String reason, boolean playOnRemove) {
        List<Application> apps = byTarget.get(a.target);
        if (apps == null || !apps.remove(a)) return;
        if (apps.isEmpty()) byTarget.remove(a.target);
        EffectDef d = defOf(a.effect);
        if (a.shown && d != null) port.deactivate(a, d, playOnRemove);
        a.shown = false;
        if (d != null && isFlag(d, "invulnerable")) {
            immuneUntil.put(a.target + "|" + flagCategory(d, "invulnerable"), nowMs + Math.round(settings.invulnerableImmunitySeconds * 1000));
        }
        if (byTarget.containsKey(a.target)) recompute(a.target, null, false);
        else updateMultipliers(a.target);
        if (d != null) {
            String when = switch (reason) {
                case "expired" -> "expire";
                case "cured" -> "cure";
                case "death" -> "death";
                default -> "remove";
            };
            boolean generic = !d.duration().isInstant() && !isNative(a.effect) && !silentCosmetic(a.effect, d);
            messages.moment(a, d, when, appsOf(a.target, a.effect).size() + 1, settings, conditions, random, generic);
        }
        log.accept("removed " + a.id + " " + a.effect + " from " + a.target + ": " + reason);
        emit(null, new BfxEvent(BfxEvent.Type.REMOVED, snapshot(a), reason, a.effect, a.target));
    }

    private void say(Application a, EffectDef d, String when) {
        messages.moment(a, d, when, appsOf(a.target, a.effect).size(), settings, conditions, random, false);
    }

    /** A cosmetic effect with no messages of its own stays silent: no generic wear-off line (effects.md section 6). */
    private boolean silentCosmetic(String ref, EffectDef d) {
        Loaded l = effects.get(ref);
        boolean cosmetic = l != null && l.rating() != null && l.rating().isCosmetic();
        return cosmetic && !d.has("message");
    }

    private Object endsWhen(EffectDef d) {
        Binding b = d.binding(port.game());
        return b != null && b.endsWhen() != null ? b.endsWhen() : d.endsWhen();
    }

    synchronized RemoveResult remove(String caller, String target, Selector sel) {
        List<ActiveApplication> out = new ArrayList<>();
        for (Application a : new ArrayList<>(byTarget.getOrDefault(target, List.of()))) {
            boolean match;
            switch (sel.kind()) {
                case APPLICATION_ID: match = a.id.equals(sel.value()); break;
                case EFFECT: match = a.effect.equals(sel.value()); break;
                case SOURCE_ID: match = sel.value() != null && sel.value().equals(a.sourceId) && caller.equals(a.caller); break;
                default: match = caller.equals(a.caller); break;
            }
            if (!match) continue;
            out.add(snapshot(a));
            removeApp(a, "removed", true);
        }
        log.accept("remove " + caller + " -> " + target + " " + sel.kind().name().toLowerCase() + "=" + sel.value() + ": " + out.size() + " removed");
        return new RemoveResult(out);
    }

    synchronized RemoveResult cure(String caller, String target, CureRequest c) {
        boolean mayCureAll = c.asPerson() == null || port.hasPermission(c.asPerson(), "buffeffects.cure");
        List<ActiveApplication> out = new ArrayList<>();
        for (Application a : new ArrayList<>(byTarget.getOrDefault(target, List.of()))) {
            if (!mayCureAll && !caller.equals(a.caller)) continue;
            EffectDef d = defOf(a.effect);
            if (d == null) continue;
            boolean match = (c.harmful() && d.harmful()) || c.effects().contains(a.effect);
            for (String t : c.tags()) if (d.cureTags().contains(t)) match = true;
            if (!match) continue;
            out.add(snapshot(a));
            removeApp(a, "cured", true);
        }
        log.accept("cure " + caller + " -> " + target + " harmful=" + c.harmful() + " tags=" + c.tags() + " effects=" + c.effects()
                + ": " + out.size() + " removed" + (c.asPerson() != null ? " as_person=" + c.asPerson() : ""));
        return new RemoveResult(out);
    }

    // ------------------------------------------------------------------------------------------ asking

    private List<Application> appsOf(String target, String effect) {
        List<Application> out = new ArrayList<>();
        for (Application a : byTarget.getOrDefault(target, List.of())) if (a.effect.equals(effect)) out.add(a);
        return out;
    }

    private boolean hasActive(String target, String effect) {
        for (Application a : byTarget.getOrDefault(target, List.of())) if (a.effect.equals(effect)) return true;
        return false;
    }

    synchronized List<ActiveApplication> active(String target) {
        List<ActiveApplication> out = new ArrayList<>();
        for (Application a : byTarget.getOrDefault(target, List.of())) out.add(snapshot(a));
        return out;
    }

    /** Every target with applications (for the window's Active tab and the commands). */
    public synchronized Map<String, List<ActiveApplication>> allActive() {
        Map<String, List<ActiveApplication>> out = new LinkedHashMap<>();
        for (String t : byTarget.keySet()) out.put(t, active(t));
        return out;
    }

    synchronized Boolean has(String target, String effect, String sourceId) {
        TargetInfo info = port.target(target);
        if (info == null || !info.loaded()) return null;
        for (Application a : byTarget.getOrDefault(target, List.of())) {
            if (a.effect.equals(effect) && (sourceId == null || sourceId.equals(a.sourceId))) return true;
        }
        if (isNative(effect) && effect.startsWith(port.game() + ":")) return port.carriesNative(target, effect.substring(effect.indexOf(':') + 1));
        return false;
    }

    synchronized int stacks(String target, String effect) {
        return appsOf(target, effect).size();
    }

    private ActiveApplication snapshot(Application a) {
        EffectDef d = defOf(a.effect);
        String dormant = a.categoryDormant ? "category:" + (d == null ? "?" : d.stackingCategory()) : a.slotWaiting ? "slot:model" : null;
        int stacks = appsOf(a.target, a.effect).size();
        return new ActiveApplication(a.id, a.effect, d == null ? a.effect : d.displayName(stacks), a.target,
                a.msLeft >= 0 ? a.msLeft / 1000.0 : null, a.infinite, a.worldEndLabel, stacks, a.strength, a.sourceId, a.caller, a.actor, dormant,
                a.paused, a.droppedParts);
    }

    public synchronized EffectInfo describe(String ref) {
        if (isNative(ref)) {
            if (!ref.startsWith(port.game() + ":")) return null;
            String id = ref.substring(ref.indexOf(':') + 1);
            EffectDef d = nativeDef(ref);
            NativeEffect n = profile.nativeEffect(id);
            Rating r = rate(d, 1.0, d.duration().hasRunTime() ? d.duration().runMs() / 1000.0 : null);
            Map<String, Object> json = n != null ? n.toMap() : new java.util.LinkedHashMap<>(Map.of("id", ref,
                    "untranslated", List.of("the adapter has no translation for this effect")));
            return new EffectInfo(ref, port.game(), d.name(), null, d.harmful(), null, List.of(), List.of(port.game()), "active",
                    "the game's own effect", r.steps(), r.cliff(), !r.isUnrated(), null, r.flags(), false, false,
                    Json.write(json, true), r.note());
        }
        Loaded l = effects.get(ref);
        if (l == null) return null;
        EffectDef d = l.def();
        Rating r = l.rating();
        return new EffectInfo(d.fullId(), d.mod(), d.name(), d.description(), d.harmful(), d.category(), d.tags(),
                new ArrayList<>(d.bindings().keySet()), l.status().name().toLowerCase(), l.why(),
                r == null ? null : r.steps(), r != null && r.cliff(), r != null && !r.isUnrated(), d.declaredPower(),
                r == null ? Set.of() : r.flags(), l.optedIn(), l.overridden(), Json.write(EffectWriter.toMap(d), true),
                r == null ? null : r.note());
    }

    synchronized List<String> list(ListFilter f) {
        List<String> out = new ArrayList<>();
        for (Loaded l : effects.values()) {
            EffectDef d = l.def();
            if (f.mod() != null && !f.mod().equalsIgnoreCase(d.mod())) continue;
            if (f.category() != null && !f.category().equalsIgnoreCase(String.valueOf(d.category()))) continue;
            if (f.tag() != null && !d.tags().contains(f.tag())) continue;
            if (f.harmful() != null && f.harmful() != d.harmful()) continue;
            boolean cosmetic = l.rating() != null && l.rating().isCosmetic();
            if (f.cosmetic() != null && f.cosmetic() != cosmetic) continue;
            if (f.usableHere() != null && f.usableHere() != l.usable()) continue;
            out.add(d.fullId());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------- game events

    /**
     * Advances run time by {@code elapsedMs}: for each target that's live (online, alive, loaded) timers
     * count down, lines are said and expired applications end; others are paused. Applications that the
     * game isn't showing yet (after a login, a respawn or a restart) are shown again.
     */
    public synchronized void tick(long elapsedMs) {
        nowMs += elapsedMs;
        conditionMs += elapsedMs;
        boolean checkNative = conditionMs >= CONDITION_MS;
        if (checkNative) conditionMs = 0;
        for (String target : new ArrayList<>(byTarget.keySet())) {
            TargetInfo info = port.target(target);
            boolean live = info != null && info.live();
            List<Application> apps = byTarget.get(target);
            if (apps == null) continue;
            for (Application a : new ArrayList<>(apps)) {
                EffectDef d = defOf(a.effect);
                if (d == null) continue;
                if (!live) {
                    if (!a.paused) {
                        a.paused = true;
                        a.shown = false;
                        sentMultipliers.remove(target);
                        emit(null, new BfxEvent(BfxEvent.Type.PAUSED, snapshot(a), null, a.effect, target));
                    }
                    continue;
                }
                boolean back = false;
                if (a.paused) {
                    a.paused = false;
                    back = true;
                    emit(null, new BfxEvent(BfxEvent.Type.RESUMED, snapshot(a), null, a.effect, target));
                }
                if (!a.categoryDormant && !a.shown) {
                    port.activate(a, d, a.msLeft, false, a.slotWaiting);
                    a.shown = true;
                    a.keptThroughDeath = false;
                    updateMultipliers(target);
                }
                if (a.msLeft >= 0) {
                    a.msLeft = Math.max(0, a.msLeft - elapsedMs);
                    if (a.msLeft == 0) {
                        removeApp(a, "expired", true);
                        continue;
                    }
                }
                if (a.worldEnd != null && port.worldTime(target) >= a.worldEnd) {
                    removeApp(a, "expired", true);
                    continue;
                }
                if (!a.categoryDormant) messages.every(a, d, elapsedMs, appsOf(target, a.effect).size(), settings, conditions, random);
                // ends_when: when the bearer is back (a date may have passed), every 5 s of run time, and at once
                // for the game's native conditions (effects.md section 8).
                Object cond = endsWhen(d);
                if (cond != null) {
                    a.conditionMs += elapsedMs;
                    boolean due = back || a.conditionMs >= LIBRARY_CONDITION_MS || (checkNative && conditions.isNative(cond));
                    if (due) {
                        a.conditionMs = 0;
                        if (conditions.evaluate(cond, target, a.actor) == Conditions.Tri.TRUE) removeApp(a, "ends_when", true);
                    }
                }
            }
        }
    }

    /** The target died: each application follows its effect's {@code on_death}. */
    public synchronized void onDeath(String target) {
        for (Application a : new ArrayList<>(byTarget.getOrDefault(target, List.of()))) {
            EffectDef d = defOf(a.effect);
            if (d == null || d.onDeath() == EffectDef.OnDeath.CLEAR) {
                removeApp(a, "death", false);
                continue;
            }
            if (d.onDeath() == EffectDef.OnDeath.RESTART && a.appliedMs >= 0) a.msLeft = a.appliedMs;
            a.keptThroughDeath = true;
            a.shown = false;
            sentMultipliers.remove(target);
        }
    }

    /**
     * The target took damage: {@code regen} parts with {@code breaks_on_hit} stop. An effect that's only
     * such regeneration ends (reason {@code interrupted}); otherwise just those parts stop.
     */
    public synchronized void onDamaged(String target) {
        for (Application a : new ArrayList<>(byTarget.getOrDefault(target, List.of()))) {
            if (!a.shown || a.categoryDormant) continue;
            EffectDef d = defOf(a.effect);
            if (d == null) continue;
            List<Part> parts = d.partsOn(port.game());
            List<Integer> breaking = new ArrayList<>();
            int rated = 0;
            for (int k = 0; k < parts.size(); k++) {
                Part p = parts.get(k);
                if (p.type().equals("message")) continue;
                rated++;
                if (p.type().equals("regen") && p.bool("breaks_on_hit", true) && !a.brokenParts.contains(k)) breaking.add(k);
            }
            if (breaking.isEmpty()) continue;
            if (breaking.size() + a.brokenParts.size() >= rated) {
                removeApp(a, "interrupted", false);
                continue;
            }
            for (int k : breaking) {
                a.brokenParts.add(k);
                port.stopPart(a, d, k);
            }
        }
    }

    // -------------------------------------------------------------------------------------------- events

    private void emit(String onlyCaller, BfxEvent e) {
        for (Sub s : new ArrayList<>(subs.values())) {
            if (e.type() == BfxEvent.Type.REFUSED && !s.caller().equals(onlyCaller)) continue;
            EventFilter f = s.filter();
            if (f.effect() != null && !f.effect().equals(e.effect())) continue;
            if (f.target() != null && !f.target().equals(e.target())) continue;
            if (!f.sourceIds().isEmpty() && (e.application() == null || !f.sourceIds().contains(e.application().sourceId()))) continue;
            port.deliver(e.target(), () -> {
                try {
                    s.listener().onEvent(e);
                } catch (RuntimeException x) {
                    log.accept("a listener of " + s.caller() + " threw " + x);
                }
            });
        }
    }

    synchronized String subscribe(String caller, EventFilter f, BuffEffects.Listener l) {
        String id = "sub-" + nextSub++;
        subs.put(id, new Sub(caller, f == null ? EventFilter.all() : f, l));
        return id;
    }

    synchronized void unsubscribe(String id) {
        subs.remove(id);
    }

    // --------------------------------------------------------------------------------------- persistence

    /** The ledger as JSON, for BuffEffects' own server data (api.md section 8). */
    public synchronized String save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "buffeffects.ledger");
        m.put("version", 1L);
        m.put("next_id", nextId);
        m.put("seq", seq);
        m.put("now_ms", nowMs);
        List<Object> apps = new ArrayList<>();
        for (List<Application> l : byTarget.values()) for (Application a : l) apps.add(a.toMap());
        m.put("applications", apps);
        Map<String, Object> im = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : immuneUntil.entrySet()) if (e.getValue() > nowMs) im.put(e.getKey(), e.getValue());
        m.put("immune_until", im);
        Map<String, Object> dr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : drHistory.entrySet()) {
            List<Object> recent = new ArrayList<>();
            for (long t : e.getValue()) if (nowMs - t < DR_WINDOW_MS) recent.add(t);
            if (!recent.isEmpty()) dr.put(e.getKey(), recent);
        }
        m.put("diminishing", dr);
        if (!messages.said.isEmpty()) m.put("said_once", new ArrayList<Object>(messages.said));
        return Json.write(m, true);
    }

    /**
     * Restores a saved ledger, before targets load. Every application comes back paused and unshown; the
     * first tick that finds its target live shows it again, with the same application id.
     */
    public synchronized void restore(String text) {
        Map<?, ?> m = (Map<?, ?>) Json.parse(text);
        byTarget.clear();
        nextId = ((Number) m.get("next_id")).longValue();
        seq = ((Number) m.get("seq")).longValue();
        nowMs = ((Number) m.get("now_ms")).longValue();
        for (Object o : (List<?>) m.get("applications")) {
            Application a = Application.fromMap((Map<?, ?>) o);
            byTarget.computeIfAbsent(a.target, k -> new ArrayList<>()).add(a);
        }
        immuneUntil.clear();
        if (m.get("immune_until") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("immune_until")).entrySet()) immuneUntil.put((String) e.getKey(), ((Number) e.getValue()).longValue());
        }
        messages.said.clear();
        if (m.get("said_once") instanceof List) for (Object o : (List<?>) m.get("said_once")) messages.said.add((String) o);
        drHistory.clear();
        if (m.get("diminishing") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("diminishing")).entrySet()) {
                List<Long> l = new ArrayList<>();
                for (Object t : (List<?>) e.getValue()) l.add(((Number) t).longValue());
                drHistory.put((String) e.getKey(), l);
            }
        }
        for (Iterator<Map.Entry<String, List<Application>>> it = byTarget.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Application>> e = it.next();
            e.getValue().removeIf(a -> defOf(a.effect) == null && !isNative(a.effect));
            if (e.getValue().isEmpty()) it.remove();
        }
        for (String t : new ArrayList<>(byTarget.keySet())) recomputeQuiet(t);
    }

    /** Re-decides categories and the slot without telling the game (nothing is shown yet). */
    private void recomputeQuiet(String target) {
        Set<Application> before = new HashSet<>();
        for (Application a : byTarget.getOrDefault(target, List.of())) if (a.shown) before.add(a);
        if (before.isEmpty()) {
            List<Application> apps = byTarget.get(target);
            Map<String, Application> best = new HashMap<>();
            for (Application a : apps) {
                EffectDef d = defOf(a.effect);
                if (d == null || d.stackingCategory() == null) continue;
                Application b = best.get(d.stackingCategory());
                double s = a.steps == null ? 0 : a.steps;
                if (b == null || s > (b.steps == null ? 0 : b.steps) || (s == (b.steps == null ? 0 : b.steps) && a.seq < b.seq)) best.put(d.stackingCategory(), a);
            }
            for (Application a : apps) {
                EffectDef d = defOf(a.effect);
                a.categoryDormant = d != null && d.stackingCategory() != null && !best.get(d.stackingCategory()).effect.equals(a.effect);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- handle

    /** The caller-bound API. */
    final class Handle implements BuffEffects {
        private final String caller;

        Handle(String caller) {
            this.caller = caller;
        }

        public String apiVersion() {
            return API_VERSION;
        }

        public String caller() {
            return caller;
        }

        public ApplyResult apply(String target, String effect, ApplyOptions options) {
            return Engine.this.apply(caller, target, effect, options, false);
        }

        public ApplyResult check(String target, String effect, ApplyOptions options) {
            return Engine.this.apply(caller, target, effect, options, true);
        }

        public RemoveResult remove(String target, Selector selector) {
            return Engine.this.remove(caller, target, selector);
        }

        public RemoveResult cure(String target, CureRequest cure) {
            return Engine.this.cure(caller, target, cure);
        }

        public List<ActiveApplication> active(String target) {
            return Engine.this.active(target);
        }

        public Boolean has(String target, String effect, String sourceId) {
            return Engine.this.has(target, effect, sourceId);
        }

        public int stacks(String target, String effect) {
            return Engine.this.stacks(target, effect);
        }

        public EffectInfo describe(String effect) {
            return Engine.this.describe(effect);
        }

        public List<String> list(ListFilter filter) {
            return Engine.this.list(filter == null ? ListFilter.all() : filter);
        }

        public String subscribe(EventFilter filter, Listener listener) {
            return Engine.this.subscribe(caller, filter, listener);
        }

        public void unsubscribe(String subscriptionId) {
            Engine.this.unsubscribe(subscriptionId);
        }
    }
}
