package buffeffects.core.engine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BuffEffects' server settings (commands-settings.md section 2), as Mod Settings Manager edits them in
 * {@code config.json}. Mutable, read under the engine's lock; {@link #fromMap} and {@link #toMap} are the
 * file's shape.
 */
public final class Settings {
    // Power
    public Set<String> optedInEffects = new HashSet<>();
    public Set<String> optedInMods = new HashSet<>();
    // Caps: resistance
    public double maxResistPlayers = 0.9;
    public double maxResistNpcs = 0.9;
    public boolean capAtZoneVanillaMax = true;
    // Caps: flags
    public double invulnerableMaxSeconds = 5;
    public double invulnerableImmunitySeconds = 30;
    public double stunMaxSeconds = 3;
    public double rootMaxSeconds = 5;
    public boolean diminishingReturns = true;
    // Caps: rating
    public double maxStepsPerEffect = 2;
    public double maxStepsTotal = 3;
    public boolean separateNpcValues = false;
    public double maxStepsPerEffectNpcs = 2;
    public double maxStepsTotalNpcs = 3;
    // Regeneration
    public boolean capRegen = true;
    // Messages
    public boolean messagesEnabled = true;
    /** The generic wear-off line for effects without their own {@code expire} message. */
    public boolean genericWearOff = true;
    public double messagesRadius = 24;
    // Mods: a mod's effects on or off (absent = on)
    public Set<String> disabledMods = new HashSet<>();
    // Log
    public int logDays = 30;

    /** The config file's keys, in the order the sidecar shows them. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("OptedInEffects", sorted(optedInEffects));
        m.put("OptedInMods", sorted(optedInMods));
        m.put("MaxResistPlayers", maxResistPlayers);
        m.put("MaxResistNpcs", maxResistNpcs);
        m.put("CapAtZoneVanillaMax", capAtZoneVanillaMax);
        m.put("InvulnerableMaxSeconds", invulnerableMaxSeconds);
        m.put("InvulnerableImmunitySeconds", invulnerableImmunitySeconds);
        m.put("StunMaxSeconds", stunMaxSeconds);
        m.put("RootMaxSeconds", rootMaxSeconds);
        m.put("DiminishingReturns", diminishingReturns);
        m.put("MaxStepsPerEffect", maxStepsPerEffect);
        m.put("MaxStepsTotal", maxStepsTotal);
        m.put("SeparateNpcValues", separateNpcValues);
        m.put("MaxStepsPerEffectNpcs", maxStepsPerEffectNpcs);
        m.put("MaxStepsTotalNpcs", maxStepsTotalNpcs);
        m.put("CapRegen", capRegen);
        m.put("MessagesEnabled", messagesEnabled);
        m.put("GenericWearOff", genericWearOff);
        m.put("MessagesRadius", messagesRadius);
        m.put("DisabledMods", sorted(disabledMods));
        m.put("LogDays", (long) logDays);
        return m;
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }

    /** Reads the config file's map; missing or wrongly typed keys keep their defaults. */
    public static Settings fromMap(Map<?, ?> m) {
        Settings s = new Settings();
        s.optedInEffects = strings(m.get("OptedInEffects"));
        s.optedInMods = strings(m.get("OptedInMods"));
        s.maxResistPlayers = num(m, "MaxResistPlayers", s.maxResistPlayers);
        s.maxResistNpcs = num(m, "MaxResistNpcs", s.maxResistNpcs);
        s.capAtZoneVanillaMax = bool(m, "CapAtZoneVanillaMax", s.capAtZoneVanillaMax);
        s.invulnerableMaxSeconds = num(m, "InvulnerableMaxSeconds", s.invulnerableMaxSeconds);
        s.invulnerableImmunitySeconds = num(m, "InvulnerableImmunitySeconds", s.invulnerableImmunitySeconds);
        s.stunMaxSeconds = num(m, "StunMaxSeconds", s.stunMaxSeconds);
        s.rootMaxSeconds = num(m, "RootMaxSeconds", s.rootMaxSeconds);
        s.diminishingReturns = bool(m, "DiminishingReturns", s.diminishingReturns);
        s.maxStepsPerEffect = num(m, "MaxStepsPerEffect", s.maxStepsPerEffect);
        s.maxStepsTotal = num(m, "MaxStepsTotal", s.maxStepsTotal);
        s.separateNpcValues = bool(m, "SeparateNpcValues", s.separateNpcValues);
        s.maxStepsPerEffectNpcs = num(m, "MaxStepsPerEffectNpcs", s.maxStepsPerEffectNpcs);
        s.maxStepsTotalNpcs = num(m, "MaxStepsTotalNpcs", s.maxStepsTotalNpcs);
        s.capRegen = bool(m, "CapRegen", s.capRegen);
        s.messagesEnabled = bool(m, "MessagesEnabled", s.messagesEnabled);
        s.genericWearOff = bool(m, "GenericWearOff", s.genericWearOff);
        s.messagesRadius = num(m, "MessagesRadius", s.messagesRadius);
        s.disabledMods = strings(m.get("DisabledMods"));
        s.logDays = (int) num(m, "LogDays", s.logDays);
        return s;
    }

    private static double num(Map<?, ?> m, String k, double d) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : d;
    }

    private static boolean bool(Map<?, ?> m, String k, boolean d) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : d;
    }

    private static Set<String> strings(Object v) {
        Set<String> out = new HashSet<>();
        if (v instanceof List) for (Object o : (List<?>) v) if (o instanceof String) out.add((String) o);
        return out;
    }

    public double stepsPerEffect(TargetInfo.Kind kind) {
        return separateNpcValues && kind == TargetInfo.Kind.NPC ? maxStepsPerEffectNpcs : maxStepsPerEffect;
    }

    public double stepsTotal(TargetInfo.Kind kind) {
        return separateNpcValues && kind == TargetInfo.Kind.NPC ? maxStepsTotalNpcs : maxStepsTotal;
    }

    public double maxResist(TargetInfo.Kind kind) {
        return kind == TargetInfo.Kind.NPC ? maxResistNpcs : maxResistPlayers;
    }
}
