package buffeffects.hytale;

import buffeffects.core.format.Binding;
import buffeffects.core.format.DurationSpec;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.Part;
import buffeffects.core.format.Vocabulary;
import buffeffects.core.json.Json;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.modelvfx.config.ModelVFX;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bson.BsonDocument;

/**
 * Turns effect definitions into Hytale assets and registers them live (P1's method:
 * {@code AssetStore.decode} then {@code loadAssets}). Ids are deterministic, so a restart registers the
 * same ones again:
 * <ul>
 * <li>{@code BFX_<mod id>_<id>} (sanitized: every character but letters, digits and {@code _} becomes
 * {@code _}) the effect itself, a stable name NPC roles can filter on (effects.md section 7), plus
 * {@code __s<percent>} for a strength other than 1, {@code __n<k>} for stack k (one id can't stack on
 * Hytale), and {@code __ns} without its shape (while another holds the model slot);</li>
 * <li>{@code ..._OnApply}, {@code ..._OnRemove} the one-shot looks;</li>
 * <li>{@code BFX_Heal_I_<stat>_<P|A><amount>} instant heals and {@code BFX_Heal_R_<stat>_<P|A><amount>_I<ms>}
 * regeneration (quantized: whole percents, intervals in 0.25 s steps);</li>
 * <li>{@code BFX_SeeThrough_<percent>} the see-through model VFX.</li>
 * </ul>
 * Must not run on a world thread (asset loading may wait on it).
 */
final class Assets {
    static final String PACK_NAME = "Hledjolf:BuffEffects";
    /** Each id this session registered, with the JSON it was registered from: an edited effect keeps its id. */
    private final Map<String, String> registered = new ConcurrentHashMap<>();
    private final Consumer<String> log;

    Assets(Consumer<String> log) {
        this.log = log;
    }

