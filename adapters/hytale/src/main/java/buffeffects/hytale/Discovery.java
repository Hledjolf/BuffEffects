package buffeffects.hytale;

import buffeffects.core.format.EffectLoader;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Finds sidecars until CommandDeck's shared loader does (UME-D085). Feeds {@link EffectLoader} exactly what
 * the shared loader will, so swapping it touches nothing else:
 * <ul>
 * <li>{@code Effects/<Group>_<Name>.effects.json} inside every loaded plugin's jar or folder (the path
 * mirrors Mod Settings Manager's {@code Settings/<Group>_<Name>.settings.json});</li>
 * <li>the {@code default} mod: {@code ecosystem/default/buffeffects.effects.json} under the server root,
 * with {@code mod.id} "default";</li>
 * <li>overrides: {@code ecosystem/overrides/<Group>_<Name>/buffeffects.effects.json}.</li>
 * </ul>
 */
final class Discovery {
    static final String OVERRIDE_FILE = "buffeffects.effects.json";

    final List<EffectLoader.Sidecar> sidecars = new ArrayList<>();
    final List<EffectLoader.OverrideFile> overrides = new ArrayList<>();
    final Set<String> installed = new LinkedHashSet<>();

    static Discovery scan(Path serverRoot, Consumer<String> log) {
        Discovery d = new Discovery();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            String mod = p.getManifest().getGroup() + ":" + p.getManifest().getName();
            d.installed.add(mod);
            if (!(p instanceof JavaPlugin)) continue;
            Path file = ((JavaPlugin) p).getFile();
            String entry = "Effects/" + p.getManifest().getGroup() + "_" + p.getManifest().getName() + ".effects.json";
            String text = null;
            try {
                if (file == null) continue;
                if (Files.isDirectory(file)) {
                    Path f = file.resolve(entry);
                    if (Files.isRegularFile(f)) text = Files.readString(f, StandardCharsets.UTF_8);
                } else if (Files.isRegularFile(file)) {
                    try (FileSystem fs = FileSystems.newFileSystem(file, Map.of())) {
                        Path f = fs.getPath(entry);
                        if (Files.isRegularFile(f)) text = Files.readString(f, StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.accept("couldn't read " + entry + " in " + file + ": " + e);
            }
            if (text != null) {
                d.sidecars.add(new EffectLoader.Sidecar(file.getFileName() + "!/" + entry, mod, String.valueOf(p.getManifest().getVersion()), text));
            }
        }
        Path eco = serverRoot.resolve("ecosystem");
        Path def = eco.resolve("default").resolve(OVERRIDE_FILE);
        if (Files.isRegularFile(def)) {
            try {
                d.sidecars.add(new EffectLoader.Sidecar(def.toString(), "default", null, Files.readString(def, StandardCharsets.UTF_8)));
                d.installed.add("default");
            } catch (IOException e) {
                log.accept("couldn't read " + def + ": " + e);
            }
        }
        Path ov = eco.resolve("overrides");
        if (Files.isDirectory(ov)) {
            try (Stream<Path> dirs = Files.list(ov)) {
                for (Path dir : dirs.toList()) {
                    Path f = dir.resolve(OVERRIDE_FILE);
                    if (!Files.isRegularFile(f)) continue;
                    String folder = dir.getFileName().toString();
                    int u = folder.indexOf('_');
                    if (u <= 0) {
                        log.accept("override folder " + folder + " isn't <Group>_<Name>: skipped");
                        continue;
                    }
                    String mod = folder.substring(0, u) + ":" + folder.substring(u + 1);
                    d.overrides.add(new EffectLoader.OverrideFile(f.toString(), mod, Files.readString(f, StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                log.accept("couldn't read " + ov + ": " + e);
            }
        }
        return d;
    }
}
