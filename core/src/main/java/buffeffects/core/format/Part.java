package buffeffects.core.format;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One part of an effect (effects.md section 6): a type, whether it's required, and its fields as read.
 * Kept generic, with typed getters, so the same class carries every part type and translates plainly.
 */
public record Part(String type, boolean required, Map<String, Object> fields) {
    /** Part types whose numeric amounts a caller's strength scales (effects.md section 10). */
    public static final List<String> SCALED_TYPES =
            List.of("stat", "instant", "regen", "damage_over_time", "resist", "outgoing", "hit");

    public Part {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Part of(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return new Part(type, true, m);
    }

    public String str(String name) {
        Object v = fields.get(name);
        return v instanceof String ? (String) v : null;
    }

    public double num(String name, double fallback) {
        Object v = fields.get(name);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public boolean bool(String name, boolean fallback) {
        Object v = fields.get(name);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    public List<String> strings(String name) {
        Object v = fields.get(name);
        List<String> out = new ArrayList<>();
        if (v instanceof List) for (Object o : (List<?>) v) if (o instanceof String) out.add((String) o);
        return out;
    }

    /** The field a caller's strength scales: {@code amount}, or {@code multiplier} for {@code outgoing}. */
    public String scaledField() {
        if (!SCALED_TYPES.contains(type)) return null;
        return type.equals("outgoing") ? "multiplier" : "amount";
    }

    /**
     * This part at a strength factor. Amounts scale; an {@code outgoing} multiplier scales its bonus
     * (1.25 at strength 2 is 1.5). Other parts come back unchanged.
     */
    public Part scaled(double strength) {
        String f = scaledField();
        if (f == null || strength == 1.0) return this;
        Map<String, Object> m = new LinkedHashMap<>(fields);
        double v = num(f, 0);
        double nv = type.equals("outgoing") ? 1 + (v - 1) * strength : v * strength;
        m.put(f, BigDecimal.valueOf(nv).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros());
        return new Part(type, required, m);
    }
}
