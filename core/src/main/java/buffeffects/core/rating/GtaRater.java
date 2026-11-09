package buffeffects.core.rating;

import buffeffects.core.format.NativeEffect;
import buffeffects.core.format.Part;
import buffeffects.core.rating.GtaMethod.Active;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BuffEffects 1.0's rater: effect parts turned into {@link GtaMethod}'s active ratios, rated on zone 1's
 * reference fight (effects.md section 11). Ratings are shown, never enforced, in 1.0.
 * <p>
 * Each part is rated as the change it makes to its bearer, signed: {@code outgoing} 0.75 is -1.29 steps.
 * A harmful effect's rating is the negative of the bearer's sum, which is what it's worth to the side that
 * applies it. A rating is always a finite number, a cliff ({@code cliff:<part>}) or unrated with a reason.
 * <ul>
 * <li>{@code outgoing} is a damage ratio; {@code resist} a percent resistance from no base, all incoming
 * damage of that cause (the best case, as GTA's caps and pricing use);</li>
 * <li>{@code stat} health: {@code add} is max health, {@code multiply} its ratio; other stats aren't rated;</li>
 * <li>{@code regen} health per second; {@code invulnerable} can't be hurt;</li>
 * <li>{@code instant} health and {@code hit} are once-applied values, rated as a fight grade of one cast:
 * {@code steps(1 + share of health)} and {@code steps(1 + damage / W)} (BFX-012, confirmed in BFX-D025);</li>
 * <li>a stun ({@code disable_actions} with attack), or {@code outgoing} at 0 or below, stops the bearer's
 * damage: what its opponents gain, as GTA's crowd control (a cliff only for the whole fight);</li>
 * <li>{@code speed}, {@code lock_movement} and {@code knockback} are flagged {@code mobility} and add no steps;</li>
 * <li>an active ratio of 0 or below (a {@code stat} multiplier, max health at 0) has no finite step
 * value: a cliff; so does anything infinite over the whole fight;</li>
 * <li>{@code damage_over_time} is rated as damage, like {@code hit}: {@code amount} times the ticks in
 * min(duration, fight), the first tick one interval after applying (SpellCrafter's rule, BFX-D030);</li>
 * <li>a game's field the adapter couldn't translate: unrated, {@code untranslated:<field>}.</li>
 * </ul>
 */
public final class GtaRater implements PowerRater {
    private final GtaMethod.Ref ref;

    public GtaRater() {
        this(GtaMethod.ZONE1);
    }

    public GtaRater(GtaMethod.Ref ref) {
        this.ref = ref;
    }

    public Rating rate(Request r) {
        List<Active> bearer = new ArrayList<>(); // what the bearer gains (or loses)
        List<Active> against = new ArrayList<>(); // what the bearer's opponents gain: the bearer can't hurt them
        List<String> bearerTypes = new ArrayList<>(), againstTypes = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        String unrated = null;
        double once = 1.0; // the bearer's once-applied ratio
        for (Part p : r.parts()) {
            Active a = null;
            switch (p.type()) {
                case "outgoing": {
                    double m = p.num("multiplier", 1);
                    // Dealing no damage at all is the same as not being able to attack (GTA's crowd control).
                    if (m <= 0) {
                        against.add(GtaMethod.enemyCannotAttack());
                        againstTypes.add("outgoing");
                    } else {
                        a = new Active(GtaMethod.Kind.OUTPUT, m);
                    }
                    break;
                }
                case "resist":
                    a = GtaMethod.resistPct(p.num("amount", 0) * 100, ref, 0.0, 1.0);
                    break;
                case "stat":
                    if (!"health".equals(p.str("stat"))) break;
                    a = "multiply".equals(p.str("op")) ? new Active(GtaMethod.Kind.SURVIVAL, p.num("amount", 1))
                            : GtaMethod.maxHealth(p.num("amount", 0), ref);
                    break;
                case "regen": {
                    if (!"health".equals(p.str("stat"))) break;
                    double amt = p.num("amount", 0);
                    if ("percent".equals(p.str("unit"))) amt = amt * ref.hp() / 100;
                    a = GtaMethod.regen(amt / p.num("interval", 1), ref);
                    break;
                }
                case "instant": {
                    if (!"health".equals(p.str("stat"))) break;
                    double share = "percent".equals(p.str("unit")) ? p.num("amount", 0) / 100 : p.num("amount", 0) / ref.hp();
                    once *= share >= 0 ? 1 + share : 1 / (1 - share);
                    break;
                }
                case "hit":
                    once /= 1 + Math.abs(p.num("amount", 0)) / (GtaMethod.FIGHT_CASTS * GtaMethod.referenceCast(ref));
                    break;
                case "invulnerable":
                    a = GtaMethod.cannotBeHurt();
                    break;
                case "disable_actions":
                    if (p.strings("actions").contains("attack")) {
                        against.add(GtaMethod.enemyCannotAttack());
                        againstTypes.add("disable_actions");
                    }
                    break;
                case "speed":
                case "lock_movement":
                case "knockback":
                    flags.add("mobility");
                    break;
                case "damage_over_time": {
                    double span = r.durationSeconds() == null ? ref.fightS() : Math.min(r.durationSeconds(), ref.fightS());
                    double interval = p.num("interval", 1);
                    // An interval of 0 is the game running it once (a hit); the first tick lands one interval in.
                    double ticks = interval > 0 ? Math.floor(span / interval + 1e-9) : 1;
                    double damage = Math.abs(p.num("amount", 0)) * ticks;
                    once /= 1 + damage / (GtaMethod.FIGHT_CASTS * GtaMethod.referenceCast(ref));
                    break;
                }
                case NativeEffect.UNTRANSLATED:
                    if (unrated == null) unrated = "untranslated:" + p.str("field");
                    break;
                default:
                    break;
            }
            if (a != null) {
                // An active ratio of 0 or below (max health gone, a multiplier of 0) has no step value: a cliff.
                if (!(a.ratio() > 0)) return Rating.cliffOf(p.type(), flags);
                bearer.add(a);
                bearerTypes.add(p.type());
            }
        }
        GtaMethod.Fight fb = GtaMethod.rate(bearer, r.durationSeconds(), ref, null);
        if (fb.cliff()) return Rating.cliffOf(firstInfinite(bearer, bearerTypes), flags);
        GtaMethod.Fight fa = GtaMethod.rate(against, r.durationSeconds(), ref, null);
        if (fa.cliff()) return Rating.cliffOf(againstTypes.get(0), flags);
        if (unrated != null) return new Rating(null, false, flags, unrated);
        double gain = GtaMethod.steps(fb.outputRatio() * fb.survivalRatio() * once)
                - GtaMethod.steps(fa.outputRatio() * fa.survivalRatio());
        if (!Double.isFinite(gain)) return new Rating(null, false, flags, "non_finite");
        double steps = GtaMethod.round(r.harmful() ? -gain : gain, 2);
        return new Rating(steps == 0 ? 0.0 : steps, false, flags);
    }

    private static String firstInfinite(List<Active> parts, List<String> types) {
        for (int k = 0; k < parts.size(); k++) if (Double.isInfinite(parts.get(k).ratio())) return types.get(k);
        return types.isEmpty() ? "survival" : types.get(0);
    }
}
