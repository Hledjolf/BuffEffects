package buffeffects.api;

import java.util.function.Function;

/**
 * The lookup. BuffEffects' plugin publishes its engine here during setup; a caller asks for an instance
 * bound to its own mod id. Callers that must work without BuffEffects check that this class exists
 * before touching it (docs/api-java.md, "Optional dependency").
 */
public final class BuffEffectsApi {
    private static volatile Function<String, BuffEffects> provider;

    private BuffEffectsApi() {
    }

    /** An instance bound to {@code callerModId} ({@code Group:Name}), or null before BuffEffects has set up. */
    public static BuffEffects forCaller(String callerModId) {
        Function<String, BuffEffects> p = provider;
        return p == null ? null : p.apply(callerModId);
    }

    /** BuffEffects' own plugin calls this at setup, and with null at shutdown. */
    public static void publish(Function<String, BuffEffects> p) {
        provider = p;
    }
}
