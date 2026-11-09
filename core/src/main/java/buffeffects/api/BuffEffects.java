package buffeffects.api;

import java.util.List;

/**
 * BuffEffects' API (contract {@code buffeffects.api}, draft 0.1; meaning in design/specs/api.md, the Java
 * spelling in docs/api-java.md). An instance is bound to one caller: get it with
 * {@link BuffEffectsApi#forCaller(String)}, so BuffEffects knows who called without an option.
 * Every method is safe from any thread.
 */
public interface BuffEffects {
    /** The contract version, {@code "0.1"} while it's a draft. */
    String apiVersion();

    /** The caller this instance is bound to. */
    String caller();

    ApplyResult apply(String target, String effect, ApplyOptions options);

    /** The same checks as {@link #apply}, the same result, nothing applied. {@code target} may be null. */
    ApplyResult check(String target, String effect, ApplyOptions options);

    RemoveResult remove(String target, Selector selector);

    RemoveResult cure(String target, CureRequest cure);

    List<ActiveApplication> active(String target);

    /** TRUE or FALSE; null when the target isn't loaded (UNKNOWN for a condition). */
    Boolean has(String target, String effect, String sourceId);

    int stacks(String target, String effect);

    /** The definition as loaded, or null if no such effect is loaded. */
    EffectInfo describe(String effect);

    List<String> list(ListFilter filter);

    /** Subscribes to events (api.md section 6). Returns an id for {@link #unsubscribe}. */
    String subscribe(EventFilter filter, Listener listener);

    void unsubscribe(String subscriptionId);

    interface Listener {
        void onEvent(BfxEvent event);
    }
}
