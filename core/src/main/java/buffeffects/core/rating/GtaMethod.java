package buffeffects.core.rating;

/**
 * Game Tactics Analysis's power method, ported from its reference implementation
 * (Game Tactics Analysis' {@code effect_spell_power.py}, the version of 2026-11-06 with fight
 * grades; method in {@code 05-effect-and-spell-power.md}). It reproduces {@code effect-spell-vectors.json} to the
 * printed precision. BuffEffects uses the effect ratings ({@link GtaRater}); the spell functions are here only
 * so the port can be checked against every vector, and spell cost stays SpellCrafter's.
 * <p>
 * Units are steps: one step is ×1.25 in time-to-kill terms. Zone 1's reference fight is the only one so far.
 * This is BuffEffects 1.0's own port, behind {@link PowerRater}, until CommandDeck's {@code commanddeck.power}
 * replaces it (CD-D052).
 */
public final class GtaMethod {
    private GtaMethod() {
    }

    public static final double STEP = 1.25;
    public static final double CAST_HITS = 2.0;
    public static final double FIGHT_CASTS = 10.0;
    public static final double POOL_CASTS = 8.0;
    public static final double DR_WINDOW_S = 15.0;

    /** A zone's reference fight: the player's health and resistance, the fight's length, a reference hit. */
    public record Ref(double hp, double resist, double fightS, double hit) {
    }

    /** Zone 1: a full iron set, an iron sword's light hit. */
    public static final Ref ZONE1 = new Ref(146.0, 0.25, 20.0, 12.67);

    /** What a part does while active: to the bearer's damage output, or to how long they survive. */
    public enum Kind { OUTPUT, SURVIVAL }

    public record Active(Kind kind, double ratio) {
    }

    /** A fight-level rating: steps is NaN for a cliff (rated infinite). */
    public record Fight(double uptime, double outputRatio, double survivalRatio, double steps, boolean cliff) {
    }

    public static double steps(double ratio) {
        if (Double.isInfinite(ratio)) return Double.POSITIVE_INFINITY;
        return Math.log(ratio) / Math.log(STEP);
    }

    public static double incomingDps(Ref ref) {
        return 0.5 * ref.hp() / ref.fightS();
    }

    /** The share of the reference fight an effect is active; a re-applicable one is limited by its cooldown. Null duration is the whole fight. */
    public static double uptime(Double durationS, Ref ref, Double cooldownS) {
        double t = ref.fightS();
        if (durationS == null) return 1.0;
        if (cooldownS != null && cooldownS > 0) {
            double active = 0, start = 0;
            while (start < t) {
                active += Math.min(durationS, Math.min(cooldownS, t - start));
                start += cooldownS;
            }
            return Math.min(1.0, active / t);
        }
        return Math.min(1.0, durationS / t);
    }

    static double fightRatioOutput(double active, double u) {
        return 1.0 + u * (active - 1.0);
    }

    static double fightRatioSurvival(double active, double u) {
        double inv = Double.isInfinite(active) ? 0.0 : 1.0 / active;
        double denom = (1.0 - u) + u * inv;
        return denom <= 0 ? Double.POSITIVE_INFINITY : 1.0 / denom;
    }

    // ---------------------------------------------------------------- the active ratios of parts, on the bearer

    public static Active damagePct(double pct) {
        return new Active(Kind.OUTPUT, 1.0 + pct / 100.0);
    }

    public static Active damageFlat(double points, Ref ref) {
        return new Active(Kind.OUTPUT, 1.0 + points / ref.hit());
    }

    /** +pct% resistance to one cause; {@code base} null is the zone's; {@code share} of incoming damage of that cause (1 is the best case). */
    public static Active resistPct(double pct, Ref ref, Double base, double share) {
        double r0 = base == null ? ref.resist() : base;
        double r1 = r0 + pct / 100.0;
        if (r1 >= 1.0) return new Active(Kind.SURVIVAL, Double.POSITIVE_INFINITY);
        double perCause = (1.0 - r0) / (1.0 - r1);
        return new Active(Kind.SURVIVAL, 1.0 / ((1.0 - share) + share / perCause));
    }

    public static Active maxHealth(double points, Ref ref) {
        return new Active(Kind.SURVIVAL, (ref.hp() + points) / ref.hp());
    }

