// BuffEffects: a game-agnostic core plus one adapter per game.
// core/ is plain Java compiled for Java 17; adapters/hytale/ is the Hytale plugin, on the game's own Java.
plugins { base }

version = "1.0.0"

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }
}
