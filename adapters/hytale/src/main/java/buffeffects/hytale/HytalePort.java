package buffeffects.hytale;

import buffeffects.core.engine.Application;
import buffeffects.core.engine.CapProfile;
import buffeffects.core.engine.GamePort;
import buffeffects.core.engine.TargetInfo;
import buffeffects.core.format.Binding;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.Part;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.joml.Vector3d;

/**
 * The engine's view of Hytale. Targets are entity UUIDs (a player's UUID for players). The engine calls
 * this under its lock from any thread, so nothing here waits on a world: game state is read from a cache
 * refreshed on each world's thread ({@link #refresh}), and game work goes through one ordered executor
 * (which registers any missing asset off the world thread) and then onto the target's world thread.
 */
final class HytalePort implements GamePort {
    /** What the cache knows of one target. Written on its world thread, read anywhere. */
    static final class Tracked {
        final UUID uuid;
        final boolean player;
        volatile UUID world;
        volatile String name;
        volatile boolean loaded;
        volatile boolean alive = true;
        volatile double healthMax = Double.NaN;
        volatile Set<String> natives = Set.of();

        Tracked(UUID uuid, boolean player) {
            this.uuid = uuid;
            this.player = player;
        }
    }

    /** What the game is showing for one application. */
    static final class Shown {
        final List<String> assets = new ArrayList<>();
        final Map<Integer, String> regens = new ConcurrentHashMap<>();
        boolean intangible;
        /** Its {@code light} part: radius and colour, or null. */
        int[] light;
        int slot;
    }

    /** One world's in-game clock: game time (ms) and the day's layout in game seconds. */
    record Clock(long gameMs) {
    }

