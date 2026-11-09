package buffeffects.core.engine;

import buffeffects.core.format.EffectDef;
import java.util.List;
import java.util.Map;

/**
 * What the engine asks of the game, implemented by each adapter. The engine owns timers, stacks, slots
 * and removal; the port only shows an application in the game (activate), takes it out (deactivate), and
 * answers questions. The engine calls it while holding its lock: an adapter that must touch the game on a
 * world thread queues the work and returns.
 */
public interface GamePort {
    /** The game's profile name, the key of its bindings. */
    String game();

    /** What the engine needs to know about a target, or null when the game doesn't know it at all. */
    TargetInfo target(String targetId);

    /** Whether a person (a player id) holds a permission node. Operators hold every node. */
    boolean hasPermission(String personId, String node);

    /** Whether the target carries the game's own effect (for {@code immune_if: ["hytale:Immunity_Fire"]}). */
    boolean carriesNative(String targetId, String gameEffectId);

    /**
     * Shows an application in the game: its parts, its natives and its looks, for {@code msLeft} of run time
     * (-1: no run-time end). Called when it starts, when a dormant one becomes active, after the target
     * comes back (login, respawn, restart) and after a refresh. Must be safe to call when it's already shown.
     *
     * @param playOnApply whether to play {@code look.on_apply} (false on a refresh or a return)
     * @param holdShape   true while the {@code model} slot belongs to another application
     */
    void activate(Application app, EffectDef def, long msLeft, boolean playOnApply, boolean holdShape);

    /** Takes an application out of the game. {@code playOnRemove}: whether to play {@code look.on_remove}. */
    void deactivate(Application app, EffectDef def, boolean playOnRemove);

    /** Stops one part (a {@code regen} broken by a hit), leaving the rest. */
    void stopPart(Application app, EffectDef def, int partIndex);

    /** The strength actually delivered for a requested one (Hytale quantizes); the identity by default. */
    default double quantizeStrength(EffectDef def, double strength) {
        return strength;
    }

    /**
     * The parts of the effect this game can't do and which are dropped ({@code required: false}), as
     * {@code type} names. Empty by default.
     */
    default List<String> droppedParts(EffectDef def) {
        return List.of();
    }

    /**
     * The combined {@code multiply} factor per stat on one target, as BuffEffects' rule has it: summed bonuses,
     * {@code 1 + Î£(m âˆ’ 1)} (effects.md section 6). The adapter applies each as one modifier on the target and
     * drops the stats that are gone. Stats are as written in the effects: neutral names or {@code <game>:<id>}.
     */
    default void setStatMultipliers(String targetId, Map<String, Double> factors) {
    }

    /** Says a line ({@code lines} part): to the bearer, or to everyone within {@code radius} blocks. */
    void say(String targetId, boolean nearby, double radius, String text);

    /** The target's in-game clock, in milliseconds of world time. */
    long worldTime(String targetId);

    /** The world time at which a clock point ({@code dawn}, {@code dusk}, {@code end_of_day}) or N days from now comes. */
    long worldTimeAt(String targetId, String point, int days);

    /** A stat's maximum on the target ({@code health}...), for percent amounts in caps. NaN when unknown. */
    double statMax(String targetId, String stat);

    /** The game's own ceilings, which caps never go below. */
    CapProfile capProfile();

    /** The zone's reference numbers for the target (incoming damage, vanilla maximum resistance). */
    default Zone zone(String targetId) {
        return Zone.DEFAULT;
    }

    /**
     * The zone's reference numbers. {@code incomingDps}: damage per second a reference fight deals here
     * after mitigation (Game Tactics Analysis: 3.65 in zone 1). {@code maxVanillaResist}: the highest
     * resistance vanilla gear and effects give here, per cause, or NaN for no such limit.
     */
    record Zone(double incomingDps, double maxVanillaResist) {
        public static final Zone DEFAULT = new Zone(3.65, Double.NaN);
    }

    /** Runs an event delivery. The default runs it at once; an adapter may move it to the target's world thread. */
    default void deliver(Runnable r) {
        r.run();
    }

    /** Runs an event delivery for one target, on its world thread where the game has one. Defaults to {@link #deliver(Runnable)}. */
    default void deliver(String targetId, Runnable r) {
        deliver(r);
    }
}
