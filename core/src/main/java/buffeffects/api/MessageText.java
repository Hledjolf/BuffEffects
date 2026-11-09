package buffeffects.api;

import java.util.List;

/**
 * A caller's re-wording of one of an effect's messages, for one application ({@code apply}'s {@code messages}
 * option, api.md section 2). Matched to the effect's {@code message} parts by {@code when} and {@code to};
 * it replaces only the text. With neither {@code text} nor {@code pick}, it silences that message. One with
 * no matching part adds that message for this application.
 *
 * @param when {@code apply}, {@code refresh}, {@code expire}, {@code cure}, {@code remove}, {@code death} or {@code every}
 * @param to   {@code self}, {@code actor} or {@code nearby}; null is {@code self}
 * @param text one line (plain text or a {@code lang:} key), or null
 * @param pick lines to pick from at random, or null
 */
public record MessageText(String when, String to, String text, List<String> pick) {
    public MessageText {
        to = to == null ? "self" : to;
        pick = pick == null ? null : List.copyOf(pick);
    }

    public static MessageText of(String when, String text) {
        return new MessageText(when, "self", text, null);
    }

    public static MessageText silence(String when, String to) {
        return new MessageText(when, to, null, null);
    }

    public boolean silences() {
        return text == null && (pick == null || pick.isEmpty());
    }
}
