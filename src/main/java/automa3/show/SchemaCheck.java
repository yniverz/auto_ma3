package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minimal JSON Schema checker for the show creator's schemas (no dependency).
 *
 * <p>Supports the subset the schemas use: type, enum, const, required, properties, additionalProperties, items,
 * minItems, maxItems, minimum, maximum, minLength, pattern, uniqueItems, oneOf, $ref (local #/$defs/...).
 * Errors are (json path, message) pairs written for the planner.</p>
 */
final class SchemaCheck {

    record Error(String path, String message) {
    }

    private SchemaCheck() {
    }

    static JsonNode load(String name) {
        return ShowFiles.resource("schemas/" + name + ".schema.json");
    }

    static List<Error> validate(JsonNode value, JsonNode schema) {
        return validate(value, schema, schema, "$");
    }

    private static List<Error> validate(JsonNode value, JsonNode schema, JsonNode root, String path) {
        List<Error> errs = new ArrayList<>();
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            JsonNode target = root;
            for (String part : ref.substring(2).split("/")) target = target.get(part);
            return validate(value, target, root, path);
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode s : schema.get("oneOf")) if (validate(value, s, root, path).isEmpty()) matches++;
            if (matches != 1) {
                String hint = schema.has("description") ? schema.get("description").asText() : "it must match exactly one allowed form";
                errs.add(new Error(path, "invalid value: " + hint));
            }
            return errs;
        }
        JsonNode t = schema.get("type");
        if (t != null) {
            List<String> types = new ArrayList<>();
            if (t.isArray()) t.forEach(x -> types.add(x.asText()));
            else types.add(t.asText());
            if (types.stream().noneMatch(x -> isType(value, x))) {
                return List.of(new Error(path, "expected " + String.join(" or ", types) + ", got " + Py.typeName(value)));
            }
        }
        if (schema.has("const") && !equal(value, schema.get("const"))) {
            errs.add(new Error(path, "must be " + Py.repr(schema.get("const"))));
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode e : schema.get("enum")) if (equal(value, e)) found = true;
            if (!found) errs.add(new Error(path, Py.repr(value) + " is not one of " + Py.repr(schema.get("enum"))));
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && compare(value, schema.get("minimum")) < 0) {
                errs.add(new Error(path, "must be >= " + Py.str(schema.get("minimum"))));
            }
            if (schema.has("maximum") && compare(value, schema.get("maximum")) > 0) {
                errs.add(new Error(path, "must be <= " + Py.str(schema.get("maximum"))));
            }
        }
        if (value.isTextual()) {
            String s = value.textValue();
            if (schema.has("minLength") && s.codePointCount(0, s.length()) < schema.get("minLength").asInt()) {
                errs.add(new Error(path, "must have at least " + schema.get("minLength").asInt() + " characters"));
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(s).find()) {
                errs.add(new Error(path, schema.has("patternMessage") ? schema.get("patternMessage").asText()
                        : "must match " + schema.get("pattern").asText()));
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errs.add(new Error(path, "needs at least " + schema.get("minItems").asInt() + " item(s)"));
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                errs.add(new Error(path, "allows at most " + schema.get("maxItems").asInt() + " item(s)"));
            }
            if (schema.path("uniqueItems").asBoolean(false)) {
                Set<String> seen = new HashSet<>();
                boolean dup = false;
                for (JsonNode v : value) dup |= !seen.add(ShowFiles.canonical(v));
                if (dup) errs.add(new Error(path, "items must be unique"));
            }
            if (schema.has("items")) {
                for (int i = 0; i < value.size(); i++) errs.addAll(validate(value.get(i), schema.get("items"), root, path + "[" + i + "]"));
            }
        }
        if (value.isObject()) {
            for (JsonNode req : schema.path("required")) {
                if (!value.has(req.asText())) errs.add(new Error(path, "missing required field '" + req.asText() + "'"));
            }
            JsonNode props = schema.path("properties");
            JsonNode extra = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> it = value.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if (props.has(k)) {
                    errs.addAll(validate(e.getValue(), props.get(k), root, path + "." + k));
                } else if (extra != null && extra.isBoolean() && !extra.booleanValue()) {
                    List<String> names = new ArrayList<>();
                    props.fieldNames().forEachRemaining(names::add);
                    errs.add(new Error(path + "." + k, "unknown field '" + k + "' (allowed: " + String.join(", ", names) + ")"));
                } else if (extra != null && extra.isObject()) {
                    errs.addAll(validate(e.getValue(), extra, root, path + "." + k));
                }
            }
        }
        return errs;
    }

    private static boolean isType(JsonNode v, String t) {
        return switch (t) {
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "boolean" -> v.isBoolean();
            case "null" -> v.isNull();
            default -> false;
        };
    }

    /** Python equality of parsed JSON: 1 == 1.0, otherwise structural. */
    static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return compare(a, b) == 0;
        return a.equals(b);
    }

    private static int compare(JsonNode a, JsonNode b) {
        return a.decimalValue().compareTo(b.decimalValue());
    }
}