    static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "_");
    }

    static String baseId(EffectDef d) {
        return "BFX_" + sanitize(d.fullId());
    }

    /** The main asset's id for a stack slot, a (quantized) strength and whether the shape is held back. */
    static String mainId(EffectDef d, int slot, double strength, boolean holdShape) {
        StringBuilder b = new StringBuilder(baseId(d));
        long pct = Math.round(strength * 100);
        if (pct != 100) b.append("__s").append(pct);
        if (slot > 0) b.append("__n").append(slot);
        Binding bd = d.binding("hytale");
        if (holdShape && bd != null && bd.shape() != null) b.append("__ns");
        return b.toString();
    }

    static String packKey() {
        var mod = AssetModule.get();
        for (var p : mod.getAssetPacks()) if (p.getName().equalsIgnoreCase(PACK_NAME)) return p.getName();
        for (var p : mod.getAssetPacks()) if (p.getName().endsWith("BuffEffects")) return p.getName();
        return mod.getBaseAssetPack().getName();
    }

    boolean has(String id) {
        return registered.containsKey(id) || EntityEffect.getAssetMap().getAsset(id) != null;
    }

    /**
     * Registers {@code json} as {@code id} unless this session already registered exactly that. Ids come from
     * the effect's name, not its content, so a Save that changes an effect's look registers the same id again
     * (the game then updates the effect in place, also on entities that have it: P1b).
     */
    String ensure(String id, Map<String, Object> json) {
        if (Json.write(json, false).equals(registered.get(id))) return id;
        return registerEffect(id, json) ? id : null;
    }

    /** Registers one effect asset from its JSON. Returns false (and logs) if the game refused it. */
    boolean registerEffect(String id, Map<String, Object> json) {
        try {
            var st = EntityEffect.getAssetStore();
            EntityEffect e = st.decode(packKey(), id, BsonDocument.parse(Json.write(json, false)));
            var res = st.loadAssets(packKey(), List.of(e));
            if (!res.getFailedToLoadKeys().isEmpty()) {
                log.accept("asset " + id + " failed to load: " + res.getFailedToLoadKeys());
                return false;
            }
            registered.put(id, Json.write(json, false));
            return true;
        } catch (RuntimeException x) {
            log.accept("asset " + id + " refused: " + x);
            return false;
        }
    }

    String seeThroughVfx(double share) {
        long pct = Math.max(1, Math.min(99, Math.round(share * 100)));
        String id = "BFX_SeeThrough_" + pct;
        if (registered.containsKey(id) || ModelVFX.getAssetMap().getAsset(id) != null) return id;
        try {
            var st = ModelVFX.getAssetStore();
            String json = "{\"SwitchTo\": \"Transparency\", \"Opacity\": " + BigDecimal.valueOf((100 - pct) / 100.0).toPlainString() + "}";
            ModelVFX v = st.decode(packKey(), id, BsonDocument.parse(json));
            st.loadAssets(packKey(), List.of(v));
            registered.put(id, json);
        } catch (RuntimeException x) {
            log.accept("model VFX " + id + " refused: " + x);
        }
        return id;
    }

    // --------------------------------------------------------------------------------------- the effect

    /** Whether an effect needs a main asset (anything besides natives and lines). */
    static boolean needsMain(EffectDef d) {
        Binding b = d.binding("hytale");
        for (Part p : d.partsOn("hytale")) {
            if (p.type().equals("stat") && "multiply".equals(p.str("op"))) continue; // one combined modifier per target (HytalePort)
            if (!List.of("message", "intangible", "instant", "regen", "hit").contains(p.type())) return true;
        }
        return b != null && (b.icon() != null || !b.whileActive().isEmpty() || b.shape() != null);
    }

    /** Registers the main asset (and the one-shot looks) for this variant, if it isn't already. */
    String ensureMain(EffectDef d, int slot, double strength, boolean holdShape) {
        return ensure(mainId(d, slot, strength, holdShape), mainJson(d, strength, holdShape));
    }

    String ensureLook(EffectDef d, boolean apply) {
        Binding b = d.binding("hytale");
        Map<String, Object> look = b == null ? Map.of() : apply ? b.onApply() : b.onRemove();
        if (look.isEmpty()) return null;
        String id = baseId(d) + (apply ? "_OnApply" : "_OnRemove");
        Map<String, Object> ae = new LinkedHashMap<>();
        if (look.get("animation") != null) ae.put("EntityAnimationId", look.get("animation"));
        if (look.get("sound") != null) ae.put("WorldSoundEventId", look.get("sound"));
        particles(look, ae);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Duration", 1.0);
        m.put("OverlapBehavior", "Overwrite");
        m.put("ApplicationEffects", ae);
        return ensure(id, m);
    }

    static void particles(Map<String, Object> look, Map<String, Object> ae) {
        Object ps = look.get("particles");
        if (!(ps instanceof List)) return;
        List<Object> list = new ArrayList<>();
        for (Object p : (List<?>) ps) list.add(Map.of("SystemId", p));
        ae.put("Particles", list);
    }

    static String stat(String s) {
        if (s.startsWith("hytale:")) return s.substring(7);
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * The damage text colour of each cause BuffEffects adds to Hytale (effects.md 1.0, section 6), registered
     * as {@code BFX_<cause>} with no parent (P12's run-time route). Listed in {@code docs\hytale-notes.md}.
     */
    static final Map<String, String> ADDED_CAUSE_COLOURS = Map.of(
            "shadow", "#7A3CFF", "holy", "#FFE680", "arcane", "#4FA8FF", "mind", "#FF7AD9",
            "sonic", "#A8F0E8", "disease", "#9BBF3A", "chaos", "#E0305A");

    /** Registers the added causes these effects use, if they aren't yet. Before their effect assets. */
    void registerCauses(java.util.Collection<EffectDef> defs) {
        Set<String> want = new java.util.TreeSet<>();
        for (EffectDef d : defs) {
            for (Part p : d.partsOn("hytale")) {
                String c = p.str("cause");
                if (c == null) continue;
                for (String leaf : Vocabulary.leaves(c)) if (Vocabulary.ADDED_CAUSES.contains(leaf)) want.add(leaf);
            }
        }
        var map = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap();
        for (String c : want) {
            String id = "BFX_" + c;
            if (registered.containsKey(id) || map.getAsset(id) != null) continue;
            String json = "{\"DamageTextColor\": \"" + ADDED_CAUSE_COLOURS.get(c) + "\"}";
            try {
                var st = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetStore();
                var dc = st.decode(packKey(), id, BsonDocument.parse(json));
                var res = st.loadAssets(packKey(), List.of(dc));
                if (!res.getFailedToLoadKeys().isEmpty()) {
                    log.accept("damage cause " + id + " failed to load: " + res.getFailedToLoadKeys());
                    continue;
                }
                registered.put(id, json);
                log.accept("registered damage cause " + id);
            } catch (RuntimeException x) {
                log.accept("damage cause " + id + " refused: " + x);
            }
        }
    }

    /** A neutral cause as the game's DamageCause id: the registered id that matches it ignoring case. */
    static String cause(String c) {
        if (c.startsWith("hytale:")) return c.substring(7);
        if (Vocabulary.ADDED_CAUSES.contains(c)) return "BFX_" + c;
        try {
            for (String id : com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getAssetMap().keySet()) {
                if (id.equalsIgnoreCase(c)) return id;
            }
        } catch (RuntimeException ignored) {
            // not loaded yet: fall back to the capitalized name
        }
        return c.substring(0, 1).toUpperCase(Locale.ROOT) + c.substring(1);
    }

    static final Map<String, String> ACTIONS = Map.of("attack", "Primary", "secondary", "Secondary", "ability1", "Ability1",
            "ability2", "Ability2", "ability3", "Ability3", "use", "Use", "dodge", "Dodge", "pickup", "Pickup");

    Map<String, Object> mainJson(EffectDef d, double strength, boolean holdShape) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> ae = new LinkedHashMap<>();
        Map<String, List<Object>> raw = new LinkedHashMap<>(), resist = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        Map<String, Object> dot = new LinkedHashMap<>();
        double cooldown = 0, speed = 1, knock = 1;
        boolean hasSpeed = false, hasKnock = false;
        Map<String, Object> move = new LinkedHashMap<>();
        List<Object> disabled = new ArrayList<>();
        for (Part p0 : d.partsOn("hytale")) {
            Part p = p0.scaled(strength);
            switch (p.type()) {
                case "stat":
                    // multiply: BuffEffects' summed bonuses, as one modifier per target and stat (HytalePort.setStatMultipliers)
                    if ("multiply".equals(p.str("op"))) break;
                    raw.computeIfAbsent(stat(p.str("stat")), k -> new ArrayList<>()).add(Map.of(
                            "Amount", p.num("amount", 0), "CalculationType", "multiply".equals(p.str("op")) ? "Multiplicative" : "Additive",
                            "Target", "Max"));
                    break;
                case "resist":
                    for (String leaf : Vocabulary.leaves(p.str("cause"))) {
                        resist.computeIfAbsent(cause(leaf), k -> new ArrayList<>()).add(Map.of("Amount", p.num("amount", 0), "CalculationType", "Percent"));
                    }
                    break;
                case "outgoing":
                    out.computeIfAbsent(cause(p.str("cause")), k -> new ArrayList<>()).add(Map.of("Amount", round(p.num("multiplier", 1) - 1), "CalculationType", "Percent"));
                    break;
                case "damage_over_time": {
                    double interval = p.num("interval", 1);
                    double amt = p.num("amount", 0);
                    if (cooldown == 0) cooldown = interval;
                    else amt = amt * cooldown / interval; // one cooldown per asset: rescale to the first part's
                    String c = cause(p.str("cause"));
                    dot.put(c, round(((Number) dot.getOrDefault(c, 0.0)).doubleValue() + amt));
                    break;
                }
                case "speed":
                    speed *= p.num("multiplier", 1);
                    hasSpeed = true;
                    break;
                case "knockback":
                    knock *= p.num("multiplier", 1);
                    hasKnock = true;
                    break;
                case "lock_movement":
                    if (p.bool("all", false)) move.put("DisableAll", true);
                    if (p.bool("jump", false)) move.put("DisableJump", true);
                    if (p.bool("sprint", false)) move.put("DisableSprint", true);
                    break;
                case "disable_actions":
                    for (String a : p.strings("actions")) {
                        String h = ACTIONS.get(a);
                        if (h != null && !disabled.contains(h)) disabled.add(h);
                    }
                    break;
                case "invulnerable":
                    m.put("Invulnerable", true);
                    break;
                default:
                    break; // intangible, instant, regen, hit, message: not in the main asset
            }
        }
        // The real length goes in at each addEffect. The asset's Duration is what the client gets as the
        // effect's full length, so it is the effect's default run time: after a reload or a respawn the
        // effect is re-added with only the time it has left, and the HUD can then show it part-used.
        DurationSpec run = d.duration();
        m.put("Duration", run != null && !run.infinite() && run.runMs() > 0 ? run.runMs() / 1000.0 : 1.0);
        m.put("OverlapBehavior", "Overwrite");
        if (d.harmful()) m.put("Debuff", true);
        Binding b = d.binding("hytale");
        if (b != null && b.icon() != null) m.put("StatusEffectIcon", b.icon());
        if (!raw.isEmpty()) m.put("RawStatModifiers", raw);
        if (!resist.isEmpty()) m.put("DamageResistance", resist);
        if (!out.isEmpty()) m.put("OutgoingDamage", out);
        if (!dot.isEmpty()) {
            m.put("DamageCalculatorCooldown", cooldown);
            m.put("DamageCalculator", Map.of("BaseDamage", dot));
        }
        if (b != null && b.shape() != null && !holdShape) m.put("ModelChange", b.shape());
        Map<String, Object> wa = b == null ? Map.of() : b.whileActive();
        if (wa.get("tint_top") != null) ae.put("EntityTopTint", wa.get("tint_top"));
        if (wa.get("tint_bottom") != null) ae.put("EntityBottomTint", wa.get("tint_bottom"));
        if (wa.get("model_vfx") != null) ae.put("ModelVFXId", wa.get("model_vfx"));
        if (wa.get("see_through") instanceof Number) ae.put("ModelVFXId", seeThroughVfx(((Number) wa.get("see_through")).doubleValue()));
        if (wa.get("screen_effect") != null) ae.put("ScreenEffect", wa.get("screen_effect"));
        if (wa.get("animation") != null) ae.put("EntityAnimationId", wa.get("animation"));
        particles(wa, ae);
        if (hasSpeed) ae.put("HorizontalSpeedMultiplier", round(speed));
        if (hasKnock) ae.put("KnockbackMultiplier", round(knock));
        if (!move.isEmpty()) ae.put("MovementEffects", move);
        if (!disabled.isEmpty()) ae.put("AbilityEffects", Map.of("Disabled", disabled));
        if (!ae.isEmpty()) m.put("ApplicationEffects", ae);
        return m;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ----------------------------------------------------------------------------------------- heals

    /** The heal variant for an {@code instant} or {@code regen} part at a strength; registered if needed. */
    String ensureHeal(Part p0, double strength) {
        Part p = p0.scaled(strength);
        boolean pct = "percent".equals(p.str("unit"));
        double amt = p.num("amount", 0);
        amt = pct ? Math.round(amt) : round(amt);
        String a = (pct ? "P" : "A") + BigDecimal.valueOf(amt).stripTrailingZeros().toPlainString().replace('.', 'd').replace('-', 'm');
        String stat = stat(p.str("stat"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("StatModifiers", Map.of(stat, amt));
        m.put("ValueType", pct ? "Percent" : "Absolute");
        m.put("OverlapBehavior", "Overwrite");
        String id;
        if (p.type().equals("instant")) {
            id = "BFX_Heal_I_" + sanitize(stat) + "_" + a;
            m.put("Duration", 0.1);
            m.put("DamageCalculatorCooldown", 0);
        } else {
            long ms = Math.max(250, Math.round(p.num("interval", 1) * 4) * 250);
            id = "BFX_Heal_R_" + sanitize(stat) + "_" + a + "_I" + ms;
            m.put("Duration", 1.0);
            m.put("DamageCalculatorCooldown", ms / 1000.0);
        }
        if (has(id)) return id;
        return registerEffect(id, m) ? id : null;
    }

    /** Registers everything an effect needs at strength 1 (startup and reload). */
    void registerAll(EffectDef d) {
        if (needsMain(d)) {
            ensureMain(d, 0, 1.0, false);
            Binding b = d.binding("hytale");
            if (b != null && b.shape() != null) ensureMain(d, 0, 1.0, true);
            for (int s = 1; s < d.maxStacks(); s++) ensureMain(d, s, 1.0, false);
        }
        ensureLook(d, true);
        ensureLook(d, false);
        for (Part p : d.partsOn("hytale")) if (p.type().equals("instant") || p.type().equals("regen")) ensureHeal(p, 1.0);
    }
}
