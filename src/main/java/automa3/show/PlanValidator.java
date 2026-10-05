package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks plan.json against the schema, the rig, the library and the reserved ranges.
 *
 * <p>Errors are written for the planner: each has a JSON path into plan.json, a stable code, a message and (where
 * useful) a hint how to fix it.</p>
 */
public final class PlanValidator {

    public record Issue(String level, String path, String code, String message, String hint) {
        @Override
        public String toString() {
            String s = String.format("%-7s %s: %s [%s]", level.toUpperCase(), path, message, code);
            return s + (hint.isEmpty() ? "" : "\n        hint: " + hint);
        }
    }

    /** AutoMA3 role -> capability a fixture needs to take part (null: no requirement). */
    static final Map<String, String> ROLE_NEEDS = new LinkedHashMap<>();

    static {
        for (String r : List.of("BASE", "EFFECT", "RISER", "ACCENT", "STROBE", "BLINDER", "BLACKOUT")) ROLE_NEEDS.put(r, "dimmer");
        ROLE_NEEDS.put("COLOR", "color_mix");
        ROLE_NEEDS.put("MOVEMENT", "pan");
        ROLE_NEEDS.put("HAZE", "haze");
        ROLE_NEEDS.put("FOG", "fog");
        ROLE_NEEDS.put("SPECIAL", null);
    }

