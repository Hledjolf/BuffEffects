package buffeffects.core.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A game's own effect, translated into BuffEffects' terms by the adapter at load (effects.md section 9.1).
 * It's only a description: applying {@code hytale:<id>} still applies the game's own effect. It's what
 * {@code describe} returns, what the rater rates, and what the caps check when a mod's effect wraps it.
 *
 * @param seconds      the game's own duration; null when the game's effect is infinite
 * @param harmful      the game's own flag, or derived from the parts when the flag is off
 * @param parts        the translated parts. A field that would be rated but can't be translated comes as a
 *                     part of type {@code untranslated} (field {@code field}), which makes the rating
 *                     {@code unrated} with the reason {@code untranslated:<field>}
 * @param look         {@code look.while_active} as the format writes it (tints, particles, model VFX...)
 * @param untranslated the game's fields the adapter can't translate, by name
 */
public record NativeEffect(String game, String id, Double seconds, boolean harmful, EffectDef.Reapply reapply,
                           List<Part> parts, String icon, String shape, Map<String, Object> look,
                           List<String> untranslated) {
    /** The part type that marks an untranslated, rated field. */
    public static final String UNTRANSLATED = "untranslated";

    public NativeEffect {
        parts = List.copyOf(parts);
        look = Map.copyOf(look);
        untranslated = List.copyOf(untranslated);
    }

    /** The parts as BuffEffects' own, without the untranslated markers. */
    public List<Part> translatedParts() {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) if (!p.type().equals(UNTRANSLATED)) out.add(p);
        return out;
    }

    /** Whether a part is harmful to its bearer (effects.md section 9.1). */
    public static boolean harmfulPart(Part p) {
        switch (p.type()) {
            case "damage_over_time":
            case "hit":
            case "lock_movement":
            case "disable_actions":
                return true;
            case "stat":
                return "multiply".equals(p.str("op")) ? p.num("amount", 1) < 1 : p.num("amount", 0) < 0;
            case "instant":
            case "regen":
            case "resist":
                return p.num("amount", 0) < 0;
            case "outgoing":
            case "speed":
                return p.num("multiplier", 1) < 1;
            default:
                return false;
        }
    }

    /** Derived {@code harmful}: true if any part is harmful to the bearer. */
    public static boolean anyHarmful(List<Part> parts) {
        for (Part p : parts) if (harmfulPart(p)) return true;
        return false;
    }

    /** The translation as an effects-format definition, for {@code describe} (api.md section 4). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", game + ":" + id);
        m.put("harmful", harmful);
        m.put("duration", seconds == null ? Map.of("infinite", true) : Map.of("seconds", seconds));
        m.put("reapply", reapply.name().toLowerCase(java.util.Locale.ROOT));
        Map<String, Object> dur = new LinkedHashMap<>();
        dur.put("min", seconds == null ? 1.0 : Math.min(1.0, seconds));
        dur.put("max", 86_400.0);
        dur.put("infinite", true);
        m.put("caller_may_set", Map.of("duration", dur));
        m.put("parts", EffectWriter.parts(translatedParts()));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("native", List.of(id));
        if (icon != null) b.put("icon", icon);
        if (!look.isEmpty()) b.put("look", Map.of("while_active", look));
        if (shape != null) b.put("shape", shape);
        m.put("bindings", Map.of(game, b));
        if (!untranslated.isEmpty()) m.put("untranslated", untranslated);
        return m;
    }
}
