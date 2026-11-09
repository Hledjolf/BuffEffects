package buffeffects.api;

/**
 * {@code apply}'s options (api.md section 2). Every field may be null.
 *
 * @param durationSeconds run time, within the effect's {@code caller_may_set.duration}
 * @param infinite        ask for an infinite application (an infinite effect, or a game's own effect)
 * @param strength        a factor within {@code caller_may_set.strength}; null is 1
 * @param sourceId        the caller's own id for what caused it, up to 120 characters
 * @param actor           the entity that caused it (an entity or player id)
 * @param asPerson        a player the application is made on behalf of; null makes it a server call
 * @param suppressOnApply skip the binding's {@code look.on_apply} and the effect's {@code apply} messages this time
 * @param worldClock      an in-game clock point ({@code dawn}...) overriding the effect's, if it allows it
 * @param messages        re-wordings of the effect's messages for this application (kept with it), or null
 */
public record ApplyOptions(Double durationSeconds, Boolean infinite, Double strength, String sourceId, String actor,
                           String asPerson, Boolean suppressOnApply, String worldClock, java.util.List<MessageText> messages) {
    public ApplyOptions {
        messages = messages == null ? java.util.List.of() : java.util.List.copyOf(messages);
    }

    public ApplyOptions(Double durationSeconds, Boolean infinite, Double strength, String sourceId, String actor,
                        String asPerson, Boolean suppressOnApply, String worldClock) {
        this(durationSeconds, infinite, strength, sourceId, actor, asPerson, suppressOnApply, worldClock, null);
    }

    public ApplyOptions withMessages(java.util.List<MessageText> m) {
        return new ApplyOptions(durationSeconds, infinite, strength, sourceId, actor, asPerson, suppressOnApply, worldClock, m);
    }

    public static ApplyOptions none() {
        return new ApplyOptions(null, null, null, null, null, null, null, null);
    }

    public ApplyOptions withDuration(double s) {
        return new ApplyOptions(s, infinite, strength, sourceId, actor, asPerson, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions withInfinite() {
        return new ApplyOptions(null, true, strength, sourceId, actor, asPerson, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions withStrength(double s) {
        return new ApplyOptions(durationSeconds, infinite, s, sourceId, actor, asPerson, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions withSource(String id) {
        return new ApplyOptions(durationSeconds, infinite, strength, id, actor, asPerson, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions withActor(String a) {
        return new ApplyOptions(durationSeconds, infinite, strength, sourceId, a, asPerson, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions asPerson(String person) {
        return new ApplyOptions(durationSeconds, infinite, strength, sourceId, actor, person, suppressOnApply, worldClock, messages);
    }

    public ApplyOptions suppressingOnApply() {
        return new ApplyOptions(durationSeconds, infinite, strength, sourceId, actor, asPerson, true, worldClock, messages);
    }
}
