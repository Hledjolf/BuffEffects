package buffeffects.core.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an {@link EffectDef} back as a sidecar effect object (the inverse of {@link EffectsFormat}), for
 * {@code describe}, the editor's export and server overrides. Defaults are left out, and the
 * can't-attack that {@code intangible} brings isn't written (the loader adds it again).
 */
public final class EffectWriter {
    private EffectWriter() {
    }

    public static Map<String, Object> toMap(EffectDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("name", d.name());
        if (d.description() != null) m.put("description", d.description());
        if (d.harmful()) m.put("harmful", true);
        if (d.category() != null) m.put("category", d.category());
        if (!d.tags().isEmpty()) m.put("tags", new ArrayList<>(d.tags()));
        if (!d.cureTags().isEmpty()) m.put("cure_tags", new ArrayList<>(d.cureTags()));
        m.put("duration", duration(d.duration()));
        if (d.reapply() != EffectDef.Reapply.REFRESH) m.put("reapply", d.reapply().name().toLowerCase());
        if (d.stackingCategory() != null || d.reapply() == EffectDef.Reapply.STACK) {
            Map<String, Object> s = new LinkedHashMap<>();
            if (d.stackingCategory() != null) s.put("category", d.stackingCategory());
            if (d.reapply() == EffectDef.Reapply.STACK) s.put("max_stacks", (long) d.maxStacks());
            if (!d.stackNames().isEmpty()) s.put("names", new ArrayList<>(d.stackNames()));
            m.put("stacking", s);
        }
        Map<String, Object> cms = new LinkedHashMap<>();
        if (d.durationBounds() != null && d.duration().hasRunTime()
                && (d.durationBounds().min() * 1000 != d.duration().runMs() || d.durationBounds().max() * 1000 != d.duration().runMs())) {
            cms.put("duration", bounds(d.durationBounds()));
        }
        if (d.strengthBounds().min() != 1 || d.strengthBounds().max() != 1) cms.put("strength", bounds(d.strengthBounds()));
        if (!cms.isEmpty()) m.put("caller_may_set", cms);
        m.put("parts", parts(d.parts()));
        Map<String, Object> bs = new LinkedHashMap<>();
        for (Binding b : d.bindings().values()) bs.put(b.game(), binding(b));
        m.put("bindings", bs);
        if (d.endsWhen() != null) m.put("ends_when", d.endsWhen());
        if (!d.immuneIf().isEmpty()) {
            List<Object> refs = new ArrayList<>();
            for (String r : d.immuneIf()) refs.add(r.startsWith(d.mod() + "/") ? r.substring(d.mod().length() + 1) : r);
            m.put("immune_if", refs);
        }
        if (d.onDeath() != EffectDef.OnDeath.CLEAR) m.put("on_death", d.onDeath().name().toLowerCase());
        if (d.declaredPower() != null) m.put("power", d.declaredPower());
        if (d.permission() != null && !d.permission().equals(EffectsFormat.defaultPermission(d.mod(), d.id()))) {
            m.put("permission", d.permission());
        }
        return m;
    }

    static Map<String, Object> duration(DurationSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s.infinite()) m.put("infinite", true);
        if (s.hasRunTime()) m.put("seconds", s.runMs() / 1000.0);
        if (s.worldPoint() != null) m.put("world", s.worldPoint());
        if (s.worldDays() > 0) m.put("world_days", (long) s.worldDays());
        return m;
    }

    static Map<String, Object> bounds(EffectDef.Bounds b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", b.min());
        m.put("max", b.max());
        return m;
    }

    static List<Object> parts(List<Part> parts) {
        List<Object> out = new ArrayList<>();
        for (Part p : parts) {
            if ("intangible".equals(p.fields().get("added_by"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type());
            m.putAll(p.fields());
            if (!p.required()) m.put("required", false);
            if (p.type().equals("regen") && Boolean.TRUE.equals(m.get("breaks_on_hit"))) m.remove("breaks_on_hit");
            if (p.type().equals("message")) {
                if ("self".equals(m.get("to"))) m.remove("to");
                if (Boolean.FALSE.equals(m.get("once"))) m.remove("once");
            }
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> binding(Binding b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b.icon() != null) m.put("icon", b.icon());
        Map<String, Object> look = new LinkedHashMap<>();
        if (!b.whileActive().isEmpty()) look.put("while_active", new LinkedHashMap<>(b.whileActive()));
        if (!b.onApply().isEmpty()) look.put("on_apply", new LinkedHashMap<>(b.onApply()));
        if (!b.onRemove().isEmpty()) look.put("on_remove", new LinkedHashMap<>(b.onRemove()));
        if (!look.isEmpty()) m.put("look", look);
        if (b.shape() != null) m.put("shape", b.shape());
        if (!b.natives().isEmpty()) m.put("native", new ArrayList<>(b.natives()));
        if (!b.parts().isEmpty()) m.put("parts", parts(b.parts()));
        if (b.endsWhen() != null) m.put("ends_when", b.endsWhen());
        return m;
    }
}
