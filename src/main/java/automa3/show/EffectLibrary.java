package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The effect library (library.json): grandMA3's own predefined phasers (preset pool 21) plus hand-written
 * judgement (energy, tags) from library/annotations.json.
 *
 * <p>The "Import Predefined Phaser" macro runs {@code Import Library "Predefined_Phaser.xml" at Preset 21.1}, so
 * the n-th Preset in that file becomes preset 21.n (assuming pool 21 was empty; the inspect job checks).</p>
 */
public final class EffectLibrary {

    /** MA3 attribute name prefix -> (capability, affected feature). */
    private static final String[][] ATTRIBUTE_CAPS = {
            {"Dimmer", "dimmer", "dimmer"}, {"Pan", "pan", "position"}, {"Tilt", "tilt", "position"},
            {"ColorRGB_", "color_mix", "color"}, {"ColorAdd_", "color_mix", "color"}, {"ColorSub_", "color_mix", "color"},
            {"Color", "color_wheel", "color"}, {"Zoom", "zoom", "beam"}, {"Focus", "focus", "beam"},
            {"Gobo", "gobo", "beam"}, {"Prism", "prism", "beam"}, {"Shutter", "shutter", "beam"},
            {"Strobe", "shutter", "beam"}, {"Iris", "iris", "beam"}, {"Frost", "frost", "beam"},
            {"Haze", "haze", "atmo"}, {"Fog", "fog", "atmo"},
    };

    private EffectLibrary() {
    }

    /** Capability and feature of an attribute, or null. */
    static String[] attributeCap(String attr) {
        for (String[] c : ATTRIBUTE_CAPS) if (attr.startsWith(c[0])) return new String[]{c[1], c[2]};
        return null;
    }

    /** Attributes a plan may set to a fixed value (ACCENT, BLINDER, BLACKOUT, HAZE, FOG ...). */
    static ObjectNode staticAttributes() {
        ObjectNode s = ShowFiles.object();
        // degrees from the fixture's centre position (0 = pan/tilt midpoint) for pan and tilt
        Object[][] rows = {
                {"dimmer", "Dimmer", "dimmer", 0, 100}, {"pan", "Pan", "pan", -270, 270}, {"tilt", "Tilt", "tilt", -135, 135},
                {"color_r", "ColorRGB_R", "color_mix", 0, 100}, {"color_g", "ColorRGB_G", "color_mix", 0, 100},
                {"color_b", "ColorRGB_B", "color_mix", 0, 100}, {"color_w", "ColorRGB_W", "color_mix", 0, 100},
                {"haze", "Haze", "haze", 0, 100}, {"fog", "Fog", "fog", 0, 100},
        };
        for (Object[] r : rows) {
            s.putObject((String) r[0]).put("ma3", (String) r[1]).put("cap", (String) r[2])
                    .put("min", (Integer) r[3]).put("max", (Integer) r[4]);
        }
        // a real strobe in Hz: whichever strobe channel function each fixture has (set by the plugin's "strobe"
        // step; the command line cannot address channel functions, see docs/showcreator/MA3_NOTES.md)
        ObjectNode strobe = s.putObject("strobe").put("ma3", Rig.STROBE_FUNCTIONS.get(0));
        ArrayNode fn = strobe.putArray("functions");
        Rig.STROBE_FUNCTIONS.forEach(fn::add);
        strobe.put("cap", "strobe").put("min", 1).put("max", 25);
        return s;
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return !s.isEmpty() && Character.isLetter(s.charAt(0)) && s.charAt(0) < 128 ? s : "p_" + s;
    }

    record Preset(int index, String name, boolean phaser, List<String> attributes) {
    }

    static List<Preset> parsePredefined(Path xml) throws ShowException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = f.newDocumentBuilder().parse(xml.toFile());
        } catch (Exception e) {
            throw new ShowException("cannot read the predefined phaser file " + xml + ": " + e.getMessage());
        }
        List<Preset> out = new ArrayList<>();
        NodeList presets = doc.getElementsByTagName("Preset");
        for (int i = 0; i < presets.getLength(); i++) {
            Element p = (Element) presets.item(i);
            NodeList phasers = p.getElementsByTagName("Phaser");
            TreeSet<String> attrs = new TreeSet<>();
            for (int j = 0; j < phasers.getLength(); j++) {
                String a = ((Element) phasers.item(j)).getAttribute("Attribute");
                if (!a.isEmpty()) attrs.add(a);
            }
            String name = p.hasAttribute("Name") ? p.getAttribute("Name") : "Preset " + (i + 1);
            out.add(new Preset(i + 1, name, phasers.getLength() > 0, new ArrayList<>(attrs)));
        }
        return out;
    }

    /** Build library.json from the predefined phaser file and the annotations. */
    static ObjectNode build(Path xml, JsonNode annotations, String ma3Version) throws ShowException {
        List<Preset> presets = parsePredefined(xml);
        JsonNode byName = annotations == null ? ShowFiles.object() : annotations.path("presets");
        ObjectNode result = ShowFiles.object();
        for (Preset p : presets) {
            JsonNode a = byName.path(p.name());
            String key = a.hasNonNull("key") && !a.get("key").asText().isEmpty() ? a.get("key").asText() : slug(p.name());
            if (result.has(key)) key = key + "_" + p.index(); // duplicate names: disambiguate by index
            List<String> caps = new ArrayList<>(), affects = new ArrayList<>();
            for (String attr : p.attributes()) {
                String[] m = attributeCap(attr);
                if (m != null && !caps.contains(m[0])) caps.add(m[0]);
                if (m != null && !affects.contains(m[1])) affects.add(m[1]);
            }
            ObjectNode e = result.putObject(key);
            e.put("pool", 21).put("index", p.index()).put("name", p.name()).put("kind", p.phaser() ? "phaser" : "static");
            ArrayNode at = e.putArray("attributes");
            p.attributes().forEach(at::add);
            ArrayNode af = e.putArray("affects");
            affects.forEach(af::add);
            if (a.has("requires")) {
                e.set("requires", a.get("requires").deepCopy()); // e.g. Zoom - Blue White: zoom is optional
            } else {
                ArrayNode rq = e.putArray("requires");
                caps.forEach(rq::add);
            }
            e.set("energy", a.has("energy") ? a.get("energy").deepCopy() : null);
            e.set("tags", a.has("tags") ? a.get("tags").deepCopy() : ShowFiles.array());
            if (a.hasNonNull("notes") && !a.get("notes").asText().isEmpty()) e.set("notes", a.get("notes").deepCopy());
        }
        ObjectNode lib = ShowFiles.object();
        lib.put("format", "ma3sc-library").put("version", 1);
        lib.putObject("source").put("file", xml.getFileName().toString()).put("ma3_version", ma3Version)
                .put("count", presets.size());
        lib.set("presets", result);
        lib.set("static_attributes", staticAttributes());
        return lib;
    }

    /** The bundled library (built from grandMA3 2.3.2), or one built from the installed version's file. */
    static JsonNode bundled() {
        return ShowFiles.resource("library/library.json");
    }

    static JsonNode annotations() {
        return ShowFiles.resource("library/annotations.json");
    }

    /** grandMA3's predefined phaser file of an installed version, or null. */
    static Path predefinedPhaserXml(Path ma3Root, String version) {
        Path p = ma3Root.resolve("gma3_" + version).resolve("shared").resolve("resource").resolve("lib_presets")
                .resolve("predefined_phaser.xml");
        return Files.exists(p) ? p : null;
    }
}
