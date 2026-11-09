package buffeffects.core.window;

import buffeffects.core.format.EffectsFormat;
import buffeffects.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Edits sidecar and override documents in place: replace an effect by id, add one, or take one out. */
public final class SidecarFiles {
    private SidecarFiles() {
    }

    /** A sidecar with {@code effect} put in (replacing the one with its id, else appended). */
    public static String upsertEffect(String existing, String modId, String modName, Map<String, Object> effect) {
        Map<String, Object> doc = existing == null ? newDoc(modId, modName) : asMap(Json.parse(existing));
        List<Object> list = list(doc, "effects");
        replaceOrAdd(list, effect.get("id"), effect, false);
        return Json.write(doc, true);
    }

    /** An override file with a replacement (or a disable) of {@code id}, based on {@code basedOn}. */
    public static String upsertOverride(String existing, String targetMod, String id, Map<String, Object> effect, String basedOn) {
        Map<String, Object> doc;
        if (existing == null) {
            doc = new LinkedHashMap<>();
            doc.put("format", EffectsFormat.FORMAT_ID);
            doc.put("format_version", EffectsFormat.MAJOR + "." + EffectsFormat.MINOR);
            Map<String, Object> mod = new LinkedHashMap<>();
            mod.put("id", targetMod);
            doc.put("mod", mod);
            doc.put("overrides", new ArrayList<>());
        } else {
            doc = asMap(Json.parse(existing));
        }
        Map<String, Object> ov = new LinkedHashMap<>();
        if (effect == null) {
            ov.put("id", id);
            ov.put("disabled", true);
        } else {
            ov.put("effect", effect);
        }
        if (basedOn != null) ov.put("based_on", basedOn);
        replaceOrAdd(list(doc, "overrides"), id, ov, true);
        return Json.write(doc, true);
    }

    /** A document with the effect (or override) for {@code id} taken out; null if it wasn't there. */
    public static String remove(String existing, String id, boolean override) {
        Map<String, Object> doc = asMap(Json.parse(existing));
        List<Object> list = list(doc, override ? "overrides" : "effects");
        boolean removed = list.removeIf(o -> id.equals(idOf(o, override)));
        return removed ? Json.write(doc, true) : null;
    }

    /** Whether a document defines {@code id}. */
    public static boolean defines(String existing, String id) {
        if (existing == null) return false;
        for (Object o : list(asMap(Json.parse(existing)), "effects")) if (id.equals(idOf(o, false))) return true;
        return false;
    }

    static Map<String, Object> newDoc(String modId, String modName) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", EffectsFormat.FORMAT_ID);
        doc.put("format_version", EffectsFormat.MAJOR + "." + EffectsFormat.MINOR);
        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("id", modId);
        if (modName != null) mod.put("name", modName);
        doc.put("mod", mod);
        doc.put("effects", new ArrayList<>());
        return doc;
    }

    private static void replaceOrAdd(List<Object> list, Object id, Object entry, boolean override) {
        for (int k = 0; k < list.size(); k++) {
            if (id != null && id.equals(idOf(list.get(k), override))) {
                list.set(k, entry);
                return;
            }
        }
        list.add(entry);
    }

    private static Object idOf(Object o, boolean override) {
        if (!(o instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) o;
        if (override && m.get("effect") instanceof Map) return ((Map<?, ?>) m.get("effect")).get("id");
        return m.get("id");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        if (!(v instanceof List)) {
            v = new ArrayList<>();
            doc.put(key, v);
        }
        return (List<Object>) v;
    }
}
