package buffeffects.core.commands;

import buffeffects.api.ActiveApplication;
import buffeffects.api.ApplyOptions;
import buffeffects.api.ApplyResult;
import buffeffects.api.BuffEffects;
import buffeffects.api.CureRequest;
import buffeffects.api.EffectInfo;
import buffeffects.api.ListFilter;
import buffeffects.api.RemoveResult;
import buffeffects.api.Selector;
import buffeffects.core.engine.Engine;
import buffeffects.core.engine.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /bfx} commands (commands-settings.md section 1), game-neutral: a person, the text after
 * {@code /bfx}, and the lines to show back. Every command is logged like an API call, with the person
 * as {@code actor} and {@code caller: BuffEffects (command)}.
 */
public final class BfxCommands {
    public static final String CALLER = "BuffEffects (command)";

    private final Engine engine;
    private final CommandHost host;
    private final BuffEffects api;

    public BfxCommands(Engine engine, CommandHost host) {
        this.engine = engine;
        this.host = host;
        this.api = engine.api(CALLER);
    }

    /** Usage, one line per command. */
    public static List<String> help() {
        return List.of(
                "/bfx editor: open the effect editor",
                "/bfx list [<mod>|tag:<t>|category:<c>]: the loaded effects",
                "/bfx info <effect>: what it does, duration, games, power",
                "/bfx show [player]: the effects on you (or on a player)",
                "/bfx apply <player> <effect> [seconds] [strength]",
                "/bfx remove <player> <effect|all>",
                "/bfx cure <player> [harmful|tag:<t>|<effect>]",
                "/bfx check <player> <effect> [seconds] [strength]: a dry run",
                "/bfx optin <effect|mod:<mod id>>, /bfx optout ...: excessive effects on this server");
    }

