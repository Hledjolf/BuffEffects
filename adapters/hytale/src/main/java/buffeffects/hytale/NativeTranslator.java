package buffeffects.hytale;

import buffeffects.core.format.EffectDef;
import buffeffects.core.format.NativeEffect;
import buffeffects.core.format.Part;
import buffeffects.core.format.Vocabulary;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hytale's own effects in BuffEffects' terms (effects.md section 9.1). Two steps:
 * <ol>
 * <li>{@link #toJson} reads a loaded {@code EntityEffect} into the asset's own JSON shape (its getters,
 * and its protected fields by reflection), so other mods' changes to it count;</li>
 * <li>{@link #translate} turns that shape into a {@link NativeEffect}. The same step reads the reference
 * {@code effects.json} in the tests.</li>
 * </ol>
 * What the game does, from its bytecode (0.7.6, {@code ActiveEntityEffect.calculateCyclesToRun}): an
 * effect's {@code DamageCalculator} and {@code StatModifiers} run every {@code DamageCalculatorCooldown}
 * seconds, or once when the cooldown is 0. So with a cooldown they're {@code damage_over_time} and
 * {@code regen}; without, {@code hit} and {@code instant}.
 */
final class NativeTranslator {
    private NativeTranslator() {
    }

    /** Fields that are bookkeeping, or the game's own business (ApplyConditions run natively): never listed. */
    static final Set<String> QUIET = Set.of("$Comment", "Parent", "Name", "Duration", "Infinite", "Debuff", "OverlapBehavior",
            "RemovalBehavior", "DamageCalculatorCooldown", "ValueType", "StatusEffectIcon", "DeathMessageKey", "ApplyConditions",
            "Tags", "DamageEffects", "StatModifierEffects", "LocalRemovalSoundEventId", "WorldRemovalSoundEventId");

    static final Map<String, String> ACTIONS = Map.of("Primary", "attack", "Secondary", "secondary", "Ability1", "ability1",
            "Ability2", "ability2", "Ability3", "ability3", "Use", "use", "Dodge", "dodge", "Pickup", "pickup");

    // ------------------------------------------------------------------------------------- translating

    static NativeEffect translate(String id, Map<?, ?> e, Set<String> extraCauses) {
        List<Part> parts = new ArrayList<>();
        List<String> untranslated = new ArrayList<>();
        Map<String, Object> look = new LinkedHashMap<>();
        boolean infinite = Boolean.TRUE.equals(e.get("Infinite"));
        double duration = num(e.get("Duration"), 0);
        double cooldown = num(e.get("DamageCalculatorCooldown"), 0);
        boolean percent = "percent".equalsIgnoreCase(String.valueOf(e.get("ValueType")));
        for (Map.Entry<?, ?> f : e.entrySet()) {
            String k = String.valueOf(f.getKey());
            Object v = f.getValue();
            if (QUIET.contains(k)) continue;
            switch (k) {
                case "DamageCalculator": {
                    Map<?, ?> base = map(map(v).get("BaseDamage"));
                    for (Map.Entry<?, ?> d : base.entrySet()) {
                        String cause = cause(String.valueOf(d.getKey()), extraCauses);
                        double amt = num(d.getValue(), 0);
                        if (amt <= 0) continue;
                        parts.add(cooldown > 0 ? Part.of("damage_over_time", "cause", cause, "amount", bd(amt), "interval", bd(cooldown))
                                : Part.of("hit", "cause", cause, "amount", bd(amt)));
                    }
                    break;
                }
                case "StatModifiers":
                    for (Map.Entry<?, ?> s : map(v).entrySet()) {
                        String stat = stat(String.valueOf(s.getKey()));
                        double amt = num(s.getValue(), 0);
                        String unit = percent ? "percent" : "absolute";
                        parts.add(cooldown > 0 ? Part.of("regen", "stat", stat, "amount", bd(amt), "unit", unit, "interval", bd(cooldown))
                                : Part.of("instant", "stat", stat, "amount", bd(amt), "unit", unit));
                    }
                    break;
                case "RawStatModifiers":
                    for (Map.Entry<?, ?> s : map(v).entrySet()) {
                        for (Object o : list(s.getValue())) {
                            Map<?, ?> m = map(o);
                            String target = String.valueOf(m.get("Target"));
                            String calc = String.valueOf(m.get("CalculationType"));
                            double amt = num(m.get("Amount"), 0);
                            if (!"max".equalsIgnoreCase(target)) {
                                untranslated.add("RawStatModifiers." + s.getKey() + " (" + target + ")");
                                continue;
                            }
                            boolean mul = "multiplicative".equalsIgnoreCase(calc);
                            parts.add(Part.of("stat", "stat", stat(String.valueOf(s.getKey())), "op", mul ? "multiply" : "add", "amount", bd(amt)));
                        }
                    }
                    break;
                case "DamageResistance":
                case "OutgoingDamage":
                    for (Map.Entry<?, ?> c : map(v).entrySet()) {
                        String cause = cause(String.valueOf(c.getKey()), extraCauses);
                        for (Object o : list(c.getValue())) {
                            Map<?, ?> m = map(o);
                            double amt = num(m.get("Amount"), 0);
                            if (!"percent".equalsIgnoreCase(String.valueOf(m.get("CalculationType")))) {
                                String field = k + "." + c.getKey() + " (" + m.get("CalculationType") + ")";
                                untranslated.add(field);
                                parts.add(Part.of(NativeEffect.UNTRANSLATED, "field", field));
                                continue;
                            }
                            parts.add(k.equals("DamageResistance") ? Part.of("resist", "cause", cause, "amount", bd(amt))
                                    : Part.of("outgoing", "cause", cause, "multiplier", bd(1 + amt)));
                        }
                    }
                    break;
                case "Invulnerable":
                    if (Boolean.TRUE.equals(v)) parts.add(Part.of("invulnerable"));
                    break;
                case "ModelChange":
                    break; // the binding's shape, below
                case "ApplicationEffects":
                    application(map(v), parts, look, untranslated);
                    break;
                default:
                    untranslated.add(k);
                    break;
            }
        }
        String icon = e.get("StatusEffectIcon") instanceof String ? (String) e.get("StatusEffectIcon") : null;
        String shape = e.get("ModelChange") instanceof String ? (String) e.get("ModelChange") : null;
        Double seconds = infinite ? null : duration;
        boolean harmful = Boolean.TRUE.equals(e.get("Debuff")) || NativeEffect.anyHarmful(parts);
        return new NativeEffect("hytale", id, seconds, harmful, reapply(e.get("OverlapBehavior")), parts, icon, shape, look, untranslated);
    }

    private static void application(Map<?, ?> a, List<Part> parts, Map<String, Object> look, List<String> untranslated) {
        double speed = num(a.get("HorizontalSpeedMultiplier"), 1);
        Map<?, ?> move = map(a.get("MovementEffects"));
        speed *= num(move.get("SpeedMultiplier"), 1);
        if (speed != 1) parts.add(Part.of("speed", "multiplier", bd(Math.max(0, speed))));
        double knock = num(a.get("KnockbackMultiplier"), 1);
        if (knock != 1) parts.add(Part.of("knockback", "multiplier", bd(Math.max(0, knock))));
        Map<String, Object> lock = new LinkedHashMap<>();
        boolean all = Boolean.TRUE.equals(move.get("DisableAll"));
        if (all) lock.put("all", true); // the game sets every other flag with it
        for (Map.Entry<?, ?> m : move.entrySet()) {
            String k = String.valueOf(m.getKey());
            if (all || k.equals("SpeedMultiplier") || !Boolean.TRUE.equals(m.getValue())) continue;
            switch (k) {
                case "DisableAll" -> lock.put("all", true);
                case "DisableJump" -> lock.put("jump", true);
                case "DisableSprint" -> lock.put("sprint", true);
                case "DisableCrouch" -> lock.put("crouch", true);
                default -> untranslated.add("ApplicationEffects.MovementEffects." + k);
            }
        }
        if (!lock.isEmpty()) {
            List<Object> kv = new ArrayList<>();
            for (Map.Entry<String, Object> l : lock.entrySet()) {
                kv.add(l.getKey());
                kv.add(l.getValue());
            }
            parts.add(Part.of("lock_movement", kv.toArray()));
        }
        List<String> actions = new ArrayList<>();
        for (Object o : list(map(a.get("AbilityEffects")).get("Disabled"))) {
            String act = ACTIONS.get(String.valueOf(o));
            if (act == null) untranslated.add("ApplicationEffects.AbilityEffects.Disabled." + o);
            else if (!actions.contains(act)) actions.add(act);
        }
        if (!actions.isEmpty()) parts.add(Part.of("disable_actions", "actions", actions));
        for (Map.Entry<?, ?> f : a.entrySet()) {
            String k = String.valueOf(f.getKey());
            Object v = f.getValue();
            switch (k) {
                case "HorizontalSpeedMultiplier", "KnockbackMultiplier", "MovementEffects", "AbilityEffects" -> {
                }
                case "EntityTopTint" -> look.put("tint_top", String.valueOf(v).toLowerCase(Locale.ROOT));
                case "EntityBottomTint" -> look.put("tint_bottom", String.valueOf(v).toLowerCase(Locale.ROOT));
                case "ModelVFXId" -> look.put("model_vfx", v);
                case "ScreenEffect" -> look.put("screen_effect", v);
                case "EntityAnimationId" -> look.put("animation", v);
                case "Particles" -> {
                    List<Object> ids = new ArrayList<>();
                    for (Object o : list(v)) if (map(o).get("SystemId") != null) ids.add(map(o).get("SystemId"));
                    if (!ids.isEmpty()) look.put("particles", ids);
                }
                default -> untranslated.add("ApplicationEffects." + k);
            }
        }
    }

    static EffectDef.Reapply reapply(Object overlap) {
        String o = overlap == null ? "" : String.valueOf(overlap).toLowerCase(Locale.ROOT);
        return switch (o) {
            case "extend" -> EffectDef.Reapply.EXTEND;
            case "ignore" -> EffectDef.Reapply.IGNORE;
            case "overwrite" -> EffectDef.Reapply.REFRESH;
            default -> EffectDef.Reapply.IGNORE; // the game's default when the asset doesn't say (0.7.6, read live)
        };
    }

    /** A DamageCause id as a neutral cause where one matches it ignoring case, else {@code hytale:<id>}. */
    static String cause(String id, Set<String> extra) {
        String n = id.toLowerCase(Locale.ROOT);
        return Vocabulary.isNeutralCause(n, extra) ? n : "hytale:" + id;
    }

    static String stat(String id) {
        String n = id.toLowerCase(Locale.ROOT);
        return Vocabulary.STATS.contains(n) ? n : "hytale:" + id;
    }

    // ----------------------------------------------------------------------------- reading the asset

    /**
     * A loaded effect asset in its JSON shape: only what differs from the game's defaults, as an asset
     * file would say it. Fields that can't be read are left out (and so aren't translated).
     */
    static Map<String, Object> toJson(Object effect) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object dur = get(effect, "duration");
        if (dur instanceof Number) m.put("Duration", ((Number) dur).doubleValue());
        putTrue(m, "Infinite", get(effect, "infinite"));
        putTrue(m, "Debuff", get(effect, "debuff"));
        putTrue(m, "Invulnerable", get(effect, "invulnerable"));
        Object overlap = get(effect, "overlapBehavior");
        if (overlap != null) m.put("OverlapBehavior", String.valueOf(overlap));
        Object vt = get(effect, "valueType");
        if (vt != null) m.put("ValueType", String.valueOf(vt));
        putString(m, "StatusEffectIcon", get(effect, "statusEffectIcon"));
        putString(m, "ModelChange", get(effect, "modelChange"));
        if (get(effect, "modelOverride") != null) m.put("ModelOverride", true);
        Object cd = get(effect, "damageCalculatorCooldown");
        if (cd instanceof Number && ((Number) cd).doubleValue() > 0) m.put("DamageCalculatorCooldown", ((Number) cd).doubleValue());
        Object calc = get(effect, "damageCalculator");
        if (calc != null) {
            Map<String, Object> base = floats(get(calc, "baseDamageRaw"));
            if (!base.isEmpty()) m.put("DamageCalculator", Map.of("BaseDamage", base));
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        Object es = get(effect, "entityStats");
        if (es instanceof Map) {
            for (Map.Entry<?, ?> s : ((Map<?, ?>) es).entrySet()) {
                String name = statName(s.getKey());
                if (name != null && s.getValue() instanceof Number) stats.put(name, ((Number) s.getValue()).doubleValue());
            }
        }
        stats.putAll(floats(get(effect, "unknownEntityStats")));
        if (!stats.isEmpty()) m.put("StatModifiers", stats);
        Map<String, Object> raw = modifiers(get(effect, "rawStatModifiers"), true);
        if (!raw.isEmpty()) m.put("RawStatModifiers", raw);
        Map<String, Object> res = modifiers(get(effect, "damageResistanceValuesRaw"), false);
        if (!res.isEmpty()) m.put("DamageResistance", res);
        Map<String, Object> out = modifiers(get(effect, "outgoingDamageValuesRaw"), false);
        if (!out.isEmpty()) m.put("OutgoingDamage", out);
        Object interactions = get(effect, "interactions");
        if (interactions != null) m.put("Interactions", true);
        Object ae = get(effect, "applicationEffects");
        if (ae != null) m.put("ApplicationEffects", applicationJson(ae));
        return m;
    }

    private static Map<String, Object> applicationJson(Object ae) {
        Map<String, Object> a = new LinkedHashMap<>();
        Object fresh = blank(ae.getClass());
        putColor(a, "EntityTopTint", get(ae, "entityTopTint"));
        putColor(a, "EntityBottomTint", get(ae, "entityBottomTint"));
        putString(a, "ModelVFXId", get(ae, "modelVFXId"));
        putString(a, "ScreenEffect", get(ae, "screenEffect"));
        putString(a, "EntityAnimationId", get(ae, "entityAnimationId"));
        putString(a, "WorldSoundEventId", get(ae, "soundEventIdWorld"));
        putString(a, "LocalSoundEventId", get(ae, "soundEventIdLocal"));
        putFloat(a, "HorizontalSpeedMultiplier", get(ae, "horizontalSpeedMultiplier"), fresh == null ? 1f : get(fresh, "horizontalSpeedMultiplier"));
        putFloat(a, "KnockbackMultiplier", get(ae, "knockbackMultiplier"), fresh == null ? 1f : get(fresh, "knockbackMultiplier"));
        Object particles = get(ae, "particles");
        if (particles instanceof Object[] && ((Object[]) particles).length > 0) {
            List<Object> ps = new ArrayList<>();
            for (Object p : (Object[]) particles) {
                Object sys = p == null ? null : get(p, "systemId");
                if (sys != null) ps.add(Map.of("SystemId", sys));
            }
            a.put("Particles", ps);
        }
        Object fp = get(ae, "firstPersonParticles");
        if (fp instanceof Object[] && ((Object[]) fp).length > 0) a.put("FirstPersonParticles", true);
        Object move = get(ae, "movementEffects");
        if (move != null) {
            Map<String, Object> mv = new LinkedHashMap<>();
            Object mfresh = blank(move.getClass());
            for (String f : List.of("disableAll", "disableJump", "disableSprint", "disableCrouch", "disableForward", "disableBackward",
                    "disableLeft", "disableRight")) {
                putTrue(mv, Character.toUpperCase(f.charAt(0)) + f.substring(1), get(move, f));
            }
            putFloat(mv, "SpeedMultiplier", get(move, "speedMultiplier"), mfresh == null ? 1f : get(mfresh, "speedMultiplier"));
            if (!mv.isEmpty()) a.put("MovementEffects", mv);
        }
        Object ab = get(ae, "abilityEffects");
        if (ab != null && get(ab, "disabled") instanceof Collection) {
            List<Object> d = new ArrayList<>();
            for (Object o : (Collection<?>) get(ab, "disabled")) d.add(String.valueOf(o));
            if (!d.isEmpty()) a.put("AbilityEffects", Map.of("Disabled", d));
        }
        return a;
    }

    private static Map<String, Object> modifiers(Object raw, boolean stat) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map)) return out;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (!(e.getValue() instanceof Object[])) continue;
            List<Object> mods = new ArrayList<>();
            for (Object mod : (Object[]) e.getValue()) {
                if (mod == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                Object amt = get(mod, "amount");
                m.put("Amount", amt instanceof Number ? ((Number) amt).doubleValue() : 0.0);
                m.put("CalculationType", String.valueOf(get(mod, "calculationType")));
                if (stat) m.put("Target", String.valueOf(get(mod, "target")));
                mods.add(m);
            }
            if (!mods.isEmpty()) out.put(String.valueOf(e.getKey()), mods);
        }
        return out;
    }

    private static String statName(Object index) {
        try {
            if (!(index instanceof Number)) return null;
            Object t = com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType.getAssetMap().getAsset(((Number) index).intValue());
            return t == null ? null : String.valueOf(get(t, "id"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------- reflection

    static Object get(Object o, String field) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // keep looking up the hierarchy
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** A default instance (the protected no-argument constructor), to tell defaults from set values. */
    static Object blank(Class<?> c) {
        try {
            var k = c.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> floats(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getValue() instanceof Number) out.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
            }
        }
        return out;
    }

    private static void putTrue(Map<String, Object> m, String k, Object v) {
        if (Boolean.TRUE.equals(v)) m.put(k, true);
    }

    private static void putString(Map<String, Object> m, String k, Object v) {
        if (v instanceof String && !((String) v).isEmpty()) m.put(k, v);
    }

    private static void putFloat(Map<String, Object> m, String k, Object v, Object dflt) {
        if (!(v instanceof Number)) return;
        double d = ((Number) v).doubleValue();
        double base = dflt instanceof Number ? ((Number) dflt).doubleValue() : 1.0;
        if (Math.abs(d - base) > 1e-6) m.put(k, d);
    }

    private static void putColor(Map<String, Object> m, String k, Object color) {
        if (color == null) return;
        Object r = get(color, "red"), g = get(color, "green"), b = get(color, "blue");
        if (!(r instanceof Byte) || !(g instanceof Byte) || !(b instanceof Byte)) return;
        m.put(k, String.format("#%02x%02x%02x", (Byte) r & 0xff, (Byte) g & 0xff, (Byte) b & 0xff));
    }

    // ------------------------------------------------------------------------------------ JSON helpers

    static Map<?, ?> map(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : Map.of();
    }

    static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    static double num(Object o, double fallback) {
        return o instanceof Number ? ((Number) o).doubleValue() : fallback;
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
