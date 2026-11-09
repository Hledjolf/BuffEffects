package buffeffects.hytale;

import buffeffects.core.engine.Engine;
import buffeffects.core.engine.TargetInfo;
import buffeffects.core.format.GameProfile;
import buffeffects.core.json.Json;
import buffeffects.core.rating.GtaRater;
import buffeffects.core.window.EditorModel;
import buffeffects.core.window.Storage;
import buffeffects.core.window.WindowModel;
import buffeffects.hytale.window.EditorPage;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.modelvfx.config.ModelVFX;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The window on Hytale (window.md): one {@link WindowModel} per person, kept while the server runs so a
 * closed window keeps its draft, drawn on {@link EditorPage}. The game's side of it lives here: online
 * players, the asset lists for the pickers, files, and the mod source folders from {@code dev-mods.json}.
 */
final class WindowSupport {
    private final Engine engine;
    private final GameProfile profile;
    private final HytalePort port;
    private final Path serverRoot;
    private final Path dataDir;
    private final Runnable reload;
    private final Consumer<String> log;
    private final Map<String, WindowModel> windows = new ConcurrentHashMap<>();
    private final Map<GameProfile.Kind, List<String>> assetCache = new ConcurrentHashMap<>();

    WindowSupport(Engine engine, GameProfile profile, HytalePort port, Path serverRoot, Path dataDir, Runnable reload, Consumer<String> log) {
        this.engine = engine;
        this.profile = profile;
        this.port = port;
        this.serverRoot = serverRoot;
        this.dataDir = dataDir;
        this.reload = reload;
        this.log = log;
    }

    /** The game's assets changed: the pickers list them again. */
    void assetsChanged() {
        assetCache.clear();
    }

    /** Opens the window for a person. False when they aren't an online player. */
    boolean open(String personId) {
        PlayerRef pr;
        try {
            pr = Universe.get().getPlayer(UUID.fromString(personId));
        } catch (IllegalArgumentException e) {
            return false; // the console
        }
        if (pr == null) return false;
        WindowModel m = windows.computeIfAbsent(personId, id -> create(id, pr.getUsername()));
        return EditorPage.open(pr, m, s -> log.accept("[window] " + s));
    }

    void playerLeft(String personId) {
        WindowModel m = windows.get(personId);
        if (m != null && !m.unsaved()) windows.remove(personId); // an unsaved draft waits for them
    }

    private WindowModel create(String personId, String name) {
        EditorModel.Config config = new EditorModel.Config(serverRoot.toAbsolutePath().toString().replace('\\', '/'), devMods());
        EditorModel editor = new EditorModel(engine, profile, new GtaRater(), new FileStorage(), config, modVersions(), reload);
        editor.setLog(log);
        return new WindowModel(editor, engine.api(WindowModel.CALLER), profile, new Host(), config, personId, name,
                node -> port.hasPermission(personId, node));
    }

    /**
     * Mods open from their source folders (window.md section 3): {@code dev-mods.json} in BuffEffects' data
     * folder, mod id to folder. Config file only, never set in game, because it names folders on disk.
     */
    Map<String, String> devMods() {
        Path f = dataDir.resolve("dev-mods.json");
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(f)) return out;
            Object o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (o instanceof Map<?, ?> m) for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        } catch (IOException | RuntimeException e) {
            log.accept("couldn't read dev-mods.json: " + e);
        }
        return out;
    }

    static Map<String, String> modVersions() {
        Map<String, String> out = new HashMap<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            out.put(p.getManifest().getGroup() + ":" + p.getManifest().getName(), String.valueOf(p.getManifest().getVersion()));
        }
        return out;
    }

    final class Host implements WindowModel.Host {
        public List<TargetInfo> players() {
            List<TargetInfo> out = new ArrayList<>();
            for (PlayerRef p : Universe.get().getPlayers()) out.add(new TargetInfo(p.getUuid().toString(), p.getUsername(), TargetInfo.Kind.PLAYER, true, true));
            return out;
        }

        public List<String> nativeEffects() {
            return assets(GameProfile.Kind.EFFECT);
        }

        public List<String> assets(GameProfile.Kind kind) {
            return assetCache.computeIfAbsent(kind, WindowSupport::list);
        }

        public String nameOf(String target) {
            var t = port.target(target);
            return t == null || t.name() == null ? target : t.name();
        }
    }

    /** The ids of the game's assets of a kind, sorted; BuffEffects' own generated effects aren't listed. */
    static List<String> list(GameProfile.Kind kind) {
        Collection<?> keys;
        try {
            keys = switch (kind) {
                case EFFECT -> EntityEffect.getAssetMap().getAssetMap().keySet();
                case PARTICLE -> ParticleSystem.getAssetMap().getAssetMap().keySet();
                case MODEL_VFX -> ModelVFX.getAssetMap().getAssetMap().keySet();
                case SOUND -> SoundEvent.getAssetMap().getAssetMap().keySet();
                case MODEL -> ModelAsset.getAssetMap().getAssetMap().keySet();
                case STAT -> EntityStatType.getAssetMap().getAssetMap().keySet();
                default -> List.of();
            };
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object k : keys) {
            String s = String.valueOf(k);
            if (!s.startsWith("BFX_")) out.add(s);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /** Files on disk, with '/' paths. */
    final class FileStorage implements Storage {
        public String read(String path) {
            try {
                Path p = Path.of(path);
                return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
            } catch (IOException | RuntimeException e) {
                log.accept("couldn't read " + path + ": " + e);
                return null;
            }
        }

        public void write(String path, String text) {
            try {
                Path p = Path.of(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                BuffEffectsPlugin.write(p, text);
            } catch (IOException e) {
                throw new IllegalStateException("couldn't write " + path + ": " + e.getMessage(), e);
            }
        }

        public boolean isDirectory(String path) {
            return Files.isDirectory(Path.of(path));
        }
    }
}
