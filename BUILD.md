# Building BuffEffects

You need a JDK 25 and two jars to compile against (neither is bundled):

- **Hytale's server jar,** `HytaleServer.jar`. By default the build looks in the launcher's install folder: `%APPDATA%\Hytale\install\release\package\game\latest\Server\HytaleServer.jar`.
- **CommandDeck 1.2.0's jar,** for its condition library API. By default: `%APPDATA%\Hytale\UserData\Mods\CommandDeck-1.2.0.jar`.

Elsewhere? Pass their paths:

```
gradlew jar -PhytaleServerJar=<path to HytaleServer.jar> -PcommandDeckJar=<path to CommandDeck-1.2.0.jar>
```

The plugin is written to `build\mods\BuffEffects-1.0.0.jar`. Put it in your mods folder, with Mod Settings Manager and CommandDeck 1.2.0 or later.

`docs\effects.schema.json` is the JSON schema for effect sidecars (`buffeffects.effects` 1.0).
