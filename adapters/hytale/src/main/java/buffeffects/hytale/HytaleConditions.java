package buffeffects.hytale;

import buffeffects.core.engine.Conditions;
import buffeffects.core.json.Json;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonDocument;

/**
 * {@code ends_when} on Hytale. The native types ({@code hytale.in_fluid} with {@code tags} or
 * {@code fluids}, {@code hytale.has_effect} with {@code effect}, {@code hytale.game_mode} with
 * {@code game_mode}) become the game's own Condition assets (InFluid, HasEffect, CheckPlayerGameMode), and
 * each world thread evaluates the ones asked about during its cache refresh. The engine reads the last
 * answer: UNKNOWN until one exists. Anything else is UNKNOWN until CommandDeck 1.3's condition library.
 */
final class HytaleConditions implements Conditions {
    private final HytalePort port;
    /** (target | condition JSON) -> the game's condition, decoded once. */
    private final Map<String, Condition> asked = new ConcurrentHashMap<>();
    private final Map<String, Boolean> answers = new ConcurrentHashMap<>();
    /** CommandDeck's condition library, when it's installed (CommandDeck >= 1.2.0); else null. */
    private volatile LibraryBridge library;
    /** (bearer | caster | condition JSON) -> the JSON, for the library; answered on the bearer's world thread. */
    private final Map<String, String[]> libraryAsked = new ConcurrentHashMap<>();
    private final Map<String, Tri> libraryAnswers = new ConcurrentHashMap<>();

    HytaleConditions(HytalePort port) {
        this.port = port;
    }

    /** The game's Condition JSON for a BuffEffects condition, or null when it isn't a native one. */
    static String nativeJson(Object condition) {
        if (!(condition instanceof Map)) return null;
        Map<?, ?> c = (Map<?, ?>) condition;
        Object type = c.get("type");
        Map<String, Object> m = new LinkedHashMap<>();
        if ("hytale.in_fluid".equals(type)) {
            m.put("Id", "InFluid");
            if (c.get("tags") instanceof List) m.put("Tags", c.get("tags"));
            if (c.get("fluids") instanceof List) m.put("FluidIds", c.get("fluids"));
        } else if ("hytale.has_effect".equals(type) && c.get("effect") instanceof String) {
            m.put("Id", "HasEffect");
            m.put("EffectId", c.get("effect"));
        } else if ("hytale.game_mode".equals(type) && c.get("game_mode") instanceof String) {
            m.put("Id", "CheckPlayerGameMode");
            m.put("GameMode", c.get("game_mode"));
        } else {
            return null;
        }
        if (Boolean.TRUE.equals(c.get("inverse"))) m.put("Inverse", true);
        return Json.write(m, false);
    }

    /**
     * Connects CommandDeck's condition library, if its classes are there and it has started, and registers
     * BuffEffects' condition types with it. Without it, only the game's native conditions are checked.
     */
    void attachLibrary(buffeffects.api.BuffEffects api, java.util.function.Consumer<String> log) {
        try {
            Class.forName("hledjolf.conditions.api.Conditions", false, HytaleConditions.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            log.accept("CommandDeck's condition library isn't installed: only Hytale's native conditions are checked");
            return;
        }
        LibraryBridge b = LibraryBridge.find(log);
        if (b == null) {
            log.accept("CommandDeck's condition library hasn't started: only Hytale's native conditions are checked");
            return;
        }
        b.register(api);
        library = b;
    }

    public Tri evaluate(Object condition, String bearerId) {
        return evaluate(condition, bearerId, null);
    }

    @Override
    public Tri evaluate(Object condition, String bearerId, String casterId) {
        String json = nativeJson(condition);
        if (json == null) {
            if (library == null || condition == null) return Tri.UNKNOWN;
            String text = Json.write(condition, false);
            String key = bearerId + "|" + casterId + "|" + text;
            libraryAsked.putIfAbsent(key, new String[] {bearerId, casterId, text});
            return libraryAnswers.getOrDefault(key, Tri.UNKNOWN);
        }
        return nativeAnswer(json, bearerId);
    }

    /** A native condition, as the game's JSON, for one bearer: the cached answer (asked the first time). */
    private Tri nativeAnswer(String json, String bearerId) {
        String key = bearerId + "|" + json;
        if (!asked.containsKey(key)) {
            try {
                asked.put(key, Condition.CODEC.decode(BsonDocument.parse(json), new com.hypixel.hytale.codec.ExtraInfo()));
            } catch (RuntimeException e) {
                return Tri.UNKNOWN;
            }
        }
        Boolean b = answers.get(key);
        return b == null ? Tri.UNKNOWN : b ? Tri.TRUE : Tri.FALSE;
    }

    @Override
    public boolean isNative(Object condition) {
        return nativeJson(condition) != null;
    }

    public boolean canEvaluate(Object condition) {
        if (nativeJson(condition) != null) return true;
        LibraryBridge b = library;
        return b != null && condition != null && b.canEvaluate(Json.write(condition, false));
    }

    /** The native conditions the loaded effects use ({@code ends_when}, message {@code variants}), as game JSON. */
    private volatile java.util.Set<String> primed = java.util.Set.of();

    /**
     * Asks every native condition the loaded effects use for every target, every tick, so an answer is ready
     * when an effect is applied (a potion's taste "in water" is said at once, not one tick later).
     */
    void prime(java.util.Collection<?> conditions) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Object c : conditions) {
            String json = nativeJson(c);
            if (json != null) out.add(json);
        }
        primed = out;
    }

    /** On a world's thread: evaluates the asked conditions of targets in that world. */
    void evaluateOn(World w, Store<EntityStore> store) {
        java.util.Set<String> pr = primed;
        if (!pr.isEmpty()) {
            for (Map.Entry<String, HytalePort.Tracked> t : port.targets.entrySet()) {
                if (!HytalePort.worldUuid(w).equals(t.getValue().world)) continue;
                for (String json : pr) nativeAnswer(json, t.getKey());
            }
        }
        Instant now;
        try {
            now = store.getResource(TimeResource.getResourceType()).getNow();
        } catch (RuntimeException e) {
            now = Instant.now();
        }
        for (Map.Entry<String, Condition> e : asked.entrySet()) {
            String target = e.getKey().substring(0, e.getKey().indexOf('|'));
            HytalePort.Tracked t = port.targets.get(target);
            if (t == null || !HytalePort.worldUuid(w).equals(t.world)) continue;
            Ref<EntityStore> ref = port.refOf(w, t);
            if (ref == null) {
                answers.remove(e.getKey());
                continue;
            }
            try {
                answers.put(e.getKey(), e.getValue().eval(store, ref, now));
            } catch (RuntimeException x) {
                answers.remove(e.getKey());
            }
        }
        LibraryBridge b = library;
        if (b == null) return;
        for (Map.Entry<String, String[]> e : libraryAsked.entrySet()) {
            HytalePort.Tracked t = port.targets.get(e.getValue()[0]);
            if (t == null || !HytalePort.worldUuid(w).equals(t.world)) continue;
            try {
                libraryAnswers.put(e.getKey(), b.evaluate(e.getValue()[2], e.getValue()[0], e.getValue()[1]));
            } catch (RuntimeException x) {
                libraryAnswers.remove(e.getKey());
            }
        }
    }
}
