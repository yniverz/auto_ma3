package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * The rig (rig.json): fixtures from the console patch (primary) and/or an MVR export, with the spatial data the
 * planner uses (sets, orderings, mirror pairs), so the planner never sorts coordinates.
 *
 * <p>Canonical frame in rig.json: x = audience left -> right as seen from FOH, y = distance from FOH (grows towards
 * upstage), z = up, mm. {@code audience} says where FOH is in the source coordinates (default "-y": FOH at
 * negative y, confirmed for onPC 2.3.2).</p>
 */
public final class Rig {

    static final Set<String> AUDIENCE = Set.of("-y", "+y", "-x", "+x");
    static final double MISMATCH_MM = 50.0;
    /** Positions closer than this count as equal when sorting (patch noise is sub-mm). */
    static final double GRID_MM = 50.0;
    /** grandMA3's container "fixture" holding the patch. */
    static final Set<String> IGNORED_TYPES = Set.of("Grouping");

    private Rig() {
    }

    // ------------------------------------------------------------------ console patch (ShowBuilder patch job log)

    /** A fixture of the console patch. pos in mm, null when the console has none. */
    record PatchFixture(int fid, String name, String fixturetype, String mode, String layer, String cls,
                        double[] pos, double[] rot) {
    }

    /**
     * types: fixture type -> mode -> attributes of its logical channels; functions: the same for the attributes of
     * their channel functions (sub-attributes such as "Shutter1Strobe"; read by plugin 0.2.2 and later).
     */
    record Patch(List<PatchFixture> fixtures, Map<String, Map<String, List<String>>> types,
                 Map<String, Map<String, List<String>>> functions, String jobId, String ma3) {
    }

    /** Channel functions that strobe the light at a set frequency (Hz), in the order the build prefers them. */
    static final List<String> STROBE_FUNCTIONS = List.of("Shutter1Strobe", "StrobeFrequency", "StrobeRate");

    private static Double toDouble(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.doubleValue();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {mode name: [attribute names]} from an exported fixture type XML; empty for encrypted exports. */
    static Map<String, List<String>> xmlModeAttributes(Path path) {
        return xmlMode(path, false);
    }

    /** {mode name: [channel function attribute names]} from an exported fixture type XML. */
    static Map<String, List<String>> xmlModeFunctions(Path path) {
        return xmlMode(path, true);
    }

    private static Map<String, List<String>> xmlMode(Path path, boolean functions) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Document doc;
        try {
            doc = parseXml(Files.readAllBytes(path));
        } catch (Exception e) {
            return out;
        }
        NodeList modes = doc.getElementsByTagName("DMXMode");
        for (int i = 0; i < modes.getLength(); i++) {
            Element mode = (Element) modes.item(i);
            List<String> attrs = new ArrayList<>();
            NodeList lcs = mode.getElementsByTagName(functions ? "ChannelFunction" : "LogicalChannel");
            for (int j = 0; j < lcs.getLength(); j++) {
                String a = ((Element) lcs.item(j)).getAttribute("Attribute");
                if (!a.isEmpty() && !a.equals("NoFeature") && !attrs.contains(a)) attrs.add(a);
            }
            out.put(mode.getAttribute("Name"), attrs);
        }
        return out;
    }

