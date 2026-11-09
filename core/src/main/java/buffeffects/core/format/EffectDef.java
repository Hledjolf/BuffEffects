package buffeffects.core.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One loaded effect definition (effects.md section 3), with its ids qualified: {@code fullId} is
 * {@code <mod id>/<id>}. References in {@code immuneIf} are qualified too ({@code <mod id>/<id>} for an
 * ecosystem effect, {@code <game>:<id>} for a game's own).
 */
public record EffectDef(
        String mod, String id, String name, String description, boolean harmful, String category,
        List<String> tags, DurationSpec duration, Reapply reapply, String stackingCategory, int maxStacks, List<String> stackNames,
        List<Part> parts, Map<String, Binding> bindings, Object endsWhen, List<String> immuneIf,
        List<String> cureTags, OnDeath onDeath, Bounds durationBounds, Bounds strengthBounds,
        Double declaredPower, String permission) {

    public enum Reapply { REFRESH, EXTEND, IGNORE, STACK }

    public enum OnDeath { CLEAR, KEEP, RESTART }

    /** Inclusive bounds in seconds (duration) or as a factor (strength). */
    public record Bounds(double min, double max) {
        public boolean contains(double v) {
            return v >= min - 1e-9 && v <= max + 1e-9;
        }
    }

    public EffectDef {
        tags = List.copyOf(tags);
        stackNames = stackNames == null ? List.of() : List.copyOf(stackNames);
        parts = List.copyOf(parts);
        bindings = Map.copyOf(bindings);
        immuneIf = List.copyOf(immuneIf);
        cureTags = List.copyOf(cureTags);
    }

    public String fullId() {
        return mod + "/" + id;
    }

    /**
     * The name to show for {@code stacks} stacks (effects.md section 5): {@code stacking.names}' entry when
     * there is one (plain text or a {@code lang:} key), else the name, with " ×n" above one stack.
     */
    public String displayName(int stacks) {
        if (!stackNames.isEmpty() && stacks >= 1) return stackNames.get(Math.min(stacks, stackNames.size()) - 1);
        String base = name == null ? id : name;
        return stacks > 1 ? base + " ×" + stacks : base;
    }

    public Binding binding(String game) {
        return bindings.get(game);
    }

    /** The core parts plus the parts of {@code game}'s binding: what applies, and what's rated, there. */
    public List<Part> partsOn(String game) {
        Binding b = bindings.get(game);
        if (b == null || b.parts().isEmpty()) return parts;
        List<Part> all = new ArrayList<>(parts);
        all.addAll(b.parts());
        return all;
    }

    public boolean has(String partType) {
        for (Part p : parts) if (p.type().equals(partType)) return true;
        return false;
    }
}
