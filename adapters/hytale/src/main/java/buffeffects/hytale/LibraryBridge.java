package buffeffects.hytale;

import buffeffects.api.BuffEffects;
import buffeffects.core.engine.Conditions.Tri;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import hledjolf.conditions.api.Answer;
import hledjolf.conditions.api.Conditions;
import hledjolf.conditions.api.Context;
import hledjolf.conditions.api.Evaluation;
import hledjolf.conditions.api.JsonObjectView;
import hledjolf.conditions.api.Registered;
import hledjolf.conditions.api.TypeRegistration;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Consumer;

/**
 * BuffEffects and CommandDeck's condition library ({@code hledjolf.conditions.api}, conditions-api.md):
 * registers {@code buffeffects.has_effect} and {@code buffeffects.stacks}, and evaluates the {@code ends_when}
 * and message {@code variants} conditions the game doesn't check natively.
 * <p>
 * Only loaded when the library's classes are there (CommandDeck {@code >= 1.2.0}); {@link HytaleConditions}
 * checks first, so without CommandDeck this class is never touched.
 */
final class LibraryBridge {
    static final String OWNER = "Hledjolf:BuffEffects";
    private final Conditions lib;
    private final Consumer<String> log;

    private LibraryBridge(Conditions lib, Consumer<String> log) {
        this.lib = lib;
        this.log = log;
    }

    /** The library, when it's installed and started and speaks 1.x; else null. */
    static LibraryBridge find(Consumer<String> log) {
        Conditions c = Conditions.lookup();
        if (c == null) return null;
        if (!c.apiVersion().startsWith("1.")) {
            log.accept("the condition library speaks " + c.apiVersion() + "; BuffEffects knows 1.x, so only native conditions are checked");
            return null;
        }
        return new LibraryBridge(c, log);
    }

    /** Registers BuffEffects' own condition types (CD-D037). A reload registers again, which the library accepts. */
    void register(BuffEffects api) {
        report(lib.registerType(new TypeRegistration("buffeffects.has_effect", OWNER,
                "{\"type\":\"object\",\"required\":[\"type\",\"effect\"],\"properties\":{\"type\":{\"const\":\"buffeffects.has_effect\"},"
                        + "\"subject\":{\"type\":\"string\"},\"effect\":{\"type\":\"string\"},\"source\":{\"type\":\"string\"}},\"additionalProperties\":false}",
                (c, ctx) -> hasEffect(api, c, ctx),
                "The subject carries an effect: buffeffects:<mod id>/<id> for an ecosystem effect, hytale:<id> for the game's own. With source, only applications from that source id")),
                "buffeffects.has_effect");
        report(lib.registerType(new TypeRegistration("buffeffects.stacks", OWNER,
                "{\"type\":\"object\",\"required\":[\"type\",\"effect\",\"op\",\"value\"],\"properties\":{\"type\":{\"const\":\"buffeffects.stacks\"},"
                        + "\"subject\":{\"type\":\"string\"},\"effect\":{\"type\":\"string\"},\"op\":{\"enum\":[\"eq\",\"ne\",\"lt\",\"le\",\"gt\",\"ge\"]},"
                        + "\"value\":{\"type\":\"integer\"}},\"additionalProperties\":false}",
                (c, ctx) -> stacks(api, c, ctx),
                "The number of stacks of an effect on the subject compares as given")),
                "buffeffects.stacks");
    }

    private void report(Registered r, String type) {
        if (r.ok()) log.accept("registered condition type " + type);
        else log.accept("the condition library refused " + type + ": " + r.reason());
    }

    /** Whether the library can check this condition here: valid, and every type in it registered. */
    boolean canEvaluate(String json) {
        return lib.validate(json).isEmpty();
    }

    /**
     * Evaluates a condition for a bearer, on the game's thread. {@code {self}} and {@code {bearer}} are the
     * bearer, and {@code {caster}} the effect's actor (effects.md section 8).
     */
    Tri evaluate(String json, String bearer, String caster) {
        Map<String, String> ph = caster == null ? Map.of("bearer", bearer) : Map.of("bearer", bearer, "caster", caster);
        Evaluation e = lib.evaluate(json, new Context(bearer, null, ph, null, 0));
        return switch (e.answer()) {
            case TRUE -> Tri.TRUE;
            case FALSE -> Tri.FALSE;
            default -> Tri.UNKNOWN;
        };
    }

    // ---------------------------------------------------------------------------------------- evaluators

    private static Evaluation hasEffect(BuffEffects api, JsonObjectView c, Context ctx) {
        String target = subject(c, ctx);
        String effect = effectRef(c.string("effect"));
        if (target == null || effect == null) return unknown("subject or effect not resolved");
        Boolean b = api.has(target, effect, c.string("source"));
        return b == null ? unknown("the subject isn't loaded") : answer(Answer.of(b));
    }

    private static Evaluation stacks(BuffEffects api, JsonObjectView c, Context ctx) {
        String target = subject(c, ctx);
        String effect = effectRef(c.string("effect"));
        BigDecimal v = c.number("value");
        if (target == null || effect == null || v == null) return unknown("subject, effect or value not resolved");
        if (api.has(target, effect, null) == null) return unknown("the subject isn't loaded");
        int n = api.stacks(target, effect);
        int cmp = Integer.compare(n, v.intValue());
        String op = c.string("op") == null ? "eq" : c.string("op");
        boolean r = switch (op) {
            case "ne" -> cmp != 0;
            case "lt" -> cmp < 0;
            case "le" -> cmp <= 0;
            case "gt" -> cmp > 0;
            case "ge" -> cmp >= 0;
            default -> cmp == 0;
        };
        return answer(Answer.of(r));
    }

    /** {@code buffeffects:<full id>} is BuffEffects' own; {@code hytale:<id>} the game's (effects.md section 9). */
    static String effectRef(String e) {
        if (e == null) return null;
        if (e.startsWith("buffeffects:")) return e.substring("buffeffects:".length());
        if (e.startsWith("hytale:")) return e;
        return null;
    }

    /** {@code subject}: {@code {self}} (the default) is the actor, {@code {target}} the target, other placeholders from the context, else a player's name. */
    private static String subject(JsonObjectView c, Context ctx) {
        String s = c.string("subject");
        if (s == null || s.equals("{self}")) return ctx.actor();
        if (s.equals("{target}")) return ctx.target();
        if (s.startsWith("{") && s.endsWith("}")) return ctx.placeholders().get(s.substring(1, s.length() - 1));
        for (PlayerRef pr : Universe.get().getPlayers()) {
            if (pr.getUsername().equalsIgnoreCase(s)) return pr.getUuid().toString();
        }
        return null;
    }

    private static Evaluation answer(Answer a) {
        return new Evaluation(a, null);
    }

    private static Evaluation unknown(String why) {
        return new Evaluation(Answer.UNKNOWN, why);
    }
}
