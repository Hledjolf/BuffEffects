package buffeffects.core.engine;

import buffeffects.api.MessageText;
import buffeffects.core.format.EffectDef;
import buffeffects.core.format.Part;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * An effect's messages (effects.md section 6, "Messages"): which line goes to whom at each moment, with the
 * caller's re-wordings kept with the application, {@code variants} by circumstance, {@code once} per bearer,
 * no repeats with {@code pick}, placeholders, and the generic wear-off line. Used by the engine under its lock.
 */
final class Messages {
    private final GamePort port;
    /** {@code once} lines already said: {@code <bearer>|<effect>|<when>|<to>}. Saved with the ledger. */
    final Set<String> said = new LinkedHashSet<>();
    /** The last line picked from each {@code pick}, so it isn't picked twice in a row. Not saved. */
    private final Map<String, Integer> lastPick = new HashMap<>();
    /** The stack count of the effect being spoken for ({@code {stacks}}), set by each call. */
    private int stacks = 1;

    Messages(GamePort port) {
        this.port = port;
    }

    /** One message to send: its audience, its lines, its radius and the part it came from (null for a caller's own). */
    private record Planned(String to, List<String> lines, double radius, Part part) {
    }

    /**
     * Says what {@code d} has for moment {@code when} on application {@code a}. {@code genericWearOff} asks for
     * the generic line when the bearer gets no {@code expire} message of their own.
     */
    void moment(Application a, EffectDef d, String when, int stacks, Settings s, Conditions cond, Random random, boolean genericWearOff) {
        if (!s.messagesEnabled || d == null) return;
        this.stacks = stacks;
        List<Planned> plan = plan(a, d, when, cond, s);
        boolean bearerTold = false;
        boolean bearerSilenced = false;
        for (MessageText o : a.messages) if (o.when().equals(when) && o.to().equals("self") && o.silences()) bearerSilenced = true;
        for (Planned p : plan) {
            if (p.part != null && p.part.bool("once", false)) {
                if (!said.add(a.target + "|" + a.effect + "|" + when + "|" + p.to)) continue;
            }
            String line = pick(a, when, p.to, p.lines, random);
            if (line == null) continue;
            send(a, d, p.to, p.radius, line);
            if (p.to.equals("self")) bearerTold = true;
        }
        if (when.equals("expire") && genericWearOff && s.genericWearOff && !bearerTold && !bearerSilenced) {
            send(a, d, "self", 0, "{effect} has worn off.");
        }
    }

    /**
     * {@code every} messages: run time accumulates per audience, and a line goes out each interval. The first
     * comes one interval after applying.
     */
    void every(Application a, EffectDef d, long elapsedMs, int stacks, Settings s, Conditions cond, Random random) {
        if (!s.messagesEnabled || d == null) return;
        this.stacks = stacks;
        for (Part p : d.partsOn(port.game())) {
            if (!p.type().equals("message") || !"every".equals(p.str("when"))) continue;
            String to = p.str("to");
            long every = Math.round(p.num("every", 0) * 1000);
            if (every <= 0) continue;
            long acc = a.everyMs.getOrDefault(to, 0L) + elapsedMs;
            while (acc >= every) {
                acc -= every;
                List<String> lines = linesFor(a, p, "every", to, cond);
                if (lines == null) break;
                String line = pick(a, "every", to, lines, random);
                if (line != null) send(a, d, to, radius(p, s), line);
            }
            a.everyMs.put(to, acc);
        }
    }

    private List<Planned> plan(Application a, EffectDef d, String when, Conditions cond, Settings s) {
        Map<String, Planned> byTo = new LinkedHashMap<>();
        for (Part p : d.partsOn(port.game())) {
            if (!p.type().equals("message") || !when.equals(p.str("when"))) continue;
            String to = p.str("to");
            List<String> lines = linesFor(a, p, when, to, cond);
            if (lines != null) byTo.put(to, new Planned(to, lines, radius(p, s), p));
            else byTo.put(to, null);
        }
        // A caller's re-wording with no matching part adds that message for this application.
        for (MessageText o : a.messages) {
            if (!o.when().equals(when) || byTo.containsKey(o.to()) || o.silences() || when.equals("every")) continue;
            byTo.put(o.to(), new Planned(o.to(), lines(o), s.messagesRadius, null));
        }
        List<Planned> out = new ArrayList<>();
        for (Planned p : byTo.values()) if (p != null) out.add(p);
        return out;
    }