    /** Reads the patch job's log: fixtures and fixture types straight from the show file. */
    static Patch readPatchLog(Path log) throws ShowException {
        List<JsonNode> records = new ArrayList<>();
        try {
            for (String line : Files.readString(log, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) records.add(ShowFiles.JSON.readTree(line));
            }
        } catch (IOException e) {
            throw new ShowException("cannot read " + log + ": " + e.getMessage());
        }
        JsonNode start = records.stream().filter(r -> "start".equals(r.path("ev").asText())).findFirst().orElse(ShowFiles.object());
        JsonNode step = records.stream().filter(r -> "step".equals(r.path("ev").asText()) && "patch".equals(r.path("op").asText()))
                .findFirst().orElse(null);
        if (step == null) throw new ShowException(log + " contains no patch result (did the patch job finish?)");

        Map<String, Map<String, List<String>>> types = new LinkedHashMap<>(), functions = new LinkedHashMap<>();
        for (JsonNode t : step.path("types")) {
            Map<String, List<String>> modes = new LinkedHashMap<>(), funcs = new LinkedHashMap<>();
            for (JsonNode m : t.path("modes")) {
                modes.put(m.path("name").asText(""), ShowFiles.texts(m.get("attributes")));
                funcs.put(m.path("name").asText(""), ShowFiles.texts(m.get("functions")));
            }
            if (t.hasNonNull("file") && !t.get("file").asText().isEmpty()) {
                Path xml = log.getParent().resolve(t.get("file").asText());
                for (Map.Entry<String, List<String>> e : xmlModeAttributes(xml).entrySet()) {
                    if (modes.getOrDefault(e.getKey(), List.of()).isEmpty()) modes.put(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, List<String>> e : xmlModeFunctions(xml).entrySet()) {
                    if (funcs.getOrDefault(e.getKey(), List.of()).isEmpty()) funcs.put(e.getKey(), e.getValue());
                }
            }
            if (t.hasNonNull("name") && !t.get("name").asText().isEmpty()) {
                Map<String, List<String>> existing = types.computeIfAbsent(t.get("name").asText(), k -> new LinkedHashMap<>());
                for (Map.Entry<String, List<String>> e : modes.entrySet()) {
                    if (!e.getValue().isEmpty() || !existing.containsKey(e.getKey())) existing.put(e.getKey(), e.getValue());
                }
                Map<String, List<String>> existingF = functions.computeIfAbsent(t.get("name").asText(), k -> new LinkedHashMap<>());
                for (Map.Entry<String, List<String>> e : funcs.entrySet()) {
                    if (!e.getValue().isEmpty() || !existingF.containsKey(e.getKey())) existingF.put(e.getKey(), e.getValue());
                }
            }
        }

        List<PatchFixture> fixtures = new ArrayList<>();
        for (JsonNode f : step.path("fixtures")) {
            String fid = f.hasNonNull("FID") ? f.get("FID").asText() : "";
            String type = f.path("fixturetype").asText("");
            String mode = f.path("mode").asText("");
            if (IGNORED_TYPES.contains(type) || mode.isEmpty() || fid.isEmpty() || !fid.chars().allMatch(Character::isDigit)) continue;
            Double[] pos = {toDouble(f.get("PosX")), toDouble(f.get("PosY")), toDouble(f.get("PosZ"))};
            Double[] rot = {toDouble(f.get("RotX")), toDouble(f.get("RotY")), toDouble(f.get("RotZ"))};
            boolean hasPos = pos[0] != null && pos[1] != null && pos[2] != null;
            boolean hasRot = rot[0] != null && rot[1] != null && rot[2] != null;
            String name = f.hasNonNull("Name") && !f.get("Name").asText().isEmpty() ? f.get("Name").asText()
                    : f.path("name").asText("");
            fixtures.add(new PatchFixture(Integer.parseInt(fid), name, type, mode, f.path("Layer").asText(""),
                    f.path("Class").asText(""),
                    hasPos ? new double[]{Py.round(pos[0] * 1000, 1), Py.round(pos[1] * 1000, 1), Py.round(pos[2] * 1000, 1)} : null, // m -> mm
                    hasRot ? new double[]{rot[0], rot[1], rot[2]} : null));
        }
        return new Patch(fixtures, types, functions, start.path("job_id").isMissingNode() ? null : start.get("job_id").asText(null),
                start.path("ma3").isMissingNode() ? null : start.get("ma3").asText(null));
    }

    // ------------------------------------------------------------------ MVR (My Virtual Rig, DIN SPEC 15800)

    record MvrFixture(int fid, String name, String gdtf, String mode, String layer, String cls, List<String> groups,
                      double[] pos, double[][] matrix) {
    }

    /** Row-vector matrix: three local axes and the offset (mm). */
    static final double[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, 0}};
    private static final Set<String> CONTAINERS = Set.of("GroupObject", "SceneObject", "Truss", "Support", "VideoScreen", "Projector", "Fixture");
    private static final Pattern ROW = Pattern.compile("\\{([^}]*)\\}");