    private PlanValidator() {
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.level().equals("error"));
    }

    public static ObjectNode report(List<Issue> issues) {
        ObjectNode r = ShowFiles.object();
        long errors = issues.stream().filter(i -> i.level().equals("error")).count();
        r.put("ok", errors == 0).put("errors", errors).put("warnings", issues.size() - errors);
        ArrayNode a = r.putArray("issues");
        for (Issue i : issues) {
            a.addObject().put("level", i.level()).put("path", i.path()).put("code", i.code()).put("message", i.message()).put("hint", i.hint());
        }
        return r;
    }

    /** settings may be null (no range checks); looksIn null means "no look list to compare with". */
    static List<Issue> validate(JsonNode plan, JsonNode rig, JsonNode library, ShowSettings settings, List<JsonNode> looksIn) {
        List<Issue> issues = new ArrayList<>();
        for (SchemaCheck.Error e : SchemaCheck.validate(plan, SchemaCheck.load("plan"))) {
            issues.add(new Issue("error", e.path(), "schema", e.message(), ""));
        }
        if (!issues.isEmpty()) return issues; // semantic checks assume a structurally valid plan

        Map<Integer, JsonNode> fixtures = Rig.fixturesById(rig);
        JsonNode presets = library.get("presets");
        JsonNode statics = library.get("static_attributes");

        Map<String, Integer> groupIdx = unique(plan.get("groups"), "group", "groups", issues);
        Map<String, Integer> seqIdx = unique(plan.get("sequences"), "sequence", "sequences", issues);
        unique(plan.get("looks"), "look", "looks", issues);

        // groups -> fixture ids
        Map<String, List<Integer>> groupFids = new HashMap<>();
        for (int i = 0; i < plan.get("groups").size(); i++) {
            JsonNode g = plan.get("groups").get(i);
            String p = "$.groups[" + i + "].fixtures";
            List<Integer> fids;
            try {
                fids = Rig.resolveGroup(rig, g.get("fixtures"));
            } catch (ShowException e) {
                issues.add(new Issue("error", p, "unknown_set", e.getMessage(), "use a set and ordering listed in rig.json 'orderings'"));
                continue;
            }
            List<Integer> missing = fids.stream().filter(f -> !fixtures.containsKey(f)).toList();
            if (!missing.isEmpty()) issues.add(new Issue("error", p, "unknown_fixture", "fixture ids not in the rig: " + Py.repr(missing), ""));
            if (new HashSet<>(fids).size() != fids.size()) issues.add(new Issue("error", p, "duplicate_fixture", "a fixture appears twice in the group", ""));
            if (fids.isEmpty()) issues.add(new Issue("error", p, "empty_group", "group has no fixtures", ""));
            groupFids.put(g.get("key").asText(), fids.stream().filter(fixtures::containsKey).toList());
        }

        // sequences -> cues -> lines
        for (int si = 0; si < plan.get("sequences").size(); si++) {
            JsonNode s = plan.get("sequences").get(si);
            for (int ci = 0; ci < s.get("cues").size(); ci++) {
                JsonNode c = s.get("cues").get(ci);
                for (int li = 0; li < c.get("lines").size(); li++) {
                    JsonNode line = c.get("lines").get(li);
                    String p = "$.sequences[" + si + "].cues[" + ci + "].lines[" + li + "]";
                    String gk = line.get("group").asText();
                    if (!groupIdx.containsKey(gk)) {
                        issues.add(new Issue("error", p + ".group", "unknown_group", "no group with key '" + gk + "'",
                                "defined groups: " + (groupIdx.isEmpty() ? "none" : String.join(", ", groupIdx.keySet()))));
                        continue;
                    }
                    List<Integer> fids = groupFids.getOrDefault(gk, List.of());
                    boolean hasPreset = line.has("preset"), hasValues = line.has("values");
                    if (hasPreset == hasValues) {
                        issues.add(new Issue("error", p, "preset_or_values", "a line needs exactly one of 'preset' or 'values'", ""));
                        continue;
                    }
                    if (hasPreset) {
                        String pk = line.get("preset").asText();
                        if (!presets.has(pk)) {
                            issues.add(new Issue("error", p + ".preset", "unknown_preset", "no preset with key '" + pk + "' in library.json", ""));
                            continue;
                        }
                        JsonNode pr = presets.get(pk);
                        List<String> requires = ShowFiles.texts(pr.get("requires"));
                        Map<Integer, String> lacking = new LinkedHashMap<>();
                        for (int f : fids) {
                            List<String> caps = ShowFiles.texts(fixtures.get(f).get("caps"));
                            List<String> cs = requires.stream().filter(x -> !caps.contains(x)).toList();
                            if (!cs.isEmpty()) lacking.put(f, f + " lacks " + String.join("/", cs));
                        }
                        if (!lacking.isEmpty()) {
                            issues.add(new Issue("error", p + ".preset", "capability", "preset '" + pk + "' needs " + Py.repr(requires)
                                    + " but " + String.join(", ", lacking.values()), "use a group whose fixtures all have these capabilities"));
                        }
                        if (line.has("phase") && !"phaser".equals(pr.get("kind").asText())) {
                            issues.add(new Issue("error", p + ".phase", "phase_needs_phaser", "preset '" + pk + "' is not a phaser; phase spread needs one", ""));
                        }
                    } else {
                        for (String a : ShowFiles.keys(line.get("values"))) {
                            JsonNode v = line.get("values").get(a);
                            if (!statics.has(a)) {
                                issues.add(new Issue("error", p + ".values." + a, "unknown_attribute", "'" + a + "' is not a static attribute",
                                        "allowed: " + String.join(", ", ShowFiles.keys(statics))));
                                continue;
                            }
                            JsonNode st = statics.get(a);
                            if (v.decimalValue().compareTo(st.get("min").decimalValue()) < 0 || v.decimalValue().compareTo(st.get("max").decimalValue()) > 0) {
                                issues.add(new Issue("error", p + ".values." + a, "out_of_range", Py.str(v) + " outside " + Py.str(st.get("min")) + ".." + Py.str(st.get("max")), ""));
                            }
                            String cap = st.get("cap").asText();
                            List<Integer> noCap = fids.stream().filter(f -> !ShowFiles.texts(fixtures.get(f).get("caps")).contains(cap)).toList();
                            if (!noCap.isEmpty()) issues.add(new Issue("error", p + ".values." + a, "capability", "fixtures " + Py.repr(noCap) + " have no " + cap, ""));
                        }
                        if (line.has("phase")) {
                            issues.add(new Issue("error", p + ".phase", "phase_needs_phaser", "phase spread needs a phaser preset, not static values", ""));
                        }
                    }
                }
            }
        }

        // taste checks that came from watching the result on a rig
        Set<Integer> allFids = new HashSet<>(ShowFiles.ints(rig.path("sets").get("all")));
        int presetLines = 0, wholeRig = 0;
        for (int si = 0; si < plan.get("sequences").size(); si++) {
            JsonNode s = plan.get("sequences").get(si);
            for (int ci = 0; ci < s.get("cues").size(); ci++) {
                JsonNode c = s.get("cues").get(ci);
                Set<String> tilted = new HashSet<>();
                for (JsonNode l : c.get("lines")) if (l.path("values").has("tilt")) tilted.add(l.get("group").asText());
                for (int li = 0; li < c.get("lines").size(); li++) {
                    JsonNode line = c.get("lines").get(li);
                    JsonNode pr = presets.get(line.path("preset").asText(""));
                    if (pr == null) continue;
                    presetLines++;
                    String gk = line.get("group").asText();
                    if (!allFids.isEmpty() && new HashSet<>(groupFids.getOrDefault(gk, List.of())).equals(allFids)) wholeRig++;
                    if (ShowFiles.texts(pr.get("affects")).contains("position") && !tilted.contains(gk)) {
                        issues.add(new Issue("warning", "$.sequences[" + si + "].cues[" + ci + "].lines[" + li + "]", "movement_without_tilt",
                                "position phaser '" + line.get("preset").asText() + "' on '" + gk + "' without a static tilt in the same cue",
                                "moving heads rest at tilt midpoint (beam straight down), where pan is invisible: add a line "
                                        + "with values {\"tilt\": ...} for the same group before the phaser line"));
                    }
                }
            }
        }
        Set<String> types = new HashSet<>();
        for (JsonNode f : rig.path("fixtures")) types.add(f.get("type").asText());
        boolean severalTypes = types.size() > 1; // with one type, every group is the whole rig
        if (severalTypes && presetLines >= 4 && (double) wholeRig / presetLines > 0.4) {
            issues.add(new Issue("warning", "$.sequences", "whole_rig_overuse", wholeRig + " of " + presetLines + " effect lines use the whole rig",
                    "use fixture-type groups, foh_to_stage / mirror orderings and different effects per group"));
        }

        // speed: which sequences follow the BPM speed master (see ShowCompiler.onSpeedMaster)
        for (int si = 0; si < plan.get("sequences").size(); si++) {
            JsonNode s = plan.get("sequences").get(si);
            boolean strobe = false;
            for (JsonNode c : s.get("cues")) {
                for (JsonNode l : c.get("lines")) {
                    JsonNode pr = presets.get(l.path("preset").asText(""));
                    if (pr != null && ShowCompiler.isStrobe(pr)) strobe = true;
                }
            }
            boolean onBpm = ShowCompiler.onSpeedMaster(s, library);
            if (strobe && onBpm) {
                boolean mixed = ShowCompiler.beatPhasers(s, library) > 0;
                issues.add(new Issue("warning", "$.sequences[" + si + "]", "strobe_on_speed_master",
                        mixed ? "strobe and beat effects in one sequence: the BPM speed master makes the strobe flash with the beat"
                                : "a strobe on the BPM speed master flashes with the beat instead of strobing",
                        mixed ? "put the strobe in a sequence of its own: it then keeps its own speed (about 10 Hz)"
                                : "remove \"speed_master\": strobes keep their own speed"));
            }
            String scale = s.path("speed_scale").asText("One");
            if (!onBpm && !scale.equals("One")) {
                issues.add(new Issue("warning", "$.sequences[" + si + "].speed_scale", "speed_scale_unused",
                        "speed_scale has no effect: the sequence is not on the BPM speed master (no effect in it follows the beat)",
                        "remove speed_scale"));
            }
        }
        // a strobe look on fixtures that can strobe with their shutter should use it, not switch the dimmer
        Map<String, JsonNode> seqByKey = new HashMap<>();
        plan.get("sequences").forEach(q -> seqByKey.put(q.get("key").asText(), q));
        for (int i = 0; i < plan.get("looks").size(); i++) {
            JsonNode lk = plan.get("looks").get(i);
            JsonNode sq = seqByKey.get(lk.get("sequence").asText());
            if (!"STROBE".equals(lk.get("role").asText()) || sq == null) continue;
            Set<Integer> canStrobe = new java.util.TreeSet<>();
            for (JsonNode c : sq.get("cues")) {
                for (JsonNode l : c.get("lines")) {
                    JsonNode pr = presets.get(l.path("preset").asText(""));
                    if (pr == null || !ShowCompiler.isStrobe(pr)) continue;
                    for (int f : groupFids.getOrDefault(l.get("group").asText(), List.of())) {
                        if (ShowFiles.texts(fixtures.get(f).get("caps")).contains("strobe")) canStrobe.add(f);
                    }
                }
            }
            if (!canStrobe.isEmpty()) {
                issues.add(new Issue("warning", "$.looks[" + i + "]", "strobe_channel",
                        "fixtures " + Py.repr(new ArrayList<>(canStrobe)) + " have a strobe channel but this strobe only switches their dimmer",
                        "use values {\"dimmer\": 100, \"strobe\": 10} (Hz) on a group of the fixtures with cap strobe; keep the strobe preset for fixtures without it"));
            }
        }

        // looks
        Set<String> usedSeqs = new HashSet<>();
        Map<Integer, Integer> execs = new HashMap<>();
        JsonNode looks = plan.get("looks");
        for (int i = 0; i < looks.size(); i++) {
            JsonNode lk = looks.get(i);
            String p = "$.looks[" + i + "]";
            String sq = lk.get("sequence").asText();
            if (!seqIdx.containsKey(sq)) {
                issues.add(new Issue("error", p + ".sequence", "unknown_sequence", "no sequence with key '" + sq + "'", ""));
            } else if (usedSeqs.contains(sq)) {
                issues.add(new Issue("warning", p + ".sequence", "sequence_reused", "sequence '" + sq + "' is on more than one executor", ""));
            }
            usedSeqs.add(sq);
            JsonNode e = lk.get("energy");
            if (e != null && e.isObject() && !e.isEmpty() && e.get("min").decimalValue().compareTo(e.get("max").decimalValue()) > 0) {
                issues.add(new Issue("error", p + ".energy", "energy_range", "energy.min is greater than energy.max", ""));
            }
            if (lk.has("exec")) {
                int ex = lk.get("exec").asInt();
                if (settings != null && !settings.executors().contains(ex)) {
                    issues.add(new Issue("error", p + ".exec", "exec_out_of_range", "executor " + ex + " is outside the reserved range "
                            + settings.executors().start() + ".." + settings.executors().end(), "omit 'exec' to let the compiler allocate one"));
                }
                if (execs.containsKey(ex)) {
                    issues.add(new Issue("error", p + ".exec", "exec_taken", "executor " + ex + " already used by $.looks[" + execs.get(ex) + "]", ""));
                }
                execs.putIfAbsent(ex, i);
            }
        }
        for (Map.Entry<String, Integer> e : seqIdx.entrySet()) {
            if (!usedSeqs.contains(e.getKey())) {
                issues.add(new Issue("warning", "$.sequences[" + e.getValue() + "]", "unused_sequence", "sequence '" + e.getKey() + "' is not on any executor (no look uses it)", ""));
            }
        }

        // every look of the AutoMA3 list should be filled (matched by id, else by executor number)
        if (looksIn != null) {
            Set<String> capsPresent = new HashSet<>();
            for (JsonNode f : rig.path("fixtures")) capsPresent.addAll(ShowFiles.texts(f.get("caps")));
            Set<String> ids = new HashSet<>();
            Set<String> execsUsed = new HashSet<>();
            for (JsonNode lk : looks) {
                ids.add(lk.has("automa3_id") ? ShowFiles.canonical(lk.get("automa3_id")) : "null");
                execsUsed.add(lk.has("exec") ? ShowFiles.canonical(lk.get("exec")) : "null");
            }
            for (int i = 0; i < looksIn.size(); i++) {
                JsonNode lk = looksIn.get(i);
                JsonNode ma3 = lk.path("ma3");
                JsonNode ex = ma3.isObject() && ma3.has("exec") ? ma3.get("exec") : lk.get("exec");
                boolean hasId = truthy(lk.get("id"));
                if ((hasId && ids.contains(ShowFiles.canonical(lk.get("id")))) || (!hasId && execsUsed.contains(ex == null ? "null" : ShowFiles.canonical(ex)))) continue;
                String need = ROLE_NEEDS.get(lk.path("role").asText("").toUpperCase());
                if (need != null && !capsPresent.contains(need)) continue; // cannot be built with this rig (listed in the brief)
                issues.add(new Issue("warning", "$.looks", "look_not_filled", "look #" + i + " '" + lk.path("name").asText("") + "' ("
                        + Py.str(lk.get("role")) + ") of the look list is not in the plan",
                        "add a look with its automa3_id (or exec), or explain in notes why it is left out"));
            }
        }

        // capacity of the reserved ranges
        if (settings != null) {
            Object[][] caps = {{"groups", plan.get("groups").size(), settings.groups()},
                    {"sequences", plan.get("sequences").size(), settings.sequences()},
                    {"looks", looks.size(), settings.executors()}};
            for (Object[] c : caps) {
                int n = (Integer) c[1];
                ShowSettings.Range r = (ShowSettings.Range) c[2];
                if (n > r.size()) issues.add(new Issue("error", "$." + c[0], "range_full", n + " " + c[0] + " but the reserved range holds only " + r.size(), ""));
            }
        }
        return issues;
    }

    /** Python truthiness of a JSON value. */
    static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return false;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isNumber()) return v.doubleValue() != 0;
        if (v.isTextual()) return !v.textValue().isEmpty();
        return !v.isEmpty();
    }

    private static Map<String, Integer> unique(JsonNode items, String what, String base, List<Issue> issues) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String k = items.get(i).get("key").asText();
            if (seen.containsKey(k)) {
                issues.add(new Issue("error", "$." + base + "[" + i + "].key", "duplicate_key", what + " key '" + k + "' already used at $." + base + "[" + seen.get(k) + "]", ""));
            }
            seen.putIfAbsent(k, i);
        }
        return seen;
    }
}