    /** The lines for one part: the caller's re-wording, else the first TRUE variant, else the part's own. Null when silenced. */
    private List<String> linesFor(Application a, Part p, String when, String to, Conditions cond) {
        for (MessageText o : a.messages) {
            if (o.when().equals(when) && o.to().equals(to)) return o.silences() ? null : lines(o);
        }
        Object vs = p.fields().get("variants");
        if (vs instanceof List) {
            for (Object v : (List<?>) vs) {
                Map<?, ?> vm = (Map<?, ?>) v;
                if (cond.evaluate(vm.get("if"), a.target, a.actor) == Conditions.Tri.TRUE) return lines(vm);
            }
        }
        return lines(p.fields());
    }

    private static List<String> lines(MessageText o) {
        return o.text() != null ? List.of(o.text()) : o.pick();
    }

    private static List<String> lines(Map<?, ?> m) {
        if (m.get("text") instanceof String) return List.of((String) m.get("text"));
        List<String> out = new ArrayList<>();
        if (m.get("pick") instanceof List) for (Object o : (List<?>) m.get("pick")) if (o instanceof String) out.add((String) o);
        return out;
    }

    private static double radius(Part p, Settings s) {
        return p.num("radius", s.messagesRadius);
    }

    /** One line at random, never the one picked last time for the same bearer, effect and moment. */
    private String pick(Application a, String when, String to, List<String> lines, Random random) {
        if (lines == null || lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        String key = a.target + "|" + a.effect + "|" + when + "|" + to;
        Integer last = lastPick.get(key);
        int k = random.nextInt(last == null ? lines.size() : lines.size() - 1);
        if (last != null && k >= last) k++;
        lastPick.put(key, k);
        return lines.get(k);
    }

    private void send(Application a, EffectDef d, String to, double radius, String line) {
        String text = line.startsWith("lang:") ? line : fill(a, d, line);
        switch (to) {
            case "actor": {
                if (a.actor == null) return;
                TargetInfo ai = port.target(a.actor);
                if (ai == null || ai.kind() != TargetInfo.Kind.PLAYER) return;
                port.say(a.actor, false, 0, text);
                return;
            }
            case "nearby":
                port.say(a.target, true, radius, text);
                return;
            default:
                port.say(a.target, false, 0, text);
        }
    }

    /** The placeholders: {name}, {actor}, {effect}, {time}, {stacks}. */
    private String fill(Application a, EffectDef d, String line) {
        if (line.indexOf('{') < 0) return line;
        TargetInfo bearer = port.target(a.target);
        TargetInfo actor = a.actor == null ? null : port.target(a.actor);
        // A lang: key can't be filled into a line here: fall back to the plain name, then the id.
        String effectName = d.displayName(stacks);
        if (effectName.startsWith("lang:")) {
            String base = d.name() == null || d.name().startsWith("lang:") ? d.id() : d.name();
            effectName = stacks > 1 ? base + " ×" + stacks : base;
        }
        return line.replace("{name}", bearer == null || bearer.name() == null ? "someone" : bearer.name())
                .replace("{actor}", actor == null || actor.name() == null ? "something" : actor.name())
                .replace("{effect}", effectName)
                .replace("{time}", readable(a.msLeft))
                .replace("{stacks}", String.valueOf(stacks));
    }

    /** Time left, readable: "45 s", "2 min", "1 h 5 min", "until removed". */
    static String readable(long ms) {
        if (ms < 0) return "until removed";
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + " s";
        long m = Math.round(s / 60.0);
        if (m < 60) return m + " min";
        return (m / 60) + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min");
    }
}