    static double[][] parseMatrix(String text) throws ShowException {
        if (text == null || text.isEmpty()) return IDENTITY;
        Matcher m = ROW.matcher(text);
        List<double[]> rows = new ArrayList<>();
        while (m.find()) {
            String[] parts = m.group(1).split(",");
            double[] r = new double[parts.length];
            for (int i = 0; i < parts.length; i++) r[i] = Double.parseDouble(parts[i].trim());
            rows.add(r);
        }
        if (rows.size() != 4) throw new ShowException("bad MVR matrix '" + text + "'");
        return rows.toArray(new double[0][]);
    }

    /** World matrix of a child given its parent's world matrix (p' = p·R + o). */
    static double[][] compose(double[][] parent, double[][] child) {
        double[][] out = new double[4][3];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += child[i][k] * parent[k][j];
                out[i][j] = s;
            }
        }
        for (int j = 0; j < 3; j++) out[3][j] += parent[3][j];
        return out;
    }

    private static String childText(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getTagName().equals(tag)) return c.getTextContent();
        }
        return null;
    }

    private static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getTagName().equals(tag)) return c;
        }
        return null;
    }

    static List<MvrFixture> readMvr(Path path) throws ShowException {
        Document doc;
        try (ZipFile z = new ZipFile(path.toFile())) {
            var entry = z.getEntry("GeneralSceneDescription.xml");
            if (entry == null) throw new ShowException("not an MVR file (no GeneralSceneDescription.xml): " + path);
            doc = parseXml(z.getInputStream(entry).readAllBytes());
        } catch (ShowException e) {
            throw e;
        } catch (Exception e) {
            throw new ShowException("cannot read the MVR file " + path + ": " + e.getMessage());
        }
        Map<String, String> classes = new HashMap<>();
        NodeList cl = doc.getElementsByTagName("Class");
        for (int i = 0; i < cl.getLength(); i++) {
            Element c = (Element) cl.item(i);
            classes.put(c.getAttribute("uuid"), c.getAttribute("name"));
        }
        List<MvrFixture> out = new ArrayList<>();
        NodeList layers = doc.getElementsByTagName("Layer");
        for (int i = 0; i < layers.getLength(); i++) {
            Element layer = (Element) layers.item(i);
            walk(layer, parseMatrix(childText(layer, "Matrix")), layer.getAttribute("name"), List.of(), classes, out);
        }
        return out;
    }

    private static void walk(Element elem, double[][] world, String layer, List<String> groups, Map<String, String> classes,
                             List<MvrFixture> out) throws ShowException {
        for (Node n = elem.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element child)) continue;
            String tag = child.getTagName();
            if (tag.equals("ChildList")) {
                walk(child, world, layer, groups, classes, out);
                continue;
            }
            if (!CONTAINERS.contains(tag)) continue;
            double[][] m = compose(world, parseMatrix(childText(child, "Matrix")));
            if (tag.equals("Fixture")) {
                String fid = childText(child, "FixtureID");
                if (fid != null && !fid.strip().isEmpty() && fid.strip().chars().allMatch(Character::isDigit) && Integer.parseInt(fid.strip()) > 0) {
                    String classing = childText(child, "Classing");
                    out.add(new MvrFixture(Integer.parseInt(fid.strip()), child.getAttribute("name"),
                            orEmpty(childText(child, "GDTFSpec")), orEmpty(childText(child, "GDTFMode")), layer,
                            classes.getOrDefault(classing == null ? "" : classing.strip(), ""), new ArrayList<>(groups),
                            new double[]{Py.round(m[3][0], 1), Py.round(m[3][1], 1), Py.round(m[3][2], 1)},
                            new double[][]{m[0].clone(), m[1].clone(), m[2].clone()}));
                }
            }
            Element sub = child(child, "ChildList");
            if (sub != null) {
                List<String> g = groups;
                if (tag.equals("GroupObject")) {
                    g = new ArrayList<>(groups);
                    g.add(child.getAttribute("name"));
                }
                walk(sub, m, layer, g, classes, out);
            }
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }

    // ------------------------------------------------------------------ rig.json

    static double[] toCanonical(double[] p, String audience) {
        double x = p[0], y = p[1], z = p[2];
        double[] c = switch (audience) {
            case "+y" -> new double[]{-x, -y};
            case "-x" -> new double[]{-y, x};
            case "+x" -> new double[]{y, -x};
            default -> new double[]{x, y};
        };
        return new double[]{Py.round(c[0], 1) + 0.0, Py.round(c[1], 1) + 0.0, Py.round(z, 1)};
    }

    static List<String> capsOf(List<String> attributes) {
        List<String> caps = new ArrayList<>();
        for (String a : attributes) {
            String[] m = EffectLibrary.attributeCap(a);
            if (m != null && !caps.contains(m[0])) caps.add(m[0]);
        }
        return caps;
    }

    /** Lexicographic comparison of long keys. */
    private static Comparator<Integer> byKeys(java.util.function.Function<Integer, long[]> key) {
        return (a, b) -> {
            long[] ka = key.apply(a), kb = key.apply(b);
            for (int i = 0; i < ka.length; i++) if (ka[i] != kb[i]) return Long.compare(ka[i], kb[i]);
            return 0;
        };
    }

    private static Map<String, List<Integer>> orderings(List<Integer> fids, Map<Integer, double[]> pos) {
        Map<Integer, long[]> q = new HashMap<>();
        for (int f : fids) {
            double[] p = pos.get(f);
            q.put(f, new long[]{Py.roundInt(p[0] / GRID_MM), Py.roundInt(p[1] / GRID_MM), Py.roundInt(p[2] / GRID_MM)});
        }
        double min = fids.stream().mapToDouble(f -> pos.get(f)[0]).min().orElse(0);
        double max = fids.stream().mapToDouble(f -> pos.get(f)[0]).max().orElse(0);
        double cx = (min + max) / 2;
        final int X = 0, Y = 1, Z = 2;
        // sort on one axis; ties break left->right / FOH->stage / low->high, then by fid
        java.util.function.BiFunction<int[], Boolean, List<Integer>> by = (axes, descending) -> {
            List<Integer> l = new ArrayList<>(fids);
            l.sort(byKeys(f -> new long[]{(descending ? -1 : 1) * q.get(f)[axes[0]], q.get(f)[axes[1]], q.get(f)[axes[2]], f}));
            return l;
        };
        java.util.function.Function<Integer, Long> centre = f -> Py.roundInt(Math.abs(pos.get(f)[0] - cx) / GRID_MM);
        // equally central fixtures: FOH->stage, then left->right, so mirror partners come one after the other
        List<Integer> co = new ArrayList<>(fids);
        co.sort(byKeys(f -> new long[]{centre.apply(f), q.get(f)[Y], q.get(f)[Z], q.get(f)[X], f}));
        List<Integer> oi = new ArrayList<>(fids);
        oi.sort(byKeys(f -> new long[]{-centre.apply(f), q.get(f)[Y], q.get(f)[Z], q.get(f)[X], f}));
        Map<String, List<Integer>> o = new LinkedHashMap<>();
        o.put("left_to_right", by.apply(new int[]{X, Y, Z}, false));
        o.put("right_to_left", by.apply(new int[]{X, Y, Z}, true));
        o.put("foh_to_stage", by.apply(new int[]{Y, X, Z}, false));
        o.put("stage_to_foh", by.apply(new int[]{Y, X, Z}, true));
        o.put("bottom_to_top", by.apply(new int[]{Z, X, Y}, false));
        o.put("top_to_bottom", by.apply(new int[]{Z, X, Y}, true));
        o.put("center_out", co);
        o.put("outside_in", oi);
        return o;
    }

    /** Mirror pairs across the set's centre line (x), outermost first. Unmatched fixtures are left out. */
    private static List<int[]> pairs(List<Integer> fids, Map<Integer, double[]> pos) {
        double tol = 300.0;
        double min = fids.stream().mapToDouble(f -> pos.get(f)[0]).min().orElse(0);
        double max = fids.stream().mapToDouble(f -> pos.get(f)[0]).max().orElse(0);
        double cx = (min + max) / 2;
        List<Integer> left = new ArrayList<>(fids.stream().filter(f -> pos.get(f)[0] < cx - 1).toList());
        left.sort(Comparator.comparingDouble(f -> pos.get(f)[0])); // stable, like Python's sorted
        List<Integer> right = new ArrayList<>(fids.stream().filter(f -> pos.get(f)[0] > cx + 1).toList());
        List<int[]> out = new ArrayList<>();
        for (int f : left) {
            double mx = 2 * cx - pos.get(f)[0], y = pos.get(f)[1], z = pos.get(f)[2];
            Integer best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (int g : right) { // min(): the first of equal distances wins
                double d = Math.abs(pos.get(g)[0] - mx) + Math.abs(pos.get(g)[1] - y) + Math.abs(pos.get(g)[2] - z);
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            if (best != null && bestD <= tol) {
                out.add(new int[]{f, best});
                right.remove(best);
            }
        }
        return out;
    }

    public record Built(ObjectNode rig, List<String> warnings) {
    }

    private static ArrayNode doubles(double[] v) {
        ArrayNode a = ShowFiles.array();
        for (double d : v) a.add(d);
        return a;
    }

    private static ArrayNode intArray(List<Integer> v) {
        ArrayNode a = ShowFiles.array();
        v.forEach(a::add);
        return a;
    }

    /** rig.json from the console patch and/or an MVR export. */
    static Built build(Patch patch, List<MvrFixture> mvr, String audience, ObjectNode source) throws ShowException {
        if (!AUDIENCE.contains(audience)) throw new ShowException("audience must be one of [+x, +y, -x, -y]");
        if (patch == null && (mvr == null || mvr.isEmpty())) {
            throw new ShowException("need the console patch (patch job) and/or an MVR export");
        }
        List<String> warnings = new ArrayList<>();
        Map<Integer, MvrFixture> byMvr = new LinkedHashMap<>();
        if (mvr != null) for (MvrFixture m : mvr) byMvr.put(m.fid(), m);

        record Fx(int fid, String name, String type, String mode, String layer, String cls, double[] pos, double[] rot,
                  List<String> caps, List<String> attributes, List<String> functions) {
        }
        List<Fx> fixtures = new ArrayList<>();
        if (patch != null) {
            for (PatchFixture f : patch.fixtures()) {
                MvrFixture m = byMvr.get(f.fid());
                double[] raw = f.pos() != null ? f.pos() : (m != null ? m.pos() : null);
                if (raw == null) warnings.add("fixture " + f.fid() + ": no position (console or MVR); left out of spatial orderings");
                if (f.pos() != null && m != null) {
                    double d = 0;
                    for (int i = 0; i < 3; i++) d = Math.max(d, Math.abs(f.pos()[i] - m.pos()[i]));
                    if (d > MISMATCH_MM) warnings.add("fixture " + f.fid() + ": console and MVR positions differ by " + Py.f0(d) + " mm (console used)");
                }
                List<String> attrs = patch.types().getOrDefault(f.fixturetype(), Map.of()).getOrDefault(f.mode(), List.of());
                if (attrs.isEmpty()) warnings.add("fixture " + f.fid() + " (" + f.fixturetype() + " / " + f.mode() + "): no attributes found");
                List<String> funcs = patch.functions().getOrDefault(f.fixturetype(), Map.of()).getOrDefault(f.mode(), List.of());
                List<String> caps = new ArrayList<>(capsOf(attrs));
                // a strobe function in the fixture's shutter or strobe channel: a real strobe at a set rate
                if (funcs.stream().anyMatch(STROBE_FUNCTIONS::contains)) caps.add("strobe");
                fixtures.add(new Fx(f.fid(), f.name(), f.fixturetype(), f.mode(),
                        !f.layer().isEmpty() ? f.layer() : (m != null ? m.layer() : ""),
                        !f.cls().isEmpty() ? f.cls() : (m != null ? m.cls() : ""),
                        raw != null ? toCanonical(raw, audience) : null,
                        f.rot() != null ? f.rot() : new double[]{0.0, 0.0, 0.0}, caps, attrs, funcs));
            }
            Set<Integer> patched = new HashSet<>();
            patch.fixtures().forEach(f -> patched.add(f.fid()));
            List<Integer> missing = new ArrayList<>(new TreeSet<>(byMvr.keySet().stream().filter(k -> !patched.contains(k)).toList()));
            if (!missing.isEmpty()) warnings.add("fixtures in the MVR but not in the console patch: " + Py.repr(missing));
        } else {
            warnings.add("no console patch: capabilities unknown (MA3 exports dummy GDTFs); run the patch job");
            for (MvrFixture m : mvr) {
                fixtures.add(new Fx(m.fid(), m.name(), m.gdtf(), m.mode(), m.layer(), m.cls(), toCanonical(m.pos(), audience),
                        new double[]{0.0, 0.0, 0.0}, List.of(), List.of(), List.of()));
            }
        }
        fixtures.sort(Comparator.comparingInt(Fx::fid));
        List<Fx> placed = fixtures.stream().filter(f -> f.pos() != null).toList();
        Map<Integer, double[]> pos = new HashMap<>();
        placed.forEach(f -> pos.put(f.fid(), f.pos()));

        Map<String, List<Integer>> sets = new LinkedHashMap<>();
        sets.put("all", new ArrayList<>(placed.stream().map(Fx::fid).toList()));
        for (String key : List.of("type", "layer", "class")) {
            for (Fx f : placed) {
                String v = switch (key) {
                    case "type" -> f.type();
                    case "layer" -> f.layer();
                    default -> f.cls();
                };
                if (!v.isEmpty()) sets.computeIfAbsent(key + ":" + v, k -> new ArrayList<>()).add(f.fid());
            }
        }
        for (Fx f : placed) for (String c : f.caps()) sets.computeIfAbsent("cap:" + c, k -> new ArrayList<>()).add(f.fid());
        // layer/class sets identical to "all" (e.g. the default "FixtureLayer 1") carry no information
        List<Integer> allSorted = new ArrayList<>(sets.get("all"));
        allSorted.sort(null);
        sets.entrySet().removeIf(e -> (e.getKey().startsWith("layer:") || e.getKey().startsWith("class:"))
                && e.getValue().stream().sorted().toList().equals(allSorted));

        Map<String, Map<String, List<Integer>>> orderings = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : sets.entrySet()) {
            if (!e.getValue().isEmpty()) orderings.put(e.getKey(), orderings(e.getValue(), pos));
        }
        for (String name : new ArrayList<>(sets.keySet())) {
            if (orderings.containsKey(name)) sets.put(name, orderings.get(name).get("left_to_right")); // empty sets: no fixture has a position
        }
        Map<String, List<int[]>> pairs = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : sets.entrySet()) {
            List<int[]> p = pairs(e.getValue(), pos);
            if (!p.isEmpty()) pairs.put(e.getKey(), p);
        }
        for (Map.Entry<String, List<int[]>> e : pairs.entrySet()) {
            List<int[]> p = e.getValue();
            // for `wings: 2`: left fixtures outermost first, then their partners in reverse, so MA3's wing split
            // (first half mirrored onto the second) pairs each fixture with its mirror partner
            if (2 * p.size() == sets.get(e.getKey()).size()) {
                List<Integer> mirror = new ArrayList<>();
                p.forEach(x -> mirror.add(x[0]));
                for (int i = p.size() - 1; i >= 0; i--) mirror.add(p.get(i)[1]);
                orderings.get(e.getKey()).put("mirror", mirror);
            }
        }

        ObjectNode rig = ShowFiles.object();
        rig.put("format", "ma3sc-rig").put("version", 1);
        rig.set("source", source == null ? ShowFiles.object() : source);
        rig.putObject("axes").put("x", "audience left -> right (seen from FOH)").put("y", "distance from FOH (towards upstage)")
                .put("z", "up").put("unit", "mm").put("audience", audience);
        ArrayNode fx = rig.putArray("fixtures");
        for (Fx f : fixtures) {
            ObjectNode o = fx.addObject();
            o.put("fid", f.fid()).put("name", f.name()).put("type", f.type()).put("mode", f.mode()).put("layer", f.layer())
                    .put("class", f.cls());
            if (f.pos() != null) o.set("pos", doubles(f.pos()));
            o.set("rot", doubles(f.rot()));
            ArrayNode caps = o.putArray("caps");
            f.caps().forEach(caps::add);
            ArrayNode attrs = o.putArray("attributes");
            f.attributes().forEach(attrs::add);
            if (!f.functions().isEmpty()) {
                ArrayNode fn = o.putArray("functions");
                f.functions().forEach(fn::add);
            }
        }
        ObjectNode s = rig.putObject("sets");
        sets.forEach((k, v) -> s.set(k, intArray(v)));
        ObjectNode o = rig.putObject("orderings");
        orderings.forEach((k, v) -> {
            ObjectNode on = o.putObject(k);
            v.forEach((name, list) -> on.set(name, intArray(list)));
        });
        ObjectNode pr = rig.putObject("pairs");
        pairs.forEach((k, v) -> {
            ArrayNode a = pr.putArray(k);
            v.forEach(x -> a.addArray().add(x[0]).add(x[1]));
        });
        return new Built(rig, warnings);
    }

    static String fileDigest(Path path) throws ShowException {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(h).substring(0, 16);
        } catch (Exception e) {
            throw new ShowException("cannot read " + path + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ use of rig.json

    static Map<Integer, JsonNode> fixturesById(JsonNode rig) {
        Map<Integer, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode f : rig.path("fixtures")) out.put(f.get("fid").asInt(), f);
        return out;
    }

    /** Fixture ids of a plan group spec, in order. Throws with a readable message. */
    static List<Integer> resolveGroup(JsonNode rig, JsonNode spec) throws ShowException {
        if (spec.has("fids")) return ShowFiles.ints(spec.get("fids"));
        String s = spec.get("set").asText(), o = spec.get("order").asText();
        JsonNode orderings = rig.path("orderings");
        if (!orderings.has(s)) {
            throw new ShowException("rig has no set '" + s + "' (available: " + String.join(", ", new TreeSet<>(ShowFiles.keys(orderings))) + ")");
        }
        JsonNode orders = orderings.get(s);
        if (!orders.has(o)) {
            throw new ShowException("set '" + s + "' has no ordering '" + o + "' (available: " + String.join(", ", new TreeSet<>(ShowFiles.keys(orders))) + ")");
        }
        return ShowFiles.ints(orders.get(o));
    }
}
