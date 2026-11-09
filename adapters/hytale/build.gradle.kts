// The Hytale plugin. The server jar is the API: compile-only, never bundled.
// The game-agnostic core is merged into the plugin jar, so server owners install a single file.
val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"

// CommandDeck's condition library API (hledjolf.conditions.api), compile-only: at run time it comes from
// CommandDeck's own jar (1.2.0 or later), which BuffEffects requires.
val commandDeckJar = (findProperty("commandDeckJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/UserData/Mods/CommandDeck-1.2.0.jar"

dependencies {
    "implementation"(project(":core"))
    "compileOnly"(files(hytaleServerJar))
    "compileOnly"(files(commandDeckJar))
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", rootProject.version.toString())
    filesMatching("manifest.json") { expand("version" to rootProject.version.toString()) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("BuffEffects")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    from(rootProject.file("LICENSE"))
}
