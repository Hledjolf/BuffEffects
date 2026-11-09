package buffeffects.core.engine;

import buffeffects.api.MessageText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One application in the ledger: an effect on a target, from a caller. Every stack is an application of
 * its own, with its own timer. Mutable; only the engine changes it, under its lock.
 */
public final class Application {
    public final String id;
    public final String effect;
    public final String target;
    public String caller;
    public String sourceId;
    public String actor;
    public double strength;
    /** Run time left in milliseconds; -1 when there's no run-time end. */
    public long msLeft;
    /** The run time it was applied with (for {@code on_death: restart}); -1 when none. */
    public long appliedMs;
    public boolean infinite;
    /** The world time it ends at (in-game clock durations), or null. */
    public Long worldEnd;
    /** How {@link #worldEnd} was asked for: {@code dawn}, {@code 3 days}... */
    public String worldEndLabel;
    /** Order of arrival on the target, for "the first to arrive keeps the slot" and "the oldest stack". */
    public final long seq;
    /** Held back by its stacking category (a stronger one counts). */
    public boolean categoryDormant;
    /** Its {@code shape} waits for the {@code model} slot. */
    public boolean slotWaiting;
    /** Its target is offline, dead or unloaded: run time doesn't count. */
    public boolean paused;
    /** Kept through a death ({@code on_death} keep or restart): shown again on respawn. */
    public boolean keptThroughDeath;
    public List<String> droppedParts = new ArrayList<>();
    /** Parts stopped by a hit ({@code regen} with {@code breaks_on_hit}). */
    public Set<Integer> brokenParts = new TreeSet<>();
    /** Active run time since the last {@code every} message, per audience ({@code self}, {@code actor}, {@code nearby}). */
    public Map<String, Long> everyMs = new LinkedHashMap<>();
    /** Run time since {@code ends_when} was last checked. Not saved: a reload checks at once. */
    public long conditionMs;
    /** The caller's re-wordings of the effect's messages for this application (api.md, {@code messages}). */
    public List<MessageText> messages = new ArrayList<>();
    /** Its rating at the strength and duration applied (steps); null for a cliff or unrated. */
    public Double steps;

    /** Not saved: whether the game currently shows it (the port was told to activate it). */
    transient boolean shown;

    public Application(String id, String effect, String target, long seq) {
        this.id = id;
        this.effect = effect;
        this.target = target;
        this.seq = seq;
    }

    public boolean dormant() {
        return categoryDormant;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("effect", effect);
        m.put("target", target);
        m.put("seq", seq);
        m.put("caller", caller);
        if (sourceId != null) m.put("source_id", sourceId);
        if (actor != null) m.put("actor", actor);
        m.put("strength", strength);
        m.put("ms_left", msLeft);
        m.put("applied_ms", appliedMs);
        if (infinite) m.put("infinite", true);
        if (worldEnd != null) {
            m.put("world_end", worldEnd);
            m.put("world_end_label", worldEndLabel);
        }
        if (keptThroughDeath) m.put("kept_through_death", true);
        if (!droppedParts.isEmpty()) m.put("dropped_parts", new ArrayList<>(droppedParts));
        if (!brokenParts.isEmpty()) m.put("broken_parts", new ArrayList<>(brokenParts).stream().map(i -> (Object) (long) i).toList());
        if (!everyMs.isEmpty()) m.put("every_ms", new LinkedHashMap<String, Object>(everyMs));
        if (!messages.isEmpty()) {
            List<Object> ms = new ArrayList<>();
            for (MessageText t : messages) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("when", t.when());
                mm.put("to", t.to());
                if (t.text() != null) mm.put("text", t.text());
                if (t.pick() != null) mm.put("pick", new ArrayList<Object>(t.pick()));
                ms.add(mm);
            }
            m.put("messages", ms);
        }
        if (steps != null) m.put("steps", steps);
        return m;
    }

    static Application fromMap(Map<?, ?> m) {
        Application a = new Application((String) m.get("id"), (String) m.get("effect"), (String) m.get("target"),
                ((Number) m.get("seq")).longValue());
        a.caller = (String) m.get("caller");
        a.sourceId = (String) m.get("source_id");
        a.actor = (String) m.get("actor");
        a.strength = ((Number) m.get("strength")).doubleValue();
        a.msLeft = ((Number) m.get("ms_left")).longValue();
        a.appliedMs = m.get("applied_ms") instanceof Number ? ((Number) m.get("applied_ms")).longValue() : a.msLeft;
        a.infinite = Boolean.TRUE.equals(m.get("infinite"));
        if (m.get("world_end") instanceof Number) {
            a.worldEnd = ((Number) m.get("world_end")).longValue();
            a.worldEndLabel = (String) m.get("world_end_label");
        }
        a.keptThroughDeath = Boolean.TRUE.equals(m.get("kept_through_death"));
        if (m.get("dropped_parts") instanceof List) for (Object o : (List<?>) m.get("dropped_parts")) a.droppedParts.add((String) o);
        if (m.get("broken_parts") instanceof List) for (Object o : (List<?>) m.get("broken_parts")) a.brokenParts.add(((Number) o).intValue());
        if (m.get("every_ms") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("every_ms")).entrySet()) a.everyMs.put((String) e.getKey(), ((Number) e.getValue()).longValue());
        }
        if (m.get("messages") instanceof List) {
            for (Object o : (List<?>) m.get("messages")) {
                Map<?, ?> mm = (Map<?, ?>) o;
                List<String> pick = null;
                if (mm.get("pick") instanceof List) {
                    pick = new ArrayList<>();
                    for (Object x : (List<?>) mm.get("pick")) pick.add((String) x);
                }
                a.messages.add(new MessageText((String) mm.get("when"), (String) mm.get("to"), (String) mm.get("text"), pick));
            }
        }
        if (m.get("steps") instanceof Number) a.steps = ((Number) m.get("steps")).doubleValue();
        a.paused = true;
        return a;
    }
}
