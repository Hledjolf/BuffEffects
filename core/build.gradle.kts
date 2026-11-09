// Game-agnostic plain Java. No game API is ever on this project's classpath (checkAdapterBoundary).
// Compiled for Java 17 so the same classes run on every Java game, and kept plain so it translates.
tasks.withType<JavaCompile> { options.release.set(17) }
