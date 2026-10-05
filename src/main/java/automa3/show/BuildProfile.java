package automa3.show;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * grandMA3 command templates for building a show, per software version. ALL build command syntax lives here
 * (the playback commands are in {@link automa3.ma3.Ma3Profile}).
 *
 * <p>Each template has a status (see docs/showcreator/MA3_NOTES.md): "manual" (from the manual of that version),
 * "assumed" (by analogy, not yet confirmed), "console" (confirmed by a ShowBuilder log on onPC), "systest" (used in
 * MA's own system tests shipped with onPC). Profiles of other versions inherit 2.3 until someone checks them.
 * A console's command overrides (Consoles tab) replace templates by key.</p>
 */
public final class BuildProfile {

    record Template(String text, String status, String source) {
    }

    static final Map<String, Template> BASE_2_3 = new LinkedHashMap<>();

    private static void t(String key, String text, String status, String source) {
        BASE_2_3.put(key, new Template(text, status, source));
    }

    static {
        t("clear_all", "ClearAll", "console", "onPC 2.3.2 probe; lua_objectfree_cmd (example)");
        t("delete_range", "Delete {obj} {start} Thru {end} /NoConfirmation", "console",
                "onPC 2.3.2 build: OK on a partly filled range, no pop-up; 'Illegal object' when nothing exists");
        t("select_fixtures", "Fixture {fids}", "console", "onPC 2.3.2 probe: 'Fixture 101 + 102 + 103 + 104'");
        t("fid_join", " + ", "console", "onPC 2.3.2 probe");
        t("store_group", "Store Group {no} /Overwrite /NoConfirmation", "console",
                "onPC 2.3.2 probe: keeps selection order as grid X; keyword_store");
        t("label", "Label {obj} {no} \"{name}\"", "console", "onPC 2.3.2 probe; keyword_label");
        t("select_group", "Group {no}", "console", "onPC 2.3.2 probe; keyword_group: default function SelFix");
        t("at_preset", "At Preset {pool}.{index}", "console", "onPC 2.3.2 probe; keyword_at: At [Object] [Number]");
        t("at_attribute", "Attribute \"{attr}\" At {value}", "manual", "keyword_at example 'Attribute \"Pan\" At 20'");
        t("at_phase", "At Phase {lo} Thru {hi}", "console",
                "onPC 2.3.2 probe: both ends inclusive (0 Thru 360 on 4 = 0/120/240/360); keyword_phase");
        t("matricks_set", "Set Selection MAtricks \"{prop}\" {value}", "console", "onPC 2.3.2 probe; keyword_matricks");
        t("matricks_reset", "Reset Selection MAtricks", "console", "onPC 2.3.2 probe; keyword_matricks 'Reset Selection 1 MAtricks'");
        t("matricks_phase_from", "PhaseFromX", "console",
                "onPC 2.3.2 probe: works in a stored MAtricks (recipe); as selection MAtricks before At Preset it has no effect");
        t("matricks_phase_to", "PhaseToX", "console", "onPC 2.3.2 probe: 0..360 on 4 fixtures = 0/90/180/270");
        t("matricks_wings", "XWings", "manual", "keyword_matricks property list");
        t("matricks_blocks", "XBlock", "manual", "keyword_matricks property list");
        t("matricks_groups", "XGroup", "manual", "keyword_matricks property list");
        t("store_matricks", "Store MAtricks {no} /Overwrite /NoConfirmation", "console", "onPC 2.3.2 probe; keyword_store");
        t("store_cue", "Store Sequence {seq} Cue {cue} /Overwrite /NoConfirmation", "console",
                "onPC 2.3.2 probe: creates the sequence, also stores an empty cue");
        t("label_cue", "Label Sequence {seq} Cue {cue} \"{name}\"", "console", "onPC 2.3.2 build: renames cooked cues");
        t("cue_fade", "Set Sequence {seq} Cue {cue} Part 0 Property \"CueFade\" {fade}", "systest",
                "MA system test system_test_sequence_set.lua: 'Set <seq> Cue 1 Part 0 property \"CueFade\" 2'");
        t("seq_prop", "Set Sequence {seq} Property \"{prop}\" \"{value}\"", "console",
                "onPC 2.3.2 build (read back): SpeedScale Div4/Div2/Mul2, Priority High, OffWhenOverridden No; "
                        + "MA system test system_test_sequence_set.lua");
        t("recipe_assign", "Assign {obj} {no} At Sequence {seq} Cue {cue} Part 0.{line}", "console",
                "onPC 2.3.2 probe: Group/Preset/MAtricks fill recipe line 1");
        t("cook_sequence", "Cook Sequence {seq} /Merge", "console", "onPC 2.3.2 probe; keyword_cook");
        t("speed_master", "Set Sequence {seq} Property \"SpeedMaster\" \"Speed{master}\"", "console",
                "onPC 2.3.2 probe: \"Speed1\" sticks, \"3.1\" and \"Master 3.1\" leave None; property from sequence_settings.uixml");
        t("assign_exec", "Assign Sequence {seq} At Page {page}.{exec}", "console",
                "onPC 2.3.2 build; executor_assign 'Assign Sequence 4 At Page 2.301'");
    }

    static final List<String> KNOWN = List.of("2.0", "2.1", "2.2", "2.3", "2.4", "2.5");

    /** e.g. "2.3" */
    public final String name;
    /** the console's version, e.g. "2.3.2" */
    public final String requested;
    final Map<String, Template> templates;
    public final List<String> warnings;

    private BuildProfile(String name, String requested, Map<String, Template> templates, List<String> warnings) {
        this.name = name;
        this.requested = requested;
        this.templates = templates;
        this.warnings = warnings;
    }

    /** Fill a template; values are inserted as text. */
    String t(String key, Object... kv) {
        String s = templates.get(key).text();
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return s;
    }

    String status(String key) {
        return templates.get(key).status();
    }

    static int[] majorMinor(String version) throws ShowException {
        String[] parts = version.trim().split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0};
        } catch (NumberFormatException e) {
            throw new ShowException("cannot read grandMA3 version '" + version + "'");
        }
    }

    private static int cmp(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    /**
     * The profile for a console version. Overrides whose key is a build template replace it; other keys (the
     * playback templates in the same per-console map) are left to {@link automa3.ma3.Ma3Profile}.
     */
    public static BuildProfile resolve(String version, Map<String, String> overrides) throws ShowException {
        int[] mm = majorMinor(version);
        List<int[]> known = new ArrayList<>();
        for (String k : KNOWN) known.add(majorMinor(k));
        List<String> warnings = new ArrayList<>();
        if (cmp(mm, known.get(0)) < 0) {
            warnings.add("grandMA3 " + version + " is older than any known profile; using " + KNOWN.get(0) + " (untested)");
            mm = known.get(0);
        }
        int[] chosen = known.get(0);
        for (int[] k : known) if (cmp(k, mm) <= 0) chosen = k;
        String name = chosen[0] + "." + chosen[1];
        if (cmp(chosen, mm) != 0) warnings.add("no profile for " + version + "; using " + name + " (untested on this version)");
        Map<String, Template> templates = new LinkedHashMap<>();
        for (Map.Entry<String, Template> e : BASE_2_3.entrySet()) {
            Template v = e.getValue();
            templates.put(e.getKey(), name.equals("2.3") ? v
                    : new Template(v.text(), "assumed", v.source() + " [inherited from 2.3, not checked for " + name + "]"));
        }
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                if (templates.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isBlank()) {
                    templates.put(e.getKey(), new Template(e.getValue(), "override", "console command overrides"));
                }
            }
        }
        return new BuildProfile(name, version, templates, warnings);
    }

    /** Template key -> default text, for the Consoles tab (overrides). */
    public static Map<String, String> defaultTemplates() {
        Map<String, String> out = new LinkedHashMap<>();
        BASE_2_3.forEach((k, v) -> out.put(k, v.text()));
        return out;
    }
}