    public List<String> run(String personId, String personName, String argLine) {
        String[] a = argLine == null || argLine.isBlank() ? new String[0] : argLine.trim().split("\\s+");
        String sub = a.length == 0 ? "editor" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "editor":
                    if (!host.hasPermission(personId, "buffeffects.editor")) return no("buffeffects.editor");
                    host.openEditor(personId);
                    return List.of("Opening the effect editor.");
                case "help":
                    return help();
                case "list":
                    return list(a);
                case "info":
                    return info(a);
                case "show":
                    return show(personId, a);
                case "apply":
                case "check":
                    return apply(personId, a, sub.equals("check"));
                case "remove":
                    return remove(personId, a);
                case "cure":
                    return cure(personId, a);
                case "optin":
                case "optout":
                    return opt(personId, a, sub.equals("optin"));
                default:
                    List<String> out = new ArrayList<>();
                    out.add("Unknown command \"" + sub + "\".");
                    out.addAll(help());
                    return out;
            }
        } catch (Usage u) {
            return List.of("Usage: " + u.getMessage());
        }
    }

    static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Usage(String m) {
            super(m);
        }
    }

    private static List<String> no(String node) {
        return List.of("You don't have permission (" + node + ").");
    }

    /** One effect from what was typed: a full id, a game id, or a short id that's unambiguous. */
    private String effect(String typed, List<String> out) {
        List<String> c = engine.resolve(typed);
        if (c.size() == 1) return c.get(0);
        if (c.isEmpty()) out.add("No effect \"" + typed + "\" is loaded.");
        else out.add("\"" + typed + "\" is defined by several mods; use the full id: " + String.join(", ", c));
        return null;
    }

    private String player(String name, List<String> out) {
        String id = host.findPlayer(name);
        if (id == null) out.add("No player \"" + name + "\" is online.");
        return id;
    }

    private List<String> list(String[] a) {
        ListFilter f = ListFilter.all();
        if (a.length > 1) {
            String x = a[1];
            if (x.startsWith("tag:")) f = new ListFilter(null, null, x.substring(4), null, null, null);
            else if (x.startsWith("category:")) f = new ListFilter(null, x.substring(9), null, null, null, null);
            else f = ListFilter.ofMod(x);
        }
        List<String> ids = api.list(f);
        List<String> out = new ArrayList<>();
        out.add(ids.size() + (ids.size() == 1 ? " effect:" : " effects:"));
        for (String id : ids) {
            EffectInfo i = api.describe(id);
            String mark = i.status().equals("active") ? "" : " [" + i.status() + "]";
            out.add("  " + id + mark);
        }
        return out;
    }

    private List<String> info(String[] a) {
        if (a.length < 2) throw new Usage("/bfx info <effect>");
        List<String> out = new ArrayList<>();
        String ref = effect(a[1], out);
        if (ref == null) return out;
        EffectInfo i = api.describe(ref);
        if (i == null) return List.of("No effect " + ref + " is loaded.");
        out.add(i.effect() + ": " + i.name() + (i.harmful() ? " (harmful)" : ""));
        if (i.description() != null) out.add("  " + i.description());
        out.add("  status: " + i.status() + (i.statusWhy() != null ? " (" + i.statusWhy() + ")" : "") + "; games: " + String.join(", ", i.games()));
        String power = i.cosmetic() ? "cosmetic" : !i.rated() ? "unrated" : i.cliff() ? "cliff (unbounded)" : i.derivedSteps() + " steps";
        out.add("  power: " + power + (i.declaredPower() != null ? ", declared " + i.declaredPower() : "")
                + (i.flags().isEmpty() ? "" : "; flags: " + String.join(", ", i.flags().stream().sorted().toList()))
                + (i.optedIn() ? "; opted in" : "") + (i.overridden() ? "; overridden by this server" : ""));
        return out;
    }

    private List<String> show(String personId, String[] a) {
        List<String> out = new ArrayList<>();
        String target = personId;
        if (a.length > 1) {
            if (!host.hasPermission(personId, "buffeffects.show.others")) return no("buffeffects.show.others");
            target = player(a[1], out);
            if (target == null) return out;
        }
        List<ActiveApplication> act = api.active(target);
        out.add(host.nameOf(target) + ": " + (act.isEmpty() ? "no effects." : act.size() + (act.size() == 1 ? " effect" : " effects")));
        for (ActiveApplication x : act) {
            String left = x.infinite() ? "until removed" : x.secondsLeft() != null ? Math.round(x.secondsLeft()) + " s left" : "";
            if (x.worldEnd() != null) left = (left.isEmpty() ? "" : left + ", ") + "until " + x.worldEnd();
            String shown = x.name() == null || x.name().startsWith("lang:") || x.name().equals(x.effect()) ? x.effect() : x.name() + " (" + x.effect() + ")";
            out.add("  " + shown + ": " + left + (x.stacks() > 1 ? ", " + x.stacks() + " stacks" : "")
                    + ", from " + x.caller() + (x.sourceId() != null ? " (" + x.sourceId() + ")" : "")
                    + (x.dormant() != null ? " [" + x.dormant() + "]" : "") + (x.paused() ? " [paused]" : ""));
        }
        return out;
    }

    private List<String> apply(String personId, String[] a, boolean check) {
        if (a.length < 3) throw new Usage("/bfx " + (check ? "check" : "apply") + " <player> <effect> [seconds] [strength]");
        if (!host.hasPermission(personId, "buffeffects.apply")) return no("buffeffects.apply");
        List<String> out = new ArrayList<>();
        String target = player(a[1], out);
        String ref = effect(a[2], out);
        if (target == null || ref == null) return out;
        ApplyOptions o = ApplyOptions.none().asPerson(personId).withActor(personId);
        try {
            if (a.length > 3) o = o.withDuration(Double.parseDouble(a[3]));
            if (a.length > 4) o = o.withStrength(Double.parseDouble(a[4]));
        } catch (NumberFormatException x) {
            throw new Usage("seconds and strength are numbers");
        }
        ApplyResult r = check ? api.check(target, ref, o) : api.apply(target, ref, o);
        return List.of((check ? "Check " : "") + ref + " on " + host.nameOf(target) + ": " + r.line());
    }

    private List<String> remove(String personId, String[] a) {
        if (a.length < 3) throw new Usage("/bfx remove <player> <effect|all>");
        if (!host.hasPermission(personId, "buffeffects.remove")) return no("buffeffects.remove");
        List<String> out = new ArrayList<>();
        String target = player(a[1], out);
        if (target == null) return out;
        int n = 0;
        if (a[2].equalsIgnoreCase("all")) {
            for (ActiveApplication x : api.active(target)) n += api.remove(target, Selector.application(x.applicationId())).removed().size();
        } else {
            String ref = effect(a[2], out);
            if (ref == null) return out;
            n = api.remove(target, Selector.effect(ref)).removed().size();
        }
        return List.of("Removed " + n + (n == 1 ? " application" : " applications") + " from " + host.nameOf(target) + ".");
    }

    private List<String> cure(String personId, String[] a) {
        if (a.length < 2) throw new Usage("/bfx cure <player> [harmful|tag:<t>|<effect>]");
        if (!host.hasPermission(personId, "buffeffects.cure")) return no("buffeffects.cure");
        List<String> out = new ArrayList<>();
        String target = player(a[1], out);
        if (target == null) return out;
        CureRequest c;
        String what = a.length > 2 ? a[2] : "harmful";
        if (what.equalsIgnoreCase("harmful")) {
            c = CureRequest.harmfulOnes();
        } else if (what.startsWith("tag:")) {
            c = CureRequest.tagged(what.substring(4));
        } else {
            String ref = effect(what, out);
            if (ref == null) return out;
            c = CureRequest.of(ref);
        }
        RemoveResult r = api.cure(target, new CureRequest(c.harmful(), c.tags(), c.effects(), personId, null, personId));
        return List.of("Cured " + r.removed().size() + (r.removed().size() == 1 ? " effect" : " effects") + " on " + host.nameOf(target) + ".");
    }

    private List<String> opt(String personId, String[] a, boolean in) {
        if (a.length < 2) throw new Usage("/bfx " + (in ? "optin" : "optout") + " <effect|mod:<mod id>>");
        if (!host.hasPermission(personId, "buffeffects.optin")) return no("buffeffects.optin");
        Settings s = engine.settings();
        String what;
        if (a[1].startsWith("mod:")) {
            what = a[1].substring(4);
            if (in) s.optedInMods.add(what);
            else s.optedInMods.remove(what);
        } else {
            List<String> out = new ArrayList<>();
            what = effect(a[1], out);
            if (what == null) return out;
            if (in) s.optedInEffects.add(what);
            else s.optedInEffects.remove(what);
        }
        host.saveSettings(s);
        host.reload();
        return List.of((in ? "Opted in: " : "Opted out: ") + what + ". Saved to the settings, and the effects were reloaded.");
    }
}
