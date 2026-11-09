package buffeffects.hytale;

import buffeffects.api.BuffEffectsApi;
import buffeffects.core.About;
import buffeffects.core.commands.BfxCommands;
import buffeffects.core.commands.CommandHost;
import buffeffects.core.engine.Engine;
import buffeffects.core.engine.Settings;
import buffeffects.core.rating.GtaRater;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.EffectLoader;
import buffeffects.core.format.Issue;
import buffeffects.core.json.Json;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Hytale entry point. Setup builds the engine and publishes the API; start loads every mod's effects,
 * registers their generated assets and restores the ledger, then a scheduler thread ticks run time every
 * 250 ms and saves the ledger every 30 s and at shutdown.
 */
public final class BuffEffectsPlugin extends JavaPlugin {
    static final long TICK_MS = 250;
    static final long SAVE_MS = 30_000;
    /** At most this much run time per tick: a stalled server doesn't burn an effect's time in one jump. */
    static final long MAX_STEP_MS = 1_000;

    private Assets assets;
    private HytaleProfile profile;
    private HytalePort port;
    private Engine engine;
    private volatile BfxCommands commands;
    private ScheduledExecutorService scheduler;
    private volatile boolean loaded;
    private WindowSupport windows;

    public BuffEffectsPlugin(JavaPluginInit init) {
        super(init);
    }

    /** Every condition the loaded effects use: ends_when (the effect's and its Hytale binding's) and message variants. */
    static List<Object> conditionsUsed(EffectLoader.LoadedSet set) {
        List<Object> out = new java.util.ArrayList<>();
        for (EffectLoader.Loaded l : set.effects().values()) {
            EffectDef d = l.def();
            if (d.endsWhen() != null) out.add(d.endsWhen());
            var b = d.binding("hytale");
            if (b != null && b.endsWhen() != null) out.add(b.endsWhen());
            for (var p : d.partsOn("hytale")) {
                if (!p.type().equals("message") || !(p.fields().get("variants") instanceof List<?> vs)) continue;
                for (Object v : vs) if (v instanceof Map<?, ?> vm && vm.get("if") != null) out.add(vm.get("if"));
            }
        }
        return out;
    }

    void log(String s) {
        getLogger().atInfo().log("[BuffEffects] %s", s);
    }

    @Override
    protected void setup() {
        String version = String.valueOf(getManifest().getVersion());
        getLogger().atInfo().log("%s", About.startupLine(version, "hytale"));
        assets = new Assets(this::log);
        profile = new HytaleProfile();
        port = new HytalePort(assets, this::log);
        engine = new Engine(port, profile);
        engine.setConditions(port.conditions);
        engine.setRater(new GtaRater()); // BuffEffects 1.0's own port of GTA's method: shown, not enforced
        engine.setLog(this::log);
        engine.setSettings(readSettings());
        windows = new WindowSupport(engine, profile, port, serverRoot(), getDataDirectory(), this::reload, this::log);
        commands = new BfxCommands(engine, new Host());
        getEntityStoreRegistry().registerSystem(new Systems.OnDamage(engine, port));
        getEntityStoreRegistry().registerSystem(new Systems.OnDeath(engine, port));
        getEventRegistry().register(PlayerDisconnectEvent.class, e -> {
            port.playerLeft(e.getPlayerRef().getUuid());
            windows.playerLeft(e.getPlayerRef().getUuid().toString());
        });
        getEventRegistry().register(AllWorldsLoadedEvent.class, e -> {
            if (loaded) return;
            reload();
            restoreLedger(); // only after the effects are in: restoring drops applications of unknown effects
        });
        getCommandRegistry().registerCommand(new BfxCommand(() -> commands));
        BuffEffectsApi.publish(engine::api);
    }

