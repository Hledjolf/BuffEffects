package buffeffects.core.window;

import buffeffects.api.ActiveApplication;
import buffeffects.api.ApplyOptions;
import buffeffects.api.ApplyResult;
import buffeffects.api.BuffEffects;
import buffeffects.api.CureRequest;
import buffeffects.api.EffectInfo;
import buffeffects.api.RemoveResult;
import buffeffects.api.Selector;
import buffeffects.core.engine.TargetInfo;
import buffeffects.core.format.EffectLoader.Loaded;
import buffeffects.core.format.GameProfile;
import buffeffects.core.format.Issue;
import buffeffects.core.format.NativeEffect;
import buffeffects.core.format.Vocabulary;
import buffeffects.core.json.Json;
import buffeffects.core.rating.PowerRater.Rating;
import buffeffects.core.window.ViewOps.Child;
import buffeffects.core.window.ViewOps.Entry;
import buffeffects.core.window.ViewOps.Part;
import buffeffects.core.window.ViewOps.Ref;
import buffeffects.core.window.ViewOps.RowKind;
import buffeffects.core.window.ViewOps.Trigger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One person's BuffEffects window (window.md), game-neutral and tested headless: the four tabs, what's
 * selected, the draft's fields as rows, the pickers, and every button. It draws through {@link ViewOps};
 * the game's renderer turns those into its own UI. The data rules (drafts, validation, preview, where a
 * save goes) are {@link EditorModel}'s.
 * <p>
 * Simplified for 1.0 (agreed 2026-11-06): colours are typed as {@code #RRGGBB}, conditions as JSON text,
 * and asset pickers are a searchable list in the left pane.
 */
public final class WindowModel {
    public enum Tab { EFFECTS, EDITOR, ACTIVE, TEST }

    /** What the window needs from the game. */
    public interface Host {
        /** Online players. */
        List<TargetInfo> players();

        /** The game's own effect ids, without the game prefix. */
        List<String> nativeEffects();

        /** Ids of the game's assets of a kind, for the pickers; empty when the game can't list them. */
        List<String> assets(GameProfile.Kind kind);

        /** A target's display name, or the id itself. */
        String nameOf(String target);
    }

    public static final String CALLER = "BuffEffects (editor)";
    /** The most entries the list on the left shows; searching narrows it. */
    public static final int LIST_LIMIT = 120;
    static final String NATIVE_GROUP = "Hytale";

    /** Actions whose redraw waits until typing pauses (the page delays it). */
    public static boolean deferred(String action) {
        return action.equals("search") || action.equals("field");
    }

    // ------------------------------------------------------------------------------------------- fields

    /** How a field is edited and stored. */
    enum Kind { TEXT, NUMBER, BOOL, CHOICE, LIST, JSON }

    /** What a field writes to, besides a path in the draft. */
    enum Special { NONE, MOD, DURATION_KIND, TEST }

    /**
     * One field on the right: where it's stored ({@code path} into the draft, or a key of the Test tab),
     * how it's edited, its choices, a hint, whether changing it rebuilds the rows, and what a Pick button
     * lists. {@code sep} joins a list field's items.
     */
    record Field(String label, Kind kind, List<Object> path, List<Entry> choices, String hint, boolean structural,
                 GameProfile.Kind pick, Special special, String sep) {
        static Field of(String label, Kind kind, Object... path) {
            return new Field(label, kind, List.of(path), List.of(), null, false, null, Special.NONE, ", ");
        }

        Field hint(String h) {
            return new Field(label, kind, path, choices, h, structural, pick, special, sep);
        }

        Field choices(List<Entry> c) {
            return new Field(label, kind, path, c, hint, structural, pick, special, sep);
        }

        Field asStructural() {
            return new Field(label, kind, path, choices, hint, true, pick, special, sep);
        }

        Field pick(GameProfile.Kind k) {
            return new Field(label, kind, path, choices, hint, structural, k, special, sep);
        }

        Field special(Special s) {
            return new Field(label, kind, path, choices, hint, structural, pick, s, sep);
        }

        Field sep(String s) {
            return new Field(label, kind, path, choices, hint, structural, pick, special, s);
        }

        String pointer() {
            StringBuilder b = new StringBuilder();
            for (Object o : path) b.append('/').append(o);
            return b.toString();
        }
    }

    /** The parts a draft can have, grouped as in effects.md section 6. */
    static final List<String[]> PART_TYPES = List.of(
            new String[] {"Stats and healing", "stat"}, new String[] {"Stats and healing", "instant"},
            new String[] {"Stats and healing", "regen"}, new String[] {"Damage", "damage_over_time"},
            new String[] {"Damage", "resist"}, new String[] {"Damage", "outgoing"}, new String[] {"Damage", "hit"},
            new String[] {"Movement and actions", "speed"}, new String[] {"Movement and actions", "knockback"},
            new String[] {"Movement and actions", "lock_movement"}, new String[] {"Movement and actions", "disable_actions"},
            new String[] {"Flags", "invulnerable"}, new String[] {"Flags", "intangible"}, new String[] {"Look", "light"},
            new String[] {"Other", "message"});

    // -------------------------------------------------------------------------------------------- state

    private final EditorModel editor;
    private final BuffEffects api;
    private final GameProfile profile;
    private final Host host;
    private final String viewer;
    private final String viewerName;
    private final Predicate<String> permission;
    private final EditorModel.Config config;

    private Tab tab = Tab.EFFECTS;
    private String search = "";
    private String filter = "all";
    private String selected;
    private EditorModel.Draft draft;
    private String savedJson;
    private String pendingOpen;
    private boolean confirmDelete;
    private Integer picking;
    private String activeTarget;
    private final Map<String, Object> test = new LinkedHashMap<>();
    private String status = "";
    private final List<Field> rowFields = new ArrayList<>();

    public WindowModel(EditorModel editor, BuffEffects api, GameProfile profile, Host host, EditorModel.Config config,
                       String viewer, String viewerName, Predicate<String> permission) {
        this.editor = editor;
        this.api = api;
        this.profile = profile;
        this.host = host;
        this.config = config;
        this.viewer = viewer;
        this.viewerName = viewerName;
        this.permission = permission;
        test.put("target", viewer);
        test.put("strength", BigDecimal.ONE);
    }

    public Tab tab() {
        return tab;
    }

    public String status() {
        return status;
    }

    public EditorModel.Draft draft() {
        return draft;
    }

    public String selected() {
        return selected;
    }

    public boolean unsaved() {
        return draft != null && !Json.write(draft.effect, false).equals(savedJson);
    }

    // ------------------------------------------------------------------------------------------ drawing

    /** Everything after the page is built: the static bindings and both sides. */
    public ViewOps open() {
        ViewOps v = new ViewOps();
        for (Tab t : Tab.values()) {
            v.bind(new Ref(Part.TAB, t.ordinal(), Child.NONE), Trigger.ACTIVATE, "tab", Map.of("tab", t.name()), Map.of());
        }
        v.bind(Ref.of(Part.SEARCH), Trigger.CHANGE, "search", Map.of(), Map.of("value", Ref.of(Part.SEARCH)));
        v.bind(Ref.of(Part.FILTER), Trigger.CHANGE, "filter", Map.of(), Map.of("value", Ref.of(Part.FILTER)));
        return v.addAll(redraw());
    }

    /** The whole window as it is now. */
    ViewOps redraw() {
        ViewOps v = new ViewOps();
        for (Tab t : Tab.values()) v.disabled(new Ref(Part.TAB, t.ordinal(), Child.NONE), t == tab);
        v.value(Ref.of(Part.SEARCH), search);
        v.entries(Ref.of(Part.FILTER), filterEntries());
        v.value(Ref.of(Part.FILTER), filterEntries().stream().anyMatch(e -> e.value().equals(filter)) ? filter : "all");
        v.visible(Ref.of(Part.FILTER), picking == null && (tab == Tab.EFFECTS || tab == Tab.TEST || tab == Tab.EDITOR));
        drawList(v);
        drawRight(v);
        return v;
    }

    private List<Entry> filterEntries() {
        if (tab == Tab.ACTIVE) return List.of(new Entry("Everyone", "all"), new Entry("Players", "players"), new Entry("Creatures", "npcs"));
        return List.of(new Entry("All effects", "all"), new Entry("Harmful", "harmful"), new Entry("Beneficial", "beneficial"),
                new Entry("Cosmetic", "cosmetic"), new Entry("Excessive", "excessive"), new Entry("Dormant here", "dormant"),
                new Entry(NATIVE_GROUP + "'s own", "native"));
    }

    // ------------------------------------------------------------------------------------- the list

    private void drawList(ViewOps v) {
        v.clear(Part.LIST);
        int[] row = {0};
        int shown = 0, total = 0;
        if (picking != null && picking < rowFields.size() && rowFields.get(picking) != null) {
            Field f = rowFields.get(picking);
            List<String> ids = host.assets(f.pick());
            listRow(v, row, "Cancel picking", "pickCancel", Map.of(), false);
            heading(v, row, "Pick: " + f.label());
            for (String id : ids) {
                if (!matches(id, null)) continue;
                total++;
                if (shown >= LIST_LIMIT) continue;
                shown++;
                listRow(v, row, id, "picked", Map.of("value", f.pick() == GameProfile.Kind.EFFECT ? profile.name() + ":" + id : id), false);
            }
            count(v, shown, total, ids.isEmpty() ? "nothing to pick from" : "entries");
            return;
        }
        switch (tab) {
            case ACTIVE: {
                for (Map.Entry<String, List<ActiveApplication>> e : editor.active().entrySet()) {
                    String name = host.nameOf(e.getKey());
                    boolean player = isPlayer(e.getKey());
                    if (filter.equals("players") && !player) continue;
                    if (filter.equals("npcs") && player) continue;
                    if (!matches(name, e.getKey())) continue;
                    total++;
                    if (shown >= LIST_LIMIT) continue;
                    shown++;
                    listRow(v, row, name + " (" + e.getValue().size() + ")", "select", Map.of("id", e.getKey()), e.getKey().equals(activeTarget));
                }
                count(v, shown, total, total == 1 ? "target with effects" : "targets with effects");
                return;
            }
            case EDITOR:
                listRow(v, row, "+ New effect", "new", Map.of(), false);
                break;
            default:
                break;
        }
        // Effects, Editor and Test list the effects: by mod, then the game's own.
        EditorModel.Filters fl = switch (filter) {
            case "harmful" -> new EditorModel.Filters(true, null, null, null, null);
            case "beneficial" -> new EditorModel.Filters(false, null, null, null, null);
            case "cosmetic" -> new EditorModel.Filters(null, true, null, null, null);
            case "excessive" -> new EditorModel.Filters(null, null, true, null, null);
            case "dormant" -> new EditorModel.Filters(null, null, null, true, null);
            default -> EditorModel.Filters.none();
        };
        // The Editor marks only the open draft: a row marked current can't be pressed (playtest bfx-window-retest s2.1).
        String current = tab == Tab.TEST ? str(test.get("effect")) : tab == Tab.EDITOR ? (draft == null ? null : draft.openedFrom) : selected;
        if (!filter.equals("native")) {
            for (Map.Entry<String, List<EditorModel.Row>> g : editor.browse(search, fl).entrySet()) {
                boolean head = false;
                for (EditorModel.Row r : g.getValue()) {
                    total++;
                    if (shown >= LIST_LIMIT) continue;
                    if (!head) {
                        heading(v, row, g.getKey());
                        head = true;
                    }
                    shown++;
                    String mark = r.status().equals("active") ? "" : " [" + r.status() + "]";
                    listRow(v, row, r.name() + mark, "select", Map.of("id", r.effect()), r.effect().equals(current));
                }
            }
        }
        if (tab != Tab.EDITOR && (filter.equals("all") || filter.equals("native") || filter.equals("harmful") || filter.equals("beneficial"))) {
            boolean head = false;
            for (String id : host.nativeEffects()) {
                String full = profile.name() + ":" + id;
                if (!matches(id, full)) continue;
                if (filter.equals("harmful") || filter.equals("beneficial")) {
                    NativeEffect n = profile.nativeEffect(id);
                    boolean harmful = n != null && n.harmful();
                    if (harmful != filter.equals("harmful")) continue;
                }
                total++;
                if (shown >= LIST_LIMIT) continue;
                if (!head) {
                    heading(v, row, NATIVE_GROUP);
                    head = true;
                }
                shown++;
                listRow(v, row, id, "select", Map.of("id", full), full.equals(current));
            }
        }
        count(v, shown, total, total == 1 ? "effect" : "effects");
    }

    private boolean matches(String a, String b) {
        if (search.isBlank()) return true;
        String q = search.toLowerCase(Locale.ROOT).trim();
        return a.toLowerCase(Locale.ROOT).contains(q) || (b != null && b.toLowerCase(Locale.ROOT).contains(q));
    }

    private boolean isPlayer(String target) {
        for (TargetInfo t : host.players()) if (t.id().equals(target)) return true;
        return false;
    }

    private static void count(ViewOps v, int shown, int total, String what) {
        v.text(Ref.of(Part.COUNT), shown < total ? shown + " of " + total + " " + what + ": search to narrow" : total + " " + what);
    }

    private static void heading(ViewOps v, int[] row, String text) {
        v.append(Part.LIST, RowKind.LIST_HEADING);
        v.text(Ref.list(row[0]++, Child.NONE), text);
    }

    private static void listRow(ViewOps v, int[] row, String text, String action, Map<String, String> params, boolean current) {
        v.append(Part.LIST, RowKind.LIST_ROW);
        int r = row[0]++;
        v.text(Ref.list(r, Child.LABEL), text);
        v.disabled(Ref.list(r, Child.BUTTON), current);
        v.bind(Ref.list(r, Child.BUTTON), Trigger.ACTIVATE, action, params, Map.of());
    }

    // -------------------------------------------------------------------------------- the right side

    private void drawRight(ViewOps v) {
        v.clear(Part.ROWS);
        v.clear(Part.FOOTER);
        rowFields.clear();
        v.text(Ref.of(Part.PREVIEW), "");
        v.text(Ref.of(Part.PREVIEW_NOTE), "");
        v.text(Ref.of(Part.STATUS), status);
        switch (tab) {
            case EFFECTS -> drawEffect(v);
            case EDITOR -> drawEditor(v);
            case ACTIVE -> drawActive(v);
            case TEST -> drawTest(v);
        }
    }

    private int rows() {
        return rowFields.size();
    }

    private void note(ViewOps v, String text) {
        v.append(Part.ROWS, RowKind.NOTE_ROW);
        v.text(Ref.row(rows(), Child.NONE), text);
        rowFields.add(null);
    }

    /** A bold label and its value, on their own line. */
    private void fact(ViewOps v, String label, String value) {
        v.append(Part.ROWS, RowKind.FACT_ROW);
        int r = rows();
        v.text(Ref.row(r, Child.LABEL), label);
        v.text(Ref.row(r, Child.VALUE), value);
        rowFields.add(null);
    }

    private void section(ViewOps v, String text) {
        v.append(Part.ROWS, RowKind.SECTION_ROW);
        v.text(Ref.row(rows(), Child.NONE), text);
        rowFields.add(null);
    }

    private void footer(ViewOps v, String... buttons) {
        v.append(Part.FOOTER, RowKind.FOOTER_ROW);
        Child[] slots = {Child.B1, Child.B2, Child.B3, Child.B4, Child.B5, Child.B6};
        for (int k = 0; k < slots.length; k++) {
            boolean on = k < buttons.length;
            v.visible(Ref.footer(slots[k]), on);
            if (!on) continue;
            v.text(new Ref(Part.FOOTER, 0, slots[k]), buttons[k]);
            v.bind(Ref.footer(slots[k]), Trigger.ACTIVATE, "button", Map.of("name", buttons[k]), captures());
        }
    }

    /** Every field on the right, by row: what a button sends along. */
    private Map<String, Ref> captures() {
        Map<String, Ref> c = new LinkedHashMap<>();
        for (int k = 0; k < rowFields.size(); k++) if (rowFields.get(k) != null) c.put("f" + k, Ref.row(k, Child.INPUT));
        return c;
    }

    // ---- Effects

    private void drawEffect(ViewOps v) {
        if (selected == null) {
            v.text(Ref.of(Part.HEADING), "Effects");
            v.text(Ref.of(Part.META), "Every effect loaded on this server, by mod, and the game's own.");
            note(v, "Pick an effect on the left to see what it does, its power and where it comes from.");
            return;
        }
        EffectInfo i = api.describe(selected);
        if (i == null) {
            v.text(Ref.of(Part.HEADING), selected);
            v.text(Ref.of(Part.META), "Not loaded on this server.");
            return;
        }
        boolean nat = isNative(selected);
        v.text(Ref.of(Part.HEADING), i.name());
        v.text(Ref.of(Part.META), selected + " · " + (nat ? "the game's own effect" : i.mod()) + " · " + i.status()
                + (i.statusWhy() != null && !nat ? " (" + i.statusWhy() + ")" : "") + (i.overridden() ? " · overridden on this server" : ""));
        if (i.description() != null) note(v, i.description());
        Map<?, ?> def = i.definitionJson() == null ? Map.of() : (Map<?, ?>) Json.parse(i.definitionJson());
        fact(v, "Type", i.harmful() ? "Harmful" : "Not harmful");
        fact(v, "Duration", durationText(def.get("duration")));
        if (def.get("reapply") != null && !"refresh".equals(def.get("reapply"))) fact(v, "Applied again", reapplyWord(def));
        fact(v, "Power", power(i));
        section(v, "Parts");
        Object parts = def.get("parts");
        if (!(parts instanceof List<?> ps) || ps.isEmpty()) note(v, "None (only looks).");
        else for (Object p : ps) fact(v, partLabel(p), partDetails(p));
        Object bs = def.get("bindings");
        if (bs instanceof Map<?, ?> bm && bm.get(profile.name()) instanceof Map<?, ?> b && !b.isEmpty()) {
            section(v, "On " + profile.name());
            for (Map.Entry<?, ?> e : b.entrySet()) fact(v, words(String.valueOf(e.getKey())), prose(e.getValue()));
        }
        if (def.get("untranslated") instanceof List<?> u && !u.isEmpty()) fact(v, "Not translated", join(u));
        if (nat) footer(v, "Test", "Wrap in new effect");
        else {
            SaveTarget t = editor.target(editor.open(selected));
            if (t.kind() == SaveTarget.Kind.OVERRIDE) footer(v, "Edit", "Duplicate", "Test", "Disable here");
            else footer(v, "Edit", "Duplicate", "Test");
        }
    }

    static String power(EffectInfo i) {
        String flags = i.flags().isEmpty() ? "" : " · " + String.join(", ", i.flags().stream().sorted().toList());
        if (i.cosmetic()) return "cosmetic" + flags;
        if (i.cliff()) return "a cliff (" + (i.ratingNote() == null ? "unbounded" : i.ratingNote()) + ")" + flags;
        if (!i.rated()) return "unrated (" + (i.ratingNote() == null ? "not settled" : i.ratingNote()) + ")" + flags;
        return signed(i.derivedSteps()) + " steps" + flags + " (shown, not enforced in 1.0)";
    }

    private static String signed(Double d) {
        if (d == null) return "?";
        return (d > 0 ? "+" : "") + (Math.rint(d * 100) / 100.0);
    }

    static String durationText(Object d) {
        if (!(d instanceof Map<?, ?> m)) return "?";
        List<String> out = new ArrayList<>();
        if (Boolean.TRUE.equals(m.get("infinite"))) out.add("until removed");
        if (m.get("seconds") instanceof Number n) out.add(n.doubleValue() == 0 ? "instant" : trim(n) + " s");
        if (m.get("world") != null) out.add("until " + m.get("world"));
        if (m.get("world_days") instanceof Number n) out.add(trim(n) + " in-game days");
        return String.join(", or ", out);
    }

    private static String reapplyWord(Map<?, ?> def) {
        String r = String.valueOf(def.get("reapply"));
        if (def.get("stacking") instanceof Map<?, ?> st && !st.isEmpty()) r += " (" + prose(st) + ")";
        return r;
    }

    /** A part's type as a label: "damage_over_time" is "Damage over time". */
    static String partLabel(Object p) {
        return p instanceof Map<?, ?> m ? words(String.valueOf(m.get("type"))) : "Part";
    }

    /** A part's fields after its type: "cause physical, multiplier 0.75". */
    static String partDetails(Object p) {
        if (!(p instanceof Map<?, ?> m)) return String.valueOf(p);
        Map<Object, Object> rest = new LinkedHashMap<>(m);
        rest.remove("type");
        return rest.isEmpty() ? "(no settings)" : prose(rest);
    }

    /** A key as words, capitalized: "tint_top" is "Tint top". */
    static String words(String key) {
        String w = key.replace('_', ' ');
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** A value as plain text: maps as "key value, key value", nested ones in parentheses; lists with commas. */
    static String prose(Object o) {
        if (o instanceof Map<?, ?> m) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String v = prose(e.getValue());
                out.add(String.valueOf(e.getKey()).replace('_', ' ') + " " + (e.getValue() instanceof Map<?, ?> ? "(" + v + ")" : v));
            }
            return String.join(", ", out);
        }
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(x instanceof Map<?, ?> ? "(" + prose(x) + ")" : prose(x));
            return String.join(", ", out);
        }
        return compact(o);
    }

    static String compact(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Number n) return trim(n);
        return Json.write(o, false);
    }

    static String trim(Number n) {
        BigDecimal d = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
        return d.stripTrailingZeros().toPlainString();
    }

    private static String join(List<?> l) {
        List<String> s = new ArrayList<>();
        for (Object o : l) s.add(String.valueOf(o));
        return String.join(", ", s);
    }

    static boolean isNative(String ref) {
        return ref.indexOf('/') < 0 && ref.indexOf(':') > 0;
    }

    // ---- Editor

    private void drawEditor(ViewOps v) {
        if (draft == null) {
            v.text(Ref.of(Part.HEADING), "Editor");
            v.text(Ref.of(Part.META), "Build an effect, or change one.");
            note(v, "Press \"+ New effect\", or pick an effect on the left to open it. Saving writes it where it belongs: "
                    + "the default mod for a new one, its own source folder for a mod open from source, or this server's override.");
            footer(v, "New");
            return;
        }
        EditorModel.Preview p = editor.preview(draft);
        SaveTarget t = editor.target(draft);
        v.text(Ref.of(Part.HEADING), (draft.openedFrom == null ? "New: " : "") + str(draft.effect.get("name")) + (unsaved() ? " *" : ""));
        v.text(Ref.of(Part.META), draft.fullId() + " · " + t.message());
        Map<String, String> problems = new LinkedHashMap<>();
        for (Issue i : p.issues()) {
            if (i.severity() == Issue.Severity.LOG) continue;
            problems.merge(i.pointer() == null ? "" : i.pointer(), i.message(), (a, b) -> a + "; " + b);
        }
        section(v, "The effect");
        field(v, Field.of("Mod", Kind.CHOICE).choices(modChoices()).special(Special.MOD).asStructural()
                .hint("Where it's saved: the default mod, or a mod open from its source folder (config file)."), problems);
        field(v, Field.of("Id", Kind.TEXT, "id").hint("Lower case, digits and _"), problems);
        field(v, Field.of("Name", Kind.TEXT, "name"), problems);
        field(v, Field.of("Description", Kind.TEXT, "description"), problems);
        field(v, Field.of("Category", Kind.TEXT, "category"), problems);
        field(v, Field.of("Tags", Kind.LIST, "tags").hint("Comma-separated"), problems);
        field(v, Field.of("Harmful", Kind.BOOL, "harmful").hint("Cures and \"harmful\" filters use it"), problems);
        section(v, "Time and stacking");
        field(v, Field.of("Duration", Kind.CHOICE).special(Special.DURATION_KIND).asStructural().choices(List.of(
                new Entry("Seconds of run time", "seconds"), new Entry("Until removed", "infinite"), new Entry("In-game clock", "world"))), problems);
        Object dur = draft.effect.get("duration");
        if (dur instanceof Map<?, ?> dm && dm.containsKey("seconds")) {
            field(v, Field.of("Seconds", Kind.NUMBER, "duration", "seconds").hint("0 is instant: its once-only parts, then it ends"), problems);
        }
        if (dur instanceof Map<?, ?> dm && (dm.containsKey("world") || dm.containsKey("world_days"))) {
            field(v, Field.of("Until", Kind.CHOICE, "duration", "world").choices(List.of(new Entry("(none)", ""),
                    new Entry("Dawn", "dawn"), new Entry("Dusk", "dusk"), new Entry("End of the day", "end_of_day"))), problems);
            field(v, Field.of("In-game days", Kind.NUMBER, "duration", "world_days"), problems);
        }
        field(v, Field.of("Applied again", Kind.CHOICE, "reapply").asStructural().choices(List.of(new Entry("Refresh the time", "refresh"),
                new Entry("Extend the time", "extend"), new Entry("Ignore", "ignore"), new Entry("Stack", "stack"))), problems);
        if ("stack".equals(draft.effect.get("reapply"))) {
            field(v, Field.of("Max stacks", Kind.NUMBER, "stacking", "max_stacks"), problems);
            field(v, Field.of("Name per stack", Kind.LIST, "stacking", "names").sep(" | ")
                    .hint("Optional: one per stack, separated by |, as many as Max stacks"), problems);
        }
        field(v, Field.of("Stacking category", Kind.TEXT, "stacking", "category").hint("Effects in one category don't stack with each other"), problems);
        field(v, Field.of("On death", Kind.CHOICE, "on_death").choices(List.of(new Entry("Cleared", "clear"), new Entry("Kept", "keep"),
                new Entry("Restarted", "restart"))), problems);
        field(v, Field.of("Callers' duration, min", Kind.NUMBER, "caller_may_set", "duration", "min").hint("Seconds; leave both empty for a fixed duration"), problems);
        field(v, Field.of("Callers' duration, max", Kind.NUMBER, "caller_may_set", "duration", "max"), problems);
        field(v, Field.of("Callers' strength, min", Kind.NUMBER, "caller_may_set", "strength", "min").hint("A factor; leave both empty for 1"), problems);
        field(v, Field.of("Callers' strength, max", Kind.NUMBER, "caller_may_set", "strength", "max"), problems);
        section(v, "Ending, immunity and cures");
        field(v, Field.of("Ends when", Kind.JSON, "ends_when").hint("A condition as JSON: {\"type\": \"hytale.in_fluid\", \"fluid\": \"water\"}"), problems);
        field(v, Field.of("Immune if carrying", Kind.LIST, "immune_if").pick(GameProfile.Kind.EFFECT).hint("Effect ids, comma-separated"), problems);
        field(v, Field.of("Cure tags", Kind.LIST, "cure_tags").hint("Comma-separated"), problems);
        section(v, "Parts");
        List<?> parts = draft.effect.get("parts") instanceof List<?> l ? l : List.of();
        for (int k = 0; k < parts.size(); k++) {
            Object part = parts.get(k);
            String type = part instanceof Map<?, ?> pm ? str(pm.get("type")) : "?";
            v.append(Part.ROWS, RowKind.PART_ROW);
            int r = rows();
            v.text(Ref.row(r, Child.LABEL), (k + 1) + ". " + type);
            v.disabled(Ref.row(r, Child.UP), k == 0);
            v.disabled(Ref.row(r, Child.DOWN), k == parts.size() - 1);
            rowFields.add(null);
            for (Child c : new Child[] {Child.UP, Child.DOWN, Child.DEL}) {
                v.bind(Ref.row(r, c), Trigger.ACTIVATE, "part", Map.of("op", c.name().toLowerCase(Locale.ROOT), "index", String.valueOf(k)), captures());
            }
            String probl = problems.get("/parts/" + k);
            if (probl != null) note(v, "Problem: " + probl);
            for (Field f : partFields(type, k)) field(v, f, problems);
        }
        List<Entry> types = new ArrayList<>();
        for (String[] t2 : PART_TYPES) types.add(new Entry(t2[0] + ": " + t2[1], t2[1]));
        addRow(v, "Add a part", types, "addPart", "Add");
        section(v, "On " + profile.name());
        String g = profile.name();
        field(v, Field.of("Icon", Kind.TEXT, "bindings", g, "icon").hint("A texture path, such as Icons/Effects/Ghost.png"), problems);
        field(v, Field.of("Tint, top", Kind.TEXT, "bindings", g, "look", "while_active", "tint_top").hint("#RRGGBB; pale tints don't show"), problems);
        field(v, Field.of("Tint, bottom", Kind.TEXT, "bindings", g, "look", "while_active", "tint_bottom").hint("#RRGGBB"), problems);
        field(v, Field.of("See-through", Kind.NUMBER, "bindings", g, "look", "while_active", "see_through").hint("0 to 1: 0.9 is 90% see-through"), problems);
        field(v, Field.of("Particles", Kind.LIST, "bindings", g, "look", "while_active", "particles").pick(GameProfile.Kind.PARTICLE), problems);
        field(v, Field.of("Model VFX", Kind.TEXT, "bindings", g, "look", "while_active", "model_vfx").pick(GameProfile.Kind.MODEL_VFX), problems);
        field(v, Field.of("Screen effect", Kind.TEXT, "bindings", g, "look", "while_active", "screen_effect").hint("A texture path, such as ScreenEffects/Fire.png"), problems);
        field(v, Field.of("Pose", Kind.TEXT, "bindings", g, "look", "while_active", "animation").hint("A looping animation, such as FlyIdle"), problems);
        field(v, Field.of("Sound on apply", Kind.TEXT, "bindings", g, "look", "on_apply", "sound").pick(GameProfile.Kind.SOUND), problems);
        field(v, Field.of("Particles on apply", Kind.LIST, "bindings", g, "look", "on_apply", "particles").pick(GameProfile.Kind.PARTICLE), problems);
        field(v, Field.of("Sound on removal", Kind.TEXT, "bindings", g, "look", "on_remove", "sound").pick(GameProfile.Kind.SOUND), problems);
        field(v, Field.of("Shape", Kind.TEXT, "bindings", g, "shape").pick(GameProfile.Kind.MODEL).hint("A model the bearer becomes"), problems);
        field(v, Field.of("Game effects", Kind.LIST, "bindings", g, "native").pick(GameProfile.Kind.EFFECT).hint("The game's own effects applied with it"), problems);
        List<Entry> looks = new ArrayList<>();
        for (Map.Entry<String, List<EditorModel.Row>> e : editor.browse("", EditorModel.Filters.none()).entrySet()) {
            for (EditorModel.Row r : e.getValue()) looks.add(new Entry(r.name() + " (" + r.mod() + ")", r.effect()));
        }
        if (!looks.isEmpty()) addRow(v, "Copy the look from", looks, "copyLook", "Copy");
        String other = problems.getOrDefault("", null);
        StringBuilder all = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : problems.entrySet()) {
            n++;
            if (n <= 6) all.append(n == 1 ? "" : "\n").append(e.getKey().isEmpty() ? "" : e.getKey() + ": ").append(e.getValue());
        }
        if (n > 6) all.append("\n...and ").append(n - 6).append(" more");
        v.text(Ref.of(Part.PREVIEW), previewLine(p));
        v.text(Ref.of(Part.PREVIEW_NOTE), n == 0 ? capsLine(p) : "Not valid yet:\n" + all + (other == null ? "" : ""));
        footer(v, "Save", "Test on me", "Revert", "Duplicate", "Delete", "New");
    }

    private String previewLine(EditorModel.Preview p) {
        if (!p.valid()) return "Power: (fix the problems below first)";
        Rating r = p.rating();
        String flags = r == null || r.flags().isEmpty() ? "" : " · " + String.join(", ", r.flags().stream().sorted().toList());
        String what;
        if (r == null || r.isUnrated()) what = "unrated (" + (r == null || r.note() == null ? "not settled" : r.note()) + ")";
        else if (r.isCosmetic()) what = "cosmetic";
        else if (r.cliff()) what = "a cliff (" + r.note() + ")";
        else what = signed(r.steps()) + " steps";
        return "Power: " + what + flags + (p.excessive() ? " · above the curve (shown only in 1.0)" : "");
    }

    private static String capsLine(EditorModel.Preview p) {
        return p.caps().isEmpty() ? "Valid. No caps would be hit on a typical target at full strength."
                : "Valid. At full strength it would hit: " + String.join(", ", p.caps()) + ".";
    }

    private List<Entry> modChoices() {
        List<String> mods = new ArrayList<>();
        mods.add(EditorModel.DEFAULT_MOD);
        mods.addAll(config.devMods().keySet());
        if (draft != null && !mods.contains(draft.mod)) mods.add(draft.mod);
        List<Entry> out = new ArrayList<>();
        for (String m : mods) out.add(new Entry(m.equals(EditorModel.DEFAULT_MOD) ? "default (this server)" : m, m));
        return out;
    }

    /** The fields of one part type (effects.md section 6), at {@code parts/<index>}. */
    List<Field> partFields(String type, int index) {
        List<Field> f = new ArrayList<>();
        List<Entry> stats = new ArrayList<>();
        for (String s : List.of("health", "stamina", "mana")) stats.add(new Entry(s, s));
        for (String s : host.assets(GameProfile.Kind.STAT)) {
            if (!Vocabulary.STATS.contains(s.toLowerCase(Locale.ROOT))) stats.add(new Entry(s + " (" + profile.name() + ")", profile.name() + ":" + s));
        }
        List<Entry> causes = new ArrayList<>();
        for (String c : Vocabulary.CAUSE_PARENTS.keySet().stream().sorted().toList()) causes.add(new Entry(c + " (all of it)", c));
        for (String c : Vocabulary.CAUSE_LEAVES) causes.add(new Entry(c, c));
        for (String c : profile.extraCauses().stream().sorted().toList()) {
            // BFX_<cause>: the game's copy of an added neutral cause, already listed by its neutral name.
            if (!Vocabulary.isNeutralCause(c, java.util.Set.of()) && !c.startsWith("bfx_")) causes.add(new Entry(c, c));
        }
        List<Entry> units = List.of(new Entry("absolute", "absolute"), new Entry("percent", "percent"));
        switch (type) {
            case "stat" -> {
                f.add(p(index, "Stat", Kind.CHOICE, "stat").choices(stats));
                f.add(p(index, "How", Kind.CHOICE, "op").choices(List.of(new Entry("add to the maximum", "add"), new Entry("multiply the maximum", "multiply"))));
                f.add(p(index, "Amount", Kind.NUMBER, "amount"));
            }
            case "instant" -> {
                f.add(p(index, "Stat", Kind.CHOICE, "stat").choices(stats));
                f.add(p(index, "Amount", Kind.NUMBER, "amount").hint("Once, when applied; negative drains"));
                f.add(p(index, "Unit", Kind.CHOICE, "unit").choices(units));
            }
            case "regen" -> {
                f.add(p(index, "Stat", Kind.CHOICE, "stat").choices(stats));
                f.add(p(index, "Amount", Kind.NUMBER, "amount"));
                f.add(p(index, "Unit", Kind.CHOICE, "unit").choices(units));
                f.add(p(index, "Every (seconds)", Kind.NUMBER, "interval"));
                f.add(p(index, "Stops when hit", Kind.BOOL, "breaks_on_hit"));
            }
            case "damage_over_time" -> {
                f.add(p(index, "Cause", Kind.CHOICE, "cause").choices(causes));
                f.add(p(index, "Damage", Kind.NUMBER, "amount"));
                f.add(p(index, "Every (seconds)", Kind.NUMBER, "interval"));
            }
            case "resist" -> {
                f.add(p(index, "Cause", Kind.CHOICE, "cause").choices(causes));
                f.add(p(index, "Resistance", Kind.NUMBER, "amount").hint("A fraction: 0.3 is 30% less damage, -0.15 is 15% more"));
            }
            case "outgoing" -> {
                f.add(p(index, "Cause", Kind.CHOICE, "cause").choices(causes));
                f.add(p(index, "Multiplier", Kind.NUMBER, "multiplier").hint("Damage dealt: 1.25 is 25% more"));
            }
            case "hit" -> {
                f.add(p(index, "Cause", Kind.CHOICE, "cause").choices(causes));
                f.add(p(index, "Damage", Kind.NUMBER, "amount").hint("Once, when applied, through resistances"));
            }
            case "speed", "knockback" -> f.add(p(index, "Multiplier", Kind.NUMBER, "multiplier"));
            case "lock_movement" -> {
                for (String m : Vocabulary.MOVEMENTS) f.add(p(index, "Lock " + m, Kind.BOOL, m));
            }
            case "disable_actions" -> f.add(p(index, "Actions", Kind.LIST, "actions").hint(String.join(", ", Vocabulary.ACTIONS)));
            case "light" -> {
                f.add(p(index, "Colour", Kind.TEXT, "color").hint("#RRGGBB"));
                f.add(p(index, "Radius", Kind.NUMBER, "radius").hint("Blocks, 1 to 15; the brightest light on a bearer wins"));
            }
            case "message" -> {
                f.add(p(index, "When", Kind.CHOICE, "when").asStructural().choices(entries("apply", "refresh", "expire", "cure", "remove", "death", "every")));
                Object part = ((List<?>) draft.effect.get("parts")).get(index);
                if (part instanceof Map<?, ?> pm && "every".equals(pm.get("when"))) f.add(p(index, "Every (seconds)", Kind.NUMBER, "every"));
                f.add(p(index, "To", Kind.CHOICE, "to").asStructural().choices(entries("self", "actor", "nearby")));
                if (part instanceof Map<?, ?> pm && "nearby".equals(pm.get("to"))) f.add(p(index, "Radius", Kind.NUMBER, "radius"));
                f.add(p(index, "Sense", Kind.CHOICE, "sense").choices(withNone(entries("taste", "smell", "sound", "sight", "touch", "feeling"))));
                f.add(p(index, "Text", Kind.TEXT, "text").hint("{name}, {actor}, {effect}, {time}, {stacks}"));
                f.add(p(index, "Or one of", Kind.LIST, "pick").sep(" | ").hint("Lines separated by |, one picked at random"));
                f.add(p(index, "Variants", Kind.JSON, "variants").hint("JSON: [{\"if\": <condition>, \"text\": \"...\"}]"));
                f.add(p(index, "Once per bearer", Kind.BOOL, "once"));
            }
            default -> {
            }
        }
        f.add(p(index, "Required", Kind.BOOL, "required").hint("Off: the effect still loads where this part can't be done"));
        return f;
    }

    private static Field p(int index, String label, Kind kind, String key) {
        return Field.of(label, kind, "parts", index, key);
    }

    private static List<Entry> entries(String... values) {
        List<Entry> out = new ArrayList<>();
        for (String s : values) out.add(new Entry(s, s));
        return out;
    }

    private static List<Entry> withNone(List<Entry> e) {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("(none)", ""));
        out.addAll(e);
        return out;
    }

    private void addRow(ViewOps v, String label, List<Entry> choices, String action, String button) {
        v.append(Part.ROWS, RowKind.ADD_ROW);
        int r = rows();
        v.text(Ref.row(r, Child.LABEL), label);
        v.entries(Ref.row(r, Child.INPUT), choices);
        v.value(Ref.row(r, Child.INPUT), choices.get(0).value());
        v.text(Ref.row(r, Child.BUTTON), button);
        rowFields.add(null);
        Map<String, Ref> c = captures();
        c.put("value", Ref.row(r, Child.INPUT));
        v.bind(Ref.row(r, Child.BUTTON), Trigger.ACTIVATE, action, Map.of(), c);
    }

    private void field(ViewOps v, Field f, Map<String, String> problems) {
        RowKind kind = switch (f.kind()) {
            case BOOL -> RowKind.CHECK_ROW;
            case CHOICE -> RowKind.CHOICE_ROW;
            default -> RowKind.TEXT_ROW;
        };
        v.append(Part.ROWS, kind);
        int r = rows();
        rowFields.add(f);
        v.text(Ref.row(r, Child.LABEL), f.label());
        Object value = read(f);
        if (f.kind() == Kind.CHOICE) {
            List<Entry> choices = new ArrayList<>(f.choices());
            String val = value == null ? "" : String.valueOf(value);
            if (choices.stream().noneMatch(e -> e.value().equals(val))) choices.add(0, new Entry(val.isEmpty() ? "(none)" : val, val));
            v.entries(Ref.row(r, Child.INPUT), choices);
            v.value(Ref.row(r, Child.INPUT), val);
        } else if (f.kind() == Kind.BOOL) {
            boolean dflt = value == null && DEFAULT_TRUE.contains(String.valueOf(f.path().get(f.path().size() - 1)));
            v.value(Ref.row(r, Child.INPUT), Boolean.TRUE.equals(value) || dflt);
        } else {
            v.value(Ref.row(r, Child.INPUT), display(f, value));
        }
        if (kind == RowKind.TEXT_ROW) {
            v.visible(Ref.row(r, Child.PICK), f.pick() != null && !host.assets(f.pick()).isEmpty());
            if (f.pick() != null) v.bind(Ref.row(r, Child.PICK), Trigger.ACTIVATE, "pick", Map.of("row", String.valueOf(r)), captures());
        }
        String problem = problems.remove(f.pointer());
        String hint = problem != null ? "Problem: " + problem : f.hint();
        v.text(Ref.row(r, Child.HINT), hint);
        v.visible(Ref.row(r, Child.HINT), hint != null);
        v.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "field", Map.of("row", String.valueOf(r)), Map.of("value", Ref.row(r, Child.INPUT)));
    }

    private Object read(Field f) {
        switch (f.special()) {
            case MOD:
                return draft.mod;
            case DURATION_KIND: {
                Object d = draft.effect.get("duration");
                if (d instanceof Map<?, ?> m) {
                    if (Boolean.TRUE.equals(m.get("infinite"))) return "infinite";
                    if (m.containsKey("world") || m.containsKey("world_days")) return "world";
                }
                return "seconds";
            }
            case TEST:
                return test.get(String.valueOf(f.path().get(0)));
            default:
                Object v = get(draft.effect, f.path());
                if (v == null && f.path().size() == 1) return SHOWN_DEFAULTS.get(String.valueOf(f.path().get(0)));
                return v;
        }
    }

    static String display(Field f, Object v) {
        if (v == null) return "";
        switch (f.kind()) {
            case LIST: {
                if (!(v instanceof List<?> l)) return String.valueOf(v);
                List<String> s = new ArrayList<>();
                for (Object o : l) s.add(String.valueOf(o));
                return String.join(f.sep(), s);
            }
            case JSON:
                return Json.write(v, false);
            case NUMBER:
                return v instanceof Number n ? trim(n) : String.valueOf(v);
            default:
                return String.valueOf(v);
        }
    }

    /** A field's typed value as stored: null to remove it. A number or JSON that doesn't parse is kept as text, so validation reports it. */
    static Object parse(Field f, Object raw) {
        if (f.kind() == Kind.BOOL) return Boolean.TRUE.equals(raw) || "true".equals(String.valueOf(raw)) ? Boolean.TRUE : null;
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        switch (f.kind()) {
            case NUMBER:
                try {
                    BigDecimal d = new BigDecimal(s);
                    return d.scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0 ? (Object) d.longValueExact() : d;
                } catch (NumberFormatException | ArithmeticException e) {
                    return s;
                }
            case LIST: {
                List<Object> out = new ArrayList<>();
                String sep = f.sep().trim();
                for (String x : s.split(java.util.regex.Pattern.quote(sep))) if (!x.isBlank()) out.add(x.trim());
                return out.isEmpty() ? null : out;
            }
            case JSON:
                try {
                    return Json.parse(s);
                } catch (RuntimeException e) {
                    return s;
                }
            default:
                return s;
        }
    }

    /** True-or-false fields that are true when absent. */
    static final java.util.Set<String> DEFAULT_TRUE = java.util.Set.of("required", "breaks_on_hit");

    /**
     * Top-level fields shown with their default when absent. Writing the default back removes the key, as the
     * loaded form leaves it out: otherwise a button's captured fields mark a just-saved effect unsaved (bfx-window s2.7).
     */
    static final Map<String, String> SHOWN_DEFAULTS = Map.of("reapply", "refresh", "on_death", "clear");

    private void write(Field f, Object raw) {
        Object value = parse(f, raw);
        if (value != null && f.path().size() == 1 && value.equals(SHOWN_DEFAULTS.get(String.valueOf(f.path().get(0))))) value = null;
        if (f.kind() == Kind.BOOL && !f.path().isEmpty() && DEFAULT_TRUE.contains(String.valueOf(f.path().get(f.path().size() - 1)))) {
            value = value == null ? Boolean.FALSE : null;
        }
        switch (f.special()) {
            case MOD:
                if (value != null) draft.mod = String.valueOf(value);
                return;
            case DURATION_KIND: {
                Map<String, Object> d = new LinkedHashMap<>();
                String k = value == null ? "seconds" : String.valueOf(value);
                Object old = draft.effect.get("duration");
                switch (k) {
                    case "infinite" -> d.put("infinite", true);
                    case "world" -> d.put("world", "dawn");
                    default -> d.put("seconds", old instanceof Map<?, ?> m && m.get("seconds") instanceof Number n ? n : 30L);
                }
                draft.effect.put("duration", d);
                return;
            }
            case TEST:
                if (value == null) test.remove(String.valueOf(f.path().get(0)));
                else test.put(String.valueOf(f.path().get(0)), value);
                return;
            default:
                put(draft.effect, f.path(), value, profile.name());
        }
    }

    // ---- Active

    private void drawActive(ViewOps v) {
        if (activeTarget == null) {
            v.text(Ref.of(Part.HEADING), "Active");
            v.text(Ref.of(Part.META), "Who carries what right now.");
            note(v, editor.active().isEmpty() ? "Nobody carries an effect from BuffEffects right now." : "Pick someone on the left.");
            return;
        }
        List<ActiveApplication> apps = api.active(activeTarget);
        v.text(Ref.of(Part.HEADING), host.nameOf(activeTarget));
        v.text(Ref.of(Part.META), apps.size() + (apps.size() == 1 ? " effect" : " effects"));
        boolean canRemove = permission.test("buffeffects.remove");
        for (ActiveApplication a : apps) {
            v.append(Part.ROWS, RowKind.ITEM_ROW);
            int r = rows();
            rowFields.add(null);
            v.text(Ref.row(r, Child.LABEL), activeLine(a));
            v.visible(Ref.row(r, Child.BUTTON), canRemove);
            v.bind(Ref.row(r, Child.BUTTON), Trigger.ACTIVATE, "removeApp", Map.of("id", a.applicationId()), Map.of());
        }
        if (apps.isEmpty()) note(v, "Nothing any more.");
        if (canRemove) footer(v, "Refresh", "Cure harmful", "Remove all");
        else footer(v, "Refresh");
    }

    private String activeLine(ActiveApplication a) {
        List<String> bits = new ArrayList<>();
        bits.add(a.name() == null || a.name().startsWith("lang:") || a.name().equals(a.effect()) ? a.effect() : a.name() + " (" + a.effect() + ")");
        if (a.infinite()) bits.add("until removed");
        else if (a.secondsLeft() != null) bits.add(Math.round(a.secondsLeft()) + " s left");
        if (a.worldEnd() != null) bits.add("until " + a.worldEnd());
        if (a.stacks() > 1) bits.add("x" + a.stacks());
        if (a.sourceId() != null) bits.add("source " + a.sourceId());
        if (a.caller() != null) bits.add("by " + a.caller());
        if (a.actor() != null) bits.add("actor " + host.nameOf(a.actor()));
        if (a.dormant() != null) bits.add("dormant: " + a.dormant());
        return String.join(" · ", bits);
    }

    // ---- Test

    private void drawTest(ViewOps v) {
        v.text(Ref.of(Part.HEADING), "Test");
        v.text(Ref.of(Part.META), "Apply an effect, or try a dry run that applies nothing. Pick the effect on the left.");
        Field effect = new Field("Effect", Kind.TEXT, List.of("effect"), List.of(), "A full id, such as Hledjolf:CalendarSeasons/ghostified, or hytale:Burn",
                false, null, Special.TEST, ", ");
        field(v, effect, new LinkedHashMap<>());
        List<Entry> targets = new ArrayList<>();
        targets.add(new Entry("Me (" + viewerName + ")", viewer));
        for (TargetInfo t : host.players()) if (!t.id().equals(viewer)) targets.add(new Entry(t.name(), t.id()));
        field(v, new Field("On", Kind.CHOICE, List.of("target"), targets, null, false, null, Special.TEST, ", "), new LinkedHashMap<>());
        field(v, new Field("Seconds", Kind.NUMBER, List.of("seconds"), List.of(), "Empty: the effect's own duration", false, null, Special.TEST, ", "), new LinkedHashMap<>());
        field(v, new Field("Strength", Kind.NUMBER, List.of("strength"), List.of(), "A factor; 1 is as defined", false, null, Special.TEST, ", "), new LinkedHashMap<>());
        field(v, new Field("You are its actor", Kind.BOOL, List.of("actor"), List.of(), "For kill credit and \"to: actor\" messages", false, null, Special.TEST, ", "),
                new LinkedHashMap<>());
        String e = str(test.get("effect"));
        if (!e.isEmpty()) {
            EffectInfo i = api.describe(e);
            v.text(Ref.of(Part.PREVIEW), i == null ? "Not loaded: " + e : i.name() + ": " + power(i));
        }
        footer(v, "Apply", "Dry run", "Remove it");
    }

    // ------------------------------------------------------------------------------------------ events

    /**
     * Handles one event from the page: its action, and its data (params and captured values by name).
     * Returns what to redraw (possibly nothing).
     */
    public ViewOps onEvent(String action, Map<String, Object> data) {
        if (action == null) return new ViewOps();
        applyCaptures(data);
        switch (action) {
            case "tab":
                tab = Tab.valueOf(str(data.get("tab")));
                picking = null;
                filter = "all";
                status = "";
                // Nothing open yet: the Editor opens the effect picked on the Effects tab, as its Edit button does.
                if (tab == Tab.EDITOR && draft == null && selected != null && !isNative(selected)) openDraft(selected);
                return redraw();
            case "search":
                search = str(data.get("value"));
                return new ViewOps();
            case "filter":
                filter = str(data.get("value")).isEmpty() ? "all" : str(data.get("value"));
                return redraw();
            case "select":
                select(str(data.get("id")));
                return redraw();
            case "new":
                return newDraft();
            case "field":
                return field(data);
            case "pick":
                picking = Integer.valueOf(str(data.get("row")));
                search = "";
                status = "Pick on the left (search narrows the list).";
                return redraw();
            case "pickCancel":
                picking = null;
                status = "";
                return redraw();
            case "picked":
                picked(str(data.get("value")));
                return redraw();
            case "part":
                part(str(data.get("op")), Integer.parseInt(str(data.get("index"))));
                return redraw();
            case "addPart":
                addPart(str(data.get("value")));
                return redraw();
            case "copyLook":
                status = draft != null && editor.copyLookFrom(draft, str(data.get("value"))) ? "Look copied from " + data.get("value") + "." : "That effect has no look here.";
                return redraw();
            case "removeApp":
                removeApp(str(data.get("id")));
                return redraw();
            case "button":
                button(str(data.get("name")));
                return redraw();
            default:
                return new ViewOps();
        }
    }

    /** The redraw a deferred event waits for, built from the latest state. */
    public ViewOps deferredOps(String action) {
        ViewOps v = new ViewOps();
        if (action.equals("search")) {
            drawList(v);
            return v;
        }
        if (tab == Tab.EDITOR && draft != null) {
            EditorModel.Preview p = editor.preview(draft);
            v.text(Ref.of(Part.HEADING), (draft.openedFrom == null ? "New: " : "") + str(draft.effect.get("name")) + (unsaved() ? " *" : ""));
            v.text(Ref.of(Part.PREVIEW), previewLine(p));
            List<String> problems = new ArrayList<>();
            for (Issue i : p.issues()) if (i.severity() != Issue.Severity.LOG) problems.add((i.pointer() == null ? "" : i.pointer() + ": ") + i.message());
            v.text(Ref.of(Part.PREVIEW_NOTE), problems.isEmpty() ? capsLine(p) : "Not valid yet:\n" + String.join("\n", problems.subList(0, Math.min(6, problems.size()))));
        }
        return v;
    }

    private void applyCaptures(Map<String, Object> data) {
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("f") || k.length() < 2 || !Character.isDigit(k.charAt(1))) continue;
            int r = Integer.parseInt(k.substring(1));
            if (r < rowFields.size() && rowFields.get(r) != null && (rowFields.get(r).special() == Special.TEST || draft != null)) {
                Field f = rowFields.get(r);
                if (f.structural()) continue; // a structural change came with its own event and redraw
                write(f, e.getValue());
            }
        }
    }

    private ViewOps field(Map<String, Object> data) {
        int r = Integer.parseInt(str(data.get("row")));
        if (r >= rowFields.size() || rowFields.get(r) == null) return new ViewOps();
        Field f = rowFields.get(r);
        if (f.special() != Special.TEST && draft == null) return new ViewOps();
        write(f, data.get("value"));
        confirmDelete = false;
        return f.structural() ? redraw() : new ViewOps();
    }

    /** Whether a field's change redraws at once (a structural field) rather than once typing pauses. */
    public boolean immediate(Map<String, Object> data) {
        try {
            int r = Integer.parseInt(str(data.get("row")));
            return r < rowFields.size() && rowFields.get(r) != null && rowFields.get(r).structural();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void select(String id) {
        switch (tab) {
            case EFFECTS -> selected = id;
            case ACTIVE -> activeTarget = id;
            case TEST -> test.put("effect", id);
            case EDITOR -> {
                if (isNative(id)) return;
                if (unsaved() && !id.equals(pendingOpen)) {
                    pendingOpen = id;
                    status = "The draft has changes that aren't saved. Press the effect again to open it anyway.";
                    return;
                }
                openDraft(id);
            }
        }
    }

    private void openDraft(String id) {
        EditorModel.Draft d = editor.open(id);
        if (d == null) {
            status = id + " isn't loaded.";
            return;
        }
        draft = d;
        savedJson = Json.write(d.effect, false);
        pendingOpen = null;
        confirmDelete = false;
        picking = null;
        status = "Opened " + id + ".";
    }

    private ViewOps newDraft() {
        if (unsaved() && !"+new".equals(pendingOpen)) {
            pendingOpen = "+new";
            status = "The draft has changes that aren't saved. Press again to start a new one anyway.";
            return redraw();
        }
        String id = "new_effect";
        for (int k = 2; editor.open(EditorModel.DEFAULT_MOD + "/" + id) != null; k++) id = "new_effect_" + k;
        draft = editor.newDraft(null, id);
        savedJson = null;
        pendingOpen = null;
        confirmDelete = false;
        picking = null;
        tab = Tab.EDITOR;
        status = "A new effect in the default mod.";
        return redraw();
    }

    @SuppressWarnings("unchecked")
    private void picked(String value) {
        if (picking == null || picking >= rowFields.size() || rowFields.get(picking) == null || draft == null) {
            picking = null;
            return;
        }
        Field f = rowFields.get(picking);
        if (f.kind() == Kind.LIST) {
            Object old = get(draft.effect, f.path());
            List<Object> l = old instanceof List<?> ol ? new ArrayList<>((List<Object>) ol) : new ArrayList<>();
            if (!l.contains(value)) l.add(value);
            put(draft.effect, f.path(), l, profile.name());
        } else {
            put(draft.effect, f.path(), value, profile.name());
        }
        status = f.label() + ": " + value + ".";
        picking = null;
    }

    @SuppressWarnings("unchecked")
    private void part(String op, int index) {
        if (draft == null || !(draft.effect.get("parts") instanceof List<?>)) return;
        List<Object> parts = (List<Object>) draft.effect.get("parts");
        if (index < 0 || index >= parts.size()) return;
        switch (op) {
            case "up" -> {
                if (index > 0) parts.add(index - 1, parts.remove(index));
            }
            case "down" -> {
                if (index < parts.size() - 1) parts.add(index + 1, parts.remove(index));
            }
            case "del" -> {
                Object gone = parts.remove(index);
                status = "Removed part " + (index + 1) + " (" + (gone instanceof Map<?, ?> m ? m.get("type") : "?") + ").";
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addPart(String type) {
        if (draft == null || type.isEmpty()) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        switch (type) {
            case "stat" -> {
                p.put("stat", "health");
                p.put("op", "add");
                p.put("amount", 10L);
            }
            case "instant", "regen" -> {
                p.put("stat", "health");
                p.put("amount", 5L);
                p.put("unit", "absolute");
                if (type.equals("regen")) p.put("interval", 1L);
            }
            case "damage_over_time" -> {
                p.put("cause", "fire");
                p.put("amount", 2L);
                p.put("interval", 1L);
            }
            case "resist" -> {
                p.put("cause", "fire");
                p.put("amount", new BigDecimal("0.3"));
            }
            case "outgoing" -> {
                p.put("cause", "physical");
                p.put("multiplier", new BigDecimal("1.25"));
            }
            case "hit" -> {
                p.put("cause", "fire");
                p.put("amount", 10L);
            }
            case "speed", "knockback" -> p.put("multiplier", new BigDecimal("1.2"));
            case "lock_movement" -> p.put("jump", true);
            case "disable_actions" -> p.put("actions", new ArrayList<>(List.of("attack")));
            case "light" -> {
                p.put("color", "#FFE8B0");
                p.put("radius", 8L);
            }
            case "message" -> {
                p.put("when", "apply");
                p.put("text", "You feel different.");
            }
            default -> {
            }
        }
        Object parts = draft.effect.get("parts");
        if (!(parts instanceof List<?>)) draft.effect.put("parts", parts = new ArrayList<>());
        ((List<Object>) parts).add(p);
        status = "Added a " + type + " part.";
    }

    private void removeApp(String applicationId) {
        if (!permission.test("buffeffects.remove") || activeTarget == null) {
            status = "You don't have permission (buffeffects.remove).";
            return;
        }
        RemoveResult r = api.remove(activeTarget, Selector.application(applicationId));
        status = r.removed().isEmpty() ? "Nothing removed." : "Removed.";
    }

    private void button(String name) {
        switch (tab) {
            case EFFECTS -> effectsButton(name);
            case EDITOR -> editorButton(name);
            case ACTIVE -> activeButton(name);
            case TEST -> testButton(name);
        }
    }

    private void effectsButton(String name) {
        if (selected == null) return;
        switch (name) {
            case "Edit" -> {
                tab = Tab.EDITOR;
                if (!unsaved()) openDraft(selected);
                else select(selected);
            }
            case "Duplicate" -> {
                EditorModel.Draft d = editor.open(selected);
                if (d == null) return;
                draft = editor.duplicate(d, d.id() + "_copy");
                savedJson = null;
                tab = Tab.EDITOR;
                status = "A copy in " + draft.mod + ". Give it its own id, then save.";
            }
            case "Test" -> {
                test.put("effect", selected);
                tab = Tab.TEST;
                status = "";
            }
            case "Wrap in new effect" -> {
                String id = selected.substring(selected.indexOf(':') + 1).toLowerCase(Locale.ROOT);
                draft = editor.newDraft(null, id);
                put(draft.effect, List.of("bindings", profile.name(), "native"), new ArrayList<>(List.of(selected.substring(selected.indexOf(':') + 1))), profile.name());
                NativeEffect n = profile.nativeEffect(selected.substring(selected.indexOf(':') + 1));
                if (n != null) {
                    draft.effect.put("harmful", n.harmful());
                    if (n.seconds() == null) draft.effect.put("duration", new LinkedHashMap<>(Map.of("infinite", true)));
                    else draft.effect.put("duration", new LinkedHashMap<>(Map.of("seconds", BigDecimal.valueOf(n.seconds()).stripTrailingZeros())));
                }
                savedJson = null;
                tab = Tab.EDITOR;
                status = "A new effect that applies " + selected + ". Add parts or messages, then save.";
            }
            case "Disable here" -> {
                SaveTarget t = editor.disable(selected, viewerName);
                status = t == null ? "Only another mod's effect can be disabled here." : "Disabled on this server (" + t.path() + ").";
            }
            default -> {
            }
        }
    }

    private void editorButton(String name) {
        if (name.equals("New")) {
            newDraft();
            return;
        }
        if (draft == null) return;
        switch (name) {
            case "Save" -> {
                EditorModel.SaveResult r = editor.save(draft, viewerName);
                if (r.saved()) {
                    savedJson = Json.write(draft.effect, false);
                    draft = editor.open(draft.fullId()) != null ? reopened(draft.fullId()) : draft;
                    status = "Saved: " + r.target().message() + ".";
                } else {
                    status = "Not saved: fix the problems first.";
                }
                confirmDelete = false;
            }
            case "Test on me" -> {
                if (unsaved() || draft.openedFrom == null) {
                    status = "Save first: a test applies the effect as it's loaded.";
                    return;
                }
                test.put("effect", draft.fullId());
                test.put("target", viewer);
                status = applyLine(false);
            }
            case "Revert" -> {
                EditorModel.Draft d = editor.revert(draft);
                if (d == null) status = "A new effect has nothing to go back to.";
                else {
                    draft = d;
                    savedJson = Json.write(d.effect, false);
                    status = "Back to the loaded version.";
                }
            }
            case "Duplicate" -> {
                draft = editor.duplicate(draft, draft.id() + "_copy");
                savedJson = null;
                status = "A copy in " + draft.mod + ". Give it its own id, then save.";
            }
            case "Delete" -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    status = "Press Delete again to delete " + draft.fullId() + ". Only the default mod's effects and this server's overrides can be deleted.";
                    return;
                }
                confirmDelete = false;
                boolean ok = editor.delete(draft, viewerName);
                status = ok ? "Deleted " + draft.fullId() + "." : "Not deleted: only the default mod's effects and this server's overrides can be.";
                if (ok) {
                    draft = null;
                    savedJson = null;
                }
            }
            default -> {
            }
        }
    }

    private EditorModel.Draft reopened(String fullId) {
        EditorModel.Draft d = editor.open(fullId);
        savedJson = Json.write(d.effect, false);
        return d;
    }

    private void activeButton(String name) {
        if (activeTarget == null) return;
        if (!name.equals("Refresh") && !permission.test("buffeffects.remove")) {
            status = "You don't have permission (buffeffects.remove).";
            return;
        }
        switch (name) {
            case "Remove all" -> {
                int n = 0;
                for (ActiveApplication a : api.active(activeTarget)) n += api.remove(activeTarget, Selector.application(a.applicationId())).removed().size();
                status = "Removed " + n + ".";
            }
            case "Cure harmful" -> status = "Cured " + api.cure(activeTarget, CureRequest.harmfulOnes()).removed().size() + ".";
            default -> status = "";
        }
    }

    private void testButton(String name) {
        switch (name) {
            case "Apply" -> status = applyLine(false);
            case "Dry run" -> status = applyLine(true);
            case "Remove it" -> {
                String e = str(test.get("effect"));
                String t = str(test.get("target"));
                if (e.isEmpty() || t.isEmpty()) return;
                if (!permission.test("buffeffects.remove")) {
                    status = "You don't have permission (buffeffects.remove).";
                    return;
                }
                status = "Removed " + api.remove(t, Selector.effect(e)).removed().size() + ".";
            }
            default -> {
            }
        }
    }

    private String applyLine(boolean dry) {
        String e = str(test.get("effect"));
        String t = str(test.get("target"));
        if (e.isEmpty()) return "Pick an effect first.";
        if (!permission.test("buffeffects.apply")) return "You don't have permission (buffeffects.apply).";
        ApplyOptions o = ApplyOptions.none().asPerson(viewer);
        if (test.get("seconds") instanceof Number n) o = o.withDuration(n.doubleValue());
        else if (test.get("seconds") != null) return "Seconds must be a number.";
        if (test.get("strength") instanceof Number n) o = o.withStrength(n.doubleValue());
        else if (test.get("strength") != null) return "Strength must be a number.";
        if (Boolean.TRUE.equals(test.get("actor"))) o = o.withActor(viewer);
        ApplyResult r = dry ? api.check(t, e, o) : api.apply(t, e, o);
        return (dry ? "Dry run: " : "") + (r.applied() ? (dry ? "it would apply" : "applied") + (r.durationSeconds() == null ? "" : " for " + trim(r.durationSeconds()) + " s")
                + (r.droppedParts().isEmpty() ? "" : "; dropped here: " + String.join(", ", r.droppedParts()))
                : "refused, " + r.code() + ": " + r.message()) + ".";
    }

    // ------------------------------------------------------------------------------------ path helpers

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static Object get(Object root, List<Object> path) {
        Object cur = root;
        for (Object k : path) {
            if (cur instanceof Map<?, ?> m) cur = m.get(k);
            else if (cur instanceof List<?> l && k instanceof Integer i) cur = i < l.size() ? l.get(i) : null;
            else return null;
        }
        return cur;
    }

    /** Puts a value at a path, making the maps on the way; null removes it, and empty maps on the way go too. */
    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, List<Object> path, Object value, String game) {
        Object cur = root;
        for (int k = 0; k < path.size() - 1; k++) {
            Object key = path.get(k);
            Object next;
            if (cur instanceof Map<?, ?>) {
                Map<String, Object> m = (Map<String, Object>) cur;
                next = m.get(String.valueOf(key));
                if (!(next instanceof Map<?, ?>) && !(next instanceof List<?>)) {
                    if (value == null) return;
                    next = new LinkedHashMap<String, Object>();
                    m.put(String.valueOf(key), next);
                }
            } else if (cur instanceof List<?> && key instanceof Integer i) {
                List<Object> l = (List<Object>) cur;
                if (i >= l.size()) return;
                next = l.get(i);
            } else {
                return;
            }
            cur = next;
        }
        Object last = path.get(path.size() - 1);
        if (!(cur instanceof Map<?, ?>)) return;
        Map<String, Object> m = (Map<String, Object>) cur;
        if (value == null) m.remove(String.valueOf(last));
        else m.put(String.valueOf(last), value);
        if (value != null) return;
        for (int d = path.size() - 1; d >= 1; d--) {
            List<Object> at = path.subList(0, d);
            if (at.size() == 1 || (at.size() == 2 && at.get(0).equals("bindings") && at.get(1).equals(game))) return;
            if (at.get(0).equals("parts") && at.size() <= 2) return;
            Object o = get(root, at);
            if (o instanceof Map<?, ?> om && om.isEmpty()) put(root, new ArrayList<>(at), null, game);
            else return;
        }
    }
}