    public static Active regen(double hpPerS, Ref ref) {
        double d = incomingDps(ref);
        if (hpPerS >= d) return new Active(Kind.SURVIVAL, Double.POSITIVE_INFINITY);
        return new Active(Kind.SURVIVAL, d / (d - hpPerS));
    }

    public static Active cannotBeHurt() {
        return new Active(Kind.SURVIVAL, Double.POSITIVE_INFINITY);
    }

    public static Active enemyCannotAttack() {
        return new Active(Kind.SURVIVAL, Double.POSITIVE_INFINITY);
    }

    /** The fight-level rating of an effect made of parts, active for {@code durationS} (null: the whole fight). */
    public static Fight rate(java.util.List<Active> parts, Double durationS, Ref ref, Double cooldownS) {
        double u = uptime(durationS, ref, cooldownS);
        double out = 1.0, surv = 1.0;
        for (Active a : parts) {
            if (a.kind() == Kind.OUTPUT) out *= fightRatioOutput(a.ratio(), u);
            else surv *= fightRatioSurvival(a.ratio(), u);
        }
        boolean cliff = Double.isInfinite(surv);
        return new Fight(u, out, surv, cliff ? Double.NaN : steps(out * surv), cliff);
    }

    // ------------------------------------------------------------------------------------------- spells

    public static double areaTargets(double radius, double pack) {
        return Math.min(pack, 1.0 + 0.15 * radius * radius);
    }

    public static double referenceCast(Ref ref) {
        return CAST_HITS * ref.hit();
    }

    /** An effect payload's damage-equivalent value: the share of a reference fight it buys. Infinite for a cliff. */
    public static double effectValue(Fight r, Ref ref) {
        if (r.cliff()) return Double.POSITIVE_INFINITY;
        return (r.outputRatio() * r.survivalRatio() - 1.0) * FIGHT_CASTS * referenceCast(ref);
    }

    public static double spellGrade(double value, Ref ref) {
        return steps(value / referenceCast(ref));
    }

    public static double gateMultiplier(double p) {
        return Math.pow(p, 0.5);
    }

    public static double spellCost(double value, Ref ref, double... gatePs) {
        double c = Math.pow(value / referenceCast(ref), 1.28);
        for (double p : gatePs) c *= gateMultiplier(p);
        return c;
    }

    /** How many times a spell is cast in the reference fight, by its cooldown (or cast time) and the mana pool. */
    public static int castsInFight(Ref ref, Double cost, Double cooldownS) {
        double t = ref.fightS();
        int byTime = cooldownS != null && cooldownS > 0 ? 1 + (int) Math.floor(t / cooldownS) : (int) Math.floor(t / 1.0);
        int byMana = cost != null && cost > 0 ? (int) Math.floor(POOL_CASTS / cost) : byTime;
        return Math.max(1, Math.min(byTime, byMana));
    }

    /** Steps a spell (or a once-applied effect such as an instant heal or a hit) adds to the reference fight. */
    public static double fightGrade(double valuePerCast, int casts, Ref ref) {
        double w = FIGHT_CASTS * referenceCast(ref);
        return steps(1.0 + casts * valuePerCast / w);
    }

    /** Seconds a crowd-control effect is active on one target in the reference fight, recast every cooldown, with diminishing returns. */
    public static double ccUptime(double durationS, Ref ref, Double cooldownS, boolean diminishing) {
        double t = ref.fightS();
        if (cooldownS == null || cooldownS <= 0) return Math.min(durationS, t);
        double total = 0, now = 0;
        int chain = 0;
        Double last = null;
        while (now < t) {
            if (diminishing && last != null && now - last < DR_WINDOW_S) chain++;
            else chain = 0;
            if (chain <= 2) {
                double d = durationS / (1 << chain);
                total += Math.min(d, t - now);
                last = now;
            }
            now += Math.max(cooldownS, 0.001);
        }
        return Math.min(total, t);
    }

    public static double ccFightGrade(double durationS, Ref ref, Double cooldownS, double shareCaught, boolean diminishing) {
        double u = ccUptime(durationS, ref, cooldownS, diminishing) / ref.fightS();
        double removed = u * Math.min(1.0, shareCaught);
        return removed >= 1.0 ? Double.POSITIVE_INFINITY : steps(1.0 / (1.0 - removed));
    }

    public static double expectedShareCaught(double targets, double pack) {
        return Math.min(1.0, targets / pack);
    }

    /** Python's round(x, n), close enough for the vectors' printed precision. */
    public static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.rint(v * f) / f;
    }
}
