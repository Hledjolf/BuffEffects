package buffeffects.core.rating;

import buffeffects.core.format.EffectDef;
import buffeffects.core.format.Part;
import buffeffects.core.rating.PowerRater.Rating;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prepares what the rater sees, and answers what BuffEffects decides without it: an effect that's only
 * looks, icons, shapes and messages is cosmetic; {@code intangible} (with its can't-attack) is cosmetic and
 * flagged {@code escape} (decided 2026-11-05).
 */
public final class Power {
    private Power() {
    }

    /** Part types that are never rated (effects.md section 11). */
    static final Set<String> UNRATED = Set.of("message", "intangible", "light");

    /** The parts a rating counts: everything but messages, intangible and what intangible added. */
    public static List<Part> ratedParts(List<Part> parts) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (UNRATED.contains(p.type())) continue;
            if ("intangible".equals(p.fields().get("added_by"))) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Rates an effect at a strength and a duration (seconds, null for the whole fight). Never null: an
     * effect with no rater, or with a part the rater can't rate, is {@link Rating#unrated(String)}.
     */
    public static Rating rate(EffectDef def, String game, List<Part> nativeParts, double strength, Double seconds,
                              PowerRater rater) {
        List<Part> all = new ArrayList<>(def.partsOn(game));
        all.addAll(nativeParts);
        List<Part> rated = new ArrayList<>();
        for (Part p : ratedParts(all)) rated.add(p.scaled(strength));
        Set<String> flags = new HashSet<>();
        if (def.has("intangible")) flags.add("escape");
        if (rated.isEmpty()) {
            flags.add("cosmetic");
            return new Rating(0.0, false, flags);
        }
        if (rater == null) return Rating.unrated("no_rater");
        Rating r = rater.rate(new PowerRater.Request(rated, seconds, game, def.harmful()));
        if (r == null) return Rating.unrated("unrated");
        flags.addAll(r.flags());
        return new Rating(r.steps(), r.cliff(), flags, r.note());
    }

    /** The duration a load-time rating assumes: the longest a caller may ask for. */
    public static Double ratedSeconds(EffectDef def) {
        if (!def.duration().hasRunTime()) return null;
        if (def.durationBounds() != null) return def.durationBounds().max();
        return def.duration().runMs() / 1000.0;
    }
}