    final Map<String, Tracked> targets = new ConcurrentHashMap<>();
    final Map<String, Shown> shown = new ConcurrentHashMap<>();
    final Map<UUID, Clock> clocks = new ConcurrentHashMap<>();
    /** Applications whose instant parts have run (persisted with the ledger, so a restart doesn't heal again). */
    final Set<String> instantsDone = ConcurrentHashMap.newKeySet();
    final HytaleConditions conditions = new HytaleConditions(this);
    final Assets assets;
    private final Consumer<String> log;
    private final ExecutorService game = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "buffeffects-game");
        t.setDaemon(true);
        return t;
    });

    HytalePort(Assets assets, Consumer<String> log) {
        this.assets = assets;
        this.log = log;
    }

    void shutdown() {
        game.shutdown();
    }

    /**
     * Runs game work on the port's ordered thread, never a world's. Registering assets takes the game's global
     * asset lock, which can wait on a world thread: on one, it can hang the world (bfx-window, 2026-11-07).
     */
    void offWorld(Runnable r) {
        game.execute(r);
    }

    public String game() {
        return "hytale";
    }

    // ------------------------------------------------------------------------------------------ targets

    public TargetInfo target(String id) {
        Tracked t = targets.get(id);
        if (t == null) t = discover(id);
        if (t == null) return null;
        return new TargetInfo(id, t.name, t.player ? TargetInfo.Kind.PLAYER : TargetInfo.Kind.NPC, t.loaded, t.alive);
    }

    /** First sight of a target: a player online, or an entity in some world (thread-safe map lookups only). */
    private Tracked discover(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        PlayerRef pr = Universe.get().getPlayer(uuid);
        if (pr != null) {
            Tracked t = new Tracked(uuid, true);
            t.world = pr.getWorldUuid();
            t.name = pr.getUsername();
            t.loaded = pr.isValid();
            targets.put(id, t);
            return t;
        }
        for (World w : Universe.get().getWorlds().values()) {
            Ref<EntityStore> ref = w.getEntityStore().getRefFromUUID(uuid);
            if (ref != null && ref.isValid()) {
                Tracked t = new Tracked(uuid, false);
                t.world = worldUuid(w);
                t.name = "NPC";
                t.loaded = true;
                targets.put(id, t);
                return t;
            }
        }
        return null;
    }

    static UUID worldUuid(World w) {
        return w.getWorldConfig().getUuid();
    }

    void playerJoined(PlayerRef pr) {
        Tracked t = targets.computeIfAbsent(pr.getUuid().toString(), k -> new Tracked(pr.getUuid(), true));
        t.world = pr.getWorldUuid();
        t.name = pr.getUsername();
        t.loaded = true;
        t.alive = true;
    }

    void playerLeft(UUID uuid) {
        Tracked t = targets.get(uuid.toString());
        if (t != null) t.loaded = false;
    }

    /** Refreshes the cache for one world, on its thread: players there, tracked NPCs there, its clock. */
    void refresh(World w) {
        UUID wid = worldUuid(w);
        Store<EntityStore> store = w.getEntityStore().getStore();
        try {
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            if (time != null) clocks.put(wid, new Clock(time.getGameTime().toEpochMilli()));
        } catch (RuntimeException ignored) {
            // no clock in this world
        }
        Set<String> seen = new HashSet<>();
        for (PlayerRef pr : w.getPlayerRefs()) {
            String id = pr.getUuid().toString();
            seen.add(id);
            Tracked t = targets.computeIfAbsent(id, k -> new Tracked(pr.getUuid(), true));
            t.world = wid;
            t.name = pr.getUsername();
            read(t, pr.getReference(), store);
        }
        for (Map.Entry<String, Tracked> e : targets.entrySet()) {
            Tracked t = e.getValue();
            if (!wid.equals(t.world) || seen.contains(e.getKey())) continue;
            if (t.player) {
                t.loaded = false; // left this world, or the server
                continue;
            }
            read(t, w.getEntityStore().getRefFromUUID(t.uuid), store);
            if (t.loaded) {
                NPCEntity npc = store.getComponent(w.getEntityStore().getRefFromUUID(t.uuid), NPCEntity.getComponentType());
                if (npc != null && npc.getRoleName() != null) t.name = npc.getRoleName();
            }
        }
        conditions.evaluateOn(w, store);
    }

    private static void read(Tracked t, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            t.loaded = false;
            return;
        }
        t.loaded = true;
        t.alive = store.getComponent(ref, DeathComponent.getComponentType()) == null;
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats != null) {
            EntityStatValue h = stats.get(DefaultEntityStatTypes.getHealth());
            if (h != null) t.healthMax = h.getMax();
        }
        EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ec != null) {
            Set<String> ids = new HashSet<>();
            for (ActiveEntityEffect a : ec.getActiveEffects().values()) {
                EntityEffect e = EntityEffect.getAssetMap().getAsset(a.getEntityEffectIndex());
                if (e != null) ids.add(e.getId());
            }
            t.natives = ids;
        }
    }

    Ref<EntityStore> refOf(World w, Tracked t) {
        if (t.player) {
            PlayerRef pr = Universe.get().getPlayer(t.uuid);
            if (pr != null && pr.getReference() != null && pr.getReference().isValid()) return pr.getReference();
        }
        Ref<EntityStore> r = w.getEntityStore().getRefFromUUID(t.uuid);
        return r != null && r.isValid() ? r : null;
    }

    World worldOf(Tracked t) {
        return t == null || t.world == null ? null : Universe.get().getWorld(t.world);
    }

    // -------------------------------------------------------------------------------------- permissions

    public boolean hasPermission(String personId, String node) {
        try {
            UUID u = UUID.fromString(personId);
            if (u.equals(ConsoleSender.INSTANCE.getUuid())) return true;
            return PermissionsModule.get().hasPermission(u, node);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean carriesNative(String targetId, String gameEffectId) {
        Tracked t = targets.get(targetId);
        return t != null && t.natives.contains(gameEffectId);
    }

    // --------------------------------------------------------------------------------- showing effects

    /** The stack slot this application uses: the lowest not used by another shown stack of the same effect. */
    private int slotFor(Application app, EffectDef def) {
        Shown s = shown.get(app.id);
        if (s != null) return s.slot;
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<String, Shown> e : shown.entrySet()) {
            if (e.getKey().startsWith(app.target + "|" + app.effect + "|")) used.add(e.getValue().slot);
        }
        int k = 0;
        while (used.contains(k) && k < Math.max(1, def.maxStacks()) - 1) k++;
        return k;
    }

    private static String key(Application app) {
        return app.target + "|" + app.effect + "|" + app.id;
    }

    public void activate(Application app, EffectDef def, long msLeft, boolean playOnApply, boolean holdShape) {
        Tracked t = targets.get(app.target);
        if (t == null) return;
        String k = key(app);
        double strength = app.strength;
        Set<Integer> broken = new HashSet<>(app.brokenParts);
        int slot = slotFor(app, def);
        boolean instantNeeded = !instantsDone.contains(app.id);
        String appId = app.id;
        String actorId = app.actor;
        game.execute(() -> {
            List<String> ids = new ArrayList<>();
            Map<Integer, String> regens = new java.util.HashMap<>();
            List<String> instants = new ArrayList<>();
            List<Part> hits = new ArrayList<>();
            if (Assets.needsMain(def)) {
                String id = assets.ensureMain(def, slot, strength, holdShape);
                if (id != null) ids.add(id);
            }
            Binding b = def.binding("hytale");
            if (b != null) ids.addAll(b.natives());
            List<Part> parts = def.partsOn("hytale");
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                if (p.type().equals("regen") && !broken.contains(i)) {
                    String id = assets.ensureHeal(p, strength);
                    if (id != null) regens.put(i, id);
                } else if (p.type().equals("instant") && instantNeeded) {
                    String id = assets.ensureHeal(p, strength);
                    if (id != null) instants.add(id);
                } else if (p.type().equals("hit") && instantNeeded) {
                    hits.add(p.scaled(strength));
                }
            }
            String look = playOnApply ? assets.ensureLook(def, true) : null;
            boolean intangible = def.has("intangible");
            int[] light = lightOf(def);
            World w = worldOf(t);
            if (w == null) return;
            Worlds.run(w, () -> {
                Ref<EntityStore> ref = refOf(w, t);
                if (ref == null) return;
                Store<EntityStore> store = w.getEntityStore().getStore();
                EffectControllerComponent ec = store.ensureAndGetComponent(ref, EffectControllerComponent.getComponentType());
                Shown before = shown.get(k);
                if (before != null) {
                    for (String old : before.assets) if (!ids.contains(old)) remove(ec, ref, old, store);
                }
                Shown s = new Shown();
                s.slot = slot;
                for (String id : ids) if (add(ec, ref, id, msLeft, store)) s.assets.add(id);
                for (Map.Entry<Integer, String> r : regens.entrySet()) {
                    if (add(ec, ref, r.getValue(), msLeft, store)) s.regens.put(r.getKey(), r.getValue());
                }
                for (String id : instants) add(ec, ref, id, 100, store);
                // Hurting yourself has no source: Hytale blocks player-sourced damage to a player when PvP is off,
                // which is right for another player's bolt, but a self-applied hit should simply hurt.
                for (Part h : hits) hit(w, ref, store, h, app.target.equals(actorId) ? null : actorId);
                if (!instants.isEmpty() || !hits.isEmpty()) instantsDone.add(appId);
                if (intangible) {
                    store.putComponent(ref, Intangible.getComponentType(), Intangible.INSTANCE);
                    s.intangible = true;
                }
                if (look != null) add(ec, ref, look, 1000, store);
                s.light = light;
                shown.put(k, s);
                if (light != null || before != null && before.light != null) updateLight(app.target, ref, store);
            });
        });
    }

    /**
     * The {@code hit} part: instant damage by cause through the target's resistances, with the actor as the
     * damage's source, so a kill is credited to them (effects.md section 6; probe P18).
     */
    private void hit(World w, Ref<EntityStore> ref, Store<EntityStore> store, Part h, String actorId) {
        DamageCause cause = DamageCause.getAssetMap().getAsset(Assets.cause(h.str("cause")));
        if (cause == null) {
            log.accept("hit: no damage cause " + h.str("cause"));
            return;
        }
        Damage.Source src = Damage.NULL_SOURCE;
        Ref<EntityStore> actor = actorRef(w, actorId);
        if (actor != null) src = new Damage.EntitySource(actor);
        Damage d = new Damage(src, cause, (float) h.num("amount", 0));
        DamageSystems.executeDamage(ref, store, d);
        if (TRACE) log.accept("hit " + h.num("amount", 0) + " " + cause.getId() + " from " + (actor == null ? "nothing" : actorId)
                + ": dealt " + d.getAmount() + (d.isCancelled() ? " (cancelled)" : ""));
    }

    /** {@code -Dbuffeffects.trace=true}: log what the game did with each part (test runs only). */
    static final boolean TRACE = Boolean.getBoolean("buffeffects.trace");

    /** An actor's entity in this world: a player, or any entity by UUID. Null when it isn't here. */
    private static Ref<EntityStore> actorRef(World w, String actorId) {
        if (actorId == null) return null;
        UUID u;
        try {
            u = UUID.fromString(actorId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        PlayerRef pr = Universe.get().getPlayer(u);
        if (pr != null && pr.getReference() != null && pr.getReference().isValid()) return pr.getReference();
        Ref<EntityStore> r = w.getEntityStore().getRefFromUUID(u);
        return r != null && r.isValid() ? r : null;
    }

    /** The modifier key BuffEffects' combined multipliers use on an entity's stats. */
    static final String MULTIPLY_KEY = "BFX_multiply";
    private final Map<String, Set<Integer>> multiplied = new ConcurrentHashMap<>();

    @Override
    public void setStatMultipliers(String targetId, Map<String, Double> factors) {
        Tracked t = targets.get(targetId);
        World w = worldOf(t);
        if (w == null) return;
        Map<Integer, Float> want = new java.util.HashMap<>();
        for (Map.Entry<String, Double> e : factors.entrySet()) {
            int idx = EntityStatType.getAssetMap().getIndex(Assets.stat(e.getKey()));
            if (idx >= 0) want.put(idx, (float) e.getValue().doubleValue());
        }
        Worlds.run(w, () -> {
            Ref<EntityStore> ref = refOf(w, t);
            if (ref == null) return;
            Store<EntityStore> store = w.getEntityStore().getStore();
            EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
            if (stats == null) return;
            Set<Integer> before = multiplied.getOrDefault(targetId, Set.of());
            for (int idx : before) if (!want.containsKey(idx)) stats.removeModifier(idx, MULTIPLY_KEY);
            for (Map.Entry<Integer, Float> e : want.entrySet()) {
                stats.putModifier(e.getKey(), MULTIPLY_KEY, new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.MULTIPLICATIVE, e.getValue()));
            }
            if (want.isEmpty()) multiplied.remove(targetId);
            else multiplied.put(targetId, new HashSet<>(want.keySet()));
        });
    }

    private boolean add(EffectControllerComponent ec, Ref<EntityStore> ref, String id, long ms, Store<EntityStore> store) {
        EntityEffect e = EntityEffect.getAssetMap().getAsset(id);
        if (e == null) {
            log.accept("no effect asset " + id);
            return false;
        }
        if (ms < 0) return ec.addInfiniteEffect(ref, EntityEffect.getAssetMap().getIndex(id), e, store);
        return ec.addEffect(ref, e, Math.max(0.05f, ms / 1000f), OverlapBehavior.OVERWRITE, store);
    }

    private static void remove(EffectControllerComponent ec, Ref<EntityStore> ref, String id, Store<EntityStore> store) {
        int idx = EntityEffect.getAssetMap().getIndex(id);
        if (idx >= 0 && ec.hasEffect(idx)) ec.removeEffect(ref, idx, store);
    }

    public void deactivate(Application app, EffectDef def, boolean playOnRemove) {
        Tracked t = targets.get(app.target);
        String k = key(app);
        if (t == null) {
            shown.remove(k);
            return;
        }
        String target = app.target;
        game.execute(() -> {
            String look = playOnRemove ? assets.ensureLook(def, false) : null;
            World w = worldOf(t);
            Shown s = shown.remove(k);
            if (w == null || s == null) return;
            Worlds.run(w, () -> {
                Ref<EntityStore> ref = refOf(w, t);
                if (ref == null) return;
                Store<EntityStore> store = w.getEntityStore().getStore();
                EffectControllerComponent ec = store.ensureAndGetComponent(ref, EffectControllerComponent.getComponentType());
                for (String id : s.assets) remove(ec, ref, id, store);
                for (String id : s.regens.values()) remove(ec, ref, id, store);
                if (s.intangible && !otherIntangible(target)) store.tryRemoveComponent(ref, Intangible.getComponentType());
                if (s.light != null) updateLight(target, ref, store);
                if (hasAnimation(def)) stopAnimations(ref, store);
                if (look != null) add(ec, ref, look, 1000, store);
            });
        });
    }

    private static boolean hasAnimation(EffectDef def) {
        Binding b = def.binding("hytale");
        return b != null && b.whileActive().get("animation") != null;
    }

    /**
     * The client keeps looping an effect's EntityAnimationId (FlyIdle, for example) after the
     * effect is gone, even after vanilla's /player effect clear (playtest bfx-ghost-look, 2026-11-06).
     * The server doesn't track which slot it plays in, so stop the slots an effect animation can use.
     * <p>
     * {@code stopAnimation(ref, slot, accessor)} sends the stop to everyone who sees the entity <em>except
     * itself</em> (its boolean is false), so a player kept seeing their own pose (2026-11-07). The flag is true here.
     */
    private static void stopAnimations(Ref<EntityStore> ref, Store<EntityStore> store) {
        for (AnimationSlot slot : new AnimationSlot[] {AnimationSlot.Status, AnimationSlot.ServerAction, AnimationSlot.Action, AnimationSlot.Emote}) {
            try {
                AnimationUtils.stopAnimation(ref, slot, true, store);
            } catch (RuntimeException ignored) {
                // a slot the entity doesn't have
            }
        }
    }

    /** The first {@code light} part on Hytale: {radius, red, green, blue}, or null. */
    static int[] lightOf(EffectDef def) {
        for (Part p : def.partsOn("hytale")) {
            if (!p.type().equals("light")) continue;
            int rgb = Integer.parseInt(p.str("color").substring(1), 16);
            int radius = (int) Math.round(Math.max(1, Math.min(15, p.num("radius", 8))));
            return new int[] {radius, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        }
        return null;
    }

    /** Bearers whose DynamicLight BuffEffects set: only those are cleared, never a light of the game's own. */
    private final Set<String> lit = ConcurrentHashMap.newKeySet();

    /**
     * The {@code light} slot (effects.md section 5): the bearer shows the light with the biggest radius among
     * its applications, or none. On the world's thread (P14: {@code DynamicLight}, set and cleared server-side).
     */
    private void updateLight(String target, Ref<EntityStore> ref, Store<EntityStore> store) {
        int[] best = null;
        for (Map.Entry<String, Shown> e : shown.entrySet()) {
            int[] l = e.getValue().light;
            if (l != null && e.getKey().startsWith(target + "|") && (best == null || l[0] > best[0])) best = l;
        }
        if (best != null) {
            store.putComponent(ref, DynamicLight.getComponentType(),
                    new DynamicLight(new ColorLight((byte) best[0], (byte) best[1], (byte) best[2], (byte) best[3])));
            lit.add(target);
        } else if (lit.remove(target)) {
            store.tryRemoveComponent(ref, DynamicLight.getComponentType());
        }
    }

    private boolean otherIntangible(String target) {
        for (Map.Entry<String, Shown> e : shown.entrySet()) {
            if (e.getKey().startsWith(target + "|") && e.getValue().intangible) return true;
        }
        return false;
    }

    public void stopPart(Application app, EffectDef def, int partIndex) {
        Tracked t = targets.get(app.target);
        String k = key(app);
        if (t == null) return;
        game.execute(() -> {
            World w = worldOf(t);
            Shown s = shown.get(k);
            if (w == null || s == null) return;
            String id = s.regens.remove(partIndex);
            if (id == null) return;
            Worlds.run(w, () -> {
                Ref<EntityStore> ref = refOf(w, t);
                if (ref == null) return;
                Store<EntityStore> store = w.getEntityStore().getStore();
                EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
                if (ec != null) remove(ec, ref, id, store);
            });
        });
    }

    /** Whole percents: the heal and strength variants are cached per percent. */
    public double quantizeStrength(EffectDef def, double strength) {
        return Math.round(strength * 100) / 100.0;
    }

    // -------------------------------------------------------------------------------------------- lines

    public void say(String targetId, boolean nearby, double radius, String text) {
        Tracked t = targets.get(targetId);
        Message msg = text.startsWith("lang:") ? Message.translation(text.substring(5)) : Message.raw(text);
        if (t == null && !nearby) {
            // An actor BuffEffects doesn't track: a player, by UUID.
            try {
                PlayerRef pr = Universe.get().getPlayer(UUID.fromString(targetId));
                if (pr != null) pr.sendMessage(msg);
            } catch (IllegalArgumentException ignored) {
                // not a player id
            }
            return;
        }
        World w = worldOf(t);
        if (w == null) return;
        Worlds.run(w, () -> {
            if (!nearby) {
                PlayerRef pr = Universe.get().getPlayer(t.uuid);
                if (pr != null) pr.sendMessage(msg);
                return;
            }
            Ref<EntityStore> ref = refOf(w, t);
            if (ref == null) return;
            Store<EntityStore> store = w.getEntityStore().getStore();
            TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
            if (tc == null) return;
            Vector3d at = tc.getPosition();
            for (PlayerRef pr : w.getPlayerRefs()) {
                Ref<EntityStore> pref = pr.getReference();
                if (pref == null || !pref.isValid()) continue;
                TransformComponent ptc = store.getComponent(pref, TransformComponent.getComponentType());
                if (ptc != null && ptc.getPosition().distance(at) <= radius) pr.sendMessage(msg);
            }
        });
    }

    // -------------------------------------------------------------------------------------------- time

    /** Game seconds in a day, and where dawn (sunrise) and dusk (sunrise + daytime) fall in it. */
    static long daySeconds() {
        return WorldTimeResource.SECONDS_PER_DAY;
    }

    public long worldTime(String targetId) {
        Tracked t = targets.get(targetId);
        Clock c = t == null || t.world == null ? null : clocks.get(t.world);
        return c == null ? 0 : c.gameMs();
    }

    public long worldTimeAt(String targetId, String point, int days) {
        long now = worldTime(targetId);
        long dayMs = daySeconds() * 1000L;
        if (point == null) return now + days * dayMs;
        long at;
        switch (point) {
            case "dawn":
                at = WorldTimeResource.SUNRISE_SECONDS * 1000L;
                break;
            case "dusk":
                at = (WorldTimeResource.SUNRISE_SECONDS + (long) WorldTimeResource.DAYTIME_SECONDS) * 1000L;
                break;
            default:
                at = dayMs; // end_of_day: midnight
                break;
        }
        at = at % dayMs;
        long tod = Math.floorMod(now, dayMs);
        long wait = at > tod ? at - tod : dayMs - tod + at;
        return now + wait;
    }

    public double statMax(String targetId, String stat) {
        Tracked t = targets.get(targetId);
        return t == null || !"health".equals(stat) ? Double.NaN : t.healthMax;
    }

    /**
     * Hytale's own ceilings: vanilla's Immunity_* effects give 100% resistance, so resistance may reach
     * 1.0 here; Healing_Totem_Heal gives 5 health a second; vanilla invulnerability lasts 0.25 s, below
     * BuffEffects' default, so it sets no ceiling.
     */
    public CapProfile capProfile() {
        // No native ceilings above the defaults for mods' effects: vanilla's own 100% immunities and long stuns
        // pass by their ids instead (effects.md section 11). Regeneration up to 5/s (Healing_Totem_Heal).
        return new CapProfile(Double.NaN, 5.0, Double.NaN);
    }

    /** Events with no target go to the default world's thread. */
    public void deliver(Runnable r) {
        World w = Universe.get().getDefaultWorld();
        if (w == null) r.run();
        else Worlds.run(w, r);
    }

    /** Events for a target go to its world's thread, else the default world's. */
    @Override
    public void deliver(String targetId, Runnable r) {
        World w = targetId == null ? null : worldOf(targets.get(targetId));
        if (w == null) deliver(r);
        else Worlds.run(w, r);
    }
}