    @Override
    protected void start() {
        // CommandDeck's condition library starts in its setup, so it's there (or not) by now (conditions-api.md).
        port.conditions.attachLibrary(engine.api("Hledjolf:BuffEffects"), this::log);
        try {
            reload();
            restoreLedger();
        } catch (RuntimeException e) {
            loaded = false;
            log("loading effects at start failed (" + e + "); trying again once the worlds have loaded");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "buffeffects-tick");
            t.setDaemon(true);
            return t;
        });
        long[] last = {System.nanoTime()};
        long[] sinceSave = {0};
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.nanoTime();
                long elapsed = Math.min(MAX_STEP_MS, (now - last[0]) / 1_000_000);
                last[0] = now;
                for (World w : Universe.get().getWorlds().values()) Worlds.run(w, () -> port.refresh(w));
                engine.tick(elapsed);
                sinceSave[0] += elapsed;
                if (sinceSave[0] >= SAVE_MS) {
                    sinceSave[0] = 0;
                    saveLedger();
                }
            } catch (RuntimeException e) {
                log("tick failed: " + e);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        saveLedger();
        BuffEffectsApi.publish(null);
        if (port != null) port.shutdown();
    }

    // -------------------------------------------------------------------------------------- loading

    Path serverRoot() {
        Path data = getDataDirectory().toAbsolutePath();
        Path mods = data.getParent();
        return mods != null && mods.getParent() != null ? mods.getParent() : Path.of("").toAbsolutePath();
    }

    /** Finds the sidecars, runs the load pipeline, registers the generated assets, and installs the result. */
    synchronized void reload() {
        profile.scanCauses();
        profile.nativesChanged();
        if (engine != null) engine.nativesChanged();
        if (windows != null) windows.assetsChanged();
        Discovery d = Discovery.scan(serverRoot(), this::log);
        Settings s = engine.settings();
        EffectLoader.LoadedSet set = EffectLoader.load(d.sidecars, d.overrides, d.installed, profile, new GtaRater(),
                new EffectLoader.PowerCheck(s.maxStepsPerEffect, s.optedInEffects, s.optedInMods));
        for (Issue i : set.issues()) log(i.line());
        List<EffectDef> withAssets = new ArrayList<>();
        for (EffectLoader.Loaded l : set.effects().values()) {
            if (l.def().binding("hytale") != null) withAssets.add(l.def());
        }
        int registered = withAssets.size();
        // Off the world thread (reload is called from the editor's Save, on one). Applications queue behind it.
        port.offWorld(() -> {
            assets.registerCauses(withAssets); // before the effects that deal or resist them
            for (EffectDef def : withAssets) assets.registerAll(def);
        });
        engine.setEffects(set);
        port.conditions.prime(conditionsUsed(set));
        loaded = true;
        log(d.sidecars.size() + " sidecar(s), " + set.effects().size() + " effect(s) loaded, " + registered + " with Hytale assets");
    }

    // ------------------------------------------------------------------------------------ persistence

    Path ledgerFile() {
        return getDataDirectory().resolve("ledger.json");
    }

    Path instantsFile() {
        return getDataDirectory().resolve("instants.json");
    }

    void restoreLedger() {
        try {
            if (Files.isRegularFile(ledgerFile())) engine.restore(Files.readString(ledgerFile(), StandardCharsets.UTF_8));
            if (Files.isRegularFile(instantsFile())) {
                for (Object o : (List<?>) Json.parse(Files.readString(instantsFile(), StandardCharsets.UTF_8))) {
                    port.instantsDone.add(String.valueOf(o));
                }
            }
        } catch (IOException | RuntimeException e) {
            log("couldn't restore the ledger: " + e);
        }
    }

    synchronized void saveLedger() {
        if (engine == null) return;
        try {
            Files.createDirectories(getDataDirectory());
            write(ledgerFile(), engine.save());
            List<Object> live = new ArrayList<>();
            for (var apps : engine.allActive().values()) {
                for (var a : apps) if (port.instantsDone.contains(a.applicationId())) live.add(a.applicationId());
            }
            write(instantsFile(), Json.write(live, true));
        } catch (IOException | RuntimeException e) {
            log("couldn't save the ledger: " + e);
        }
    }

    static void write(Path f, String text) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // --------------------------------------------------------------------------------------- settings

    Path configFile() {
        return getDataDirectory().resolve("config.json");
    }

    Settings readSettings() {
        try {
            if (Files.isRegularFile(configFile())) {
                return Settings.fromMap((Map<?, ?>) Json.parse(Files.readString(configFile(), StandardCharsets.UTF_8)));
            }
            Settings s = new Settings();
            writeSettings(s);
            return s;
        } catch (IOException | RuntimeException e) {
            log("couldn't read config.json, using defaults: " + e);
            return new Settings();
        }
    }

    void writeSettings(Settings s) {
        try {
            Files.createDirectories(getDataDirectory());
            write(configFile(), Json.write(s.toMap(), true));
        } catch (IOException e) {
            log("couldn't write config.json: " + e);
        }
    }

    /** Mod Settings Manager's live-apply hook: the file is already written. Opt-ins need a reload. */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        engine.setSettings(readSettings());
        if (changed.containsKey("OptedInEffects") || changed.containsKey("OptedInMods") || changed.containsKey("MaxStepsPerEffect")
                || changed.containsKey("DisabledMods")) {
            reload();
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ host

    final class Host implements CommandHost {
        public String findPlayer(String name) {
            PlayerRef pr = Universe.get().getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
            return pr == null ? null : pr.getUuid().toString();
        }

        public String nameOf(String targetId) {
            var t = port.target(targetId);
            return t == null || t.name() == null ? targetId : t.name();
        }

        public boolean hasPermission(String personId, String node) {
            return port.hasPermission(personId, node);
        }

        public void openEditor(String personId) {
            if (!windows.open(personId)) log("the editor window opens only for a player in a world");
        }

        public void saveSettings(Settings s) {
            writeSettings(s);
            engine.setSettings(s);
        }

        public void reload() {
            BuffEffectsPlugin.this.reload();
        }
    }
}
