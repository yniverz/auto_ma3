package automa3.show;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Writes data as a Lua table literal: the job file the ShowBuilder plugin loads with loadfile. */
final class LuaWriter {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false",
            "for", "function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
            "until", "while");

    private LuaWriter() {
    }

    static String str(String s) {
        StringBuilder out = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            if (cp == '\\') out.append("\\\\");
            else if (cp == '"') out.append("\\\"");
            else if (cp == '\n') out.append("\\n");
            else if (cp == '\r') out.append("\\r");
            else if (cp == '\t') out.append("\\t");
            else if (cp < 32 || cp == 127) out.append(String.format("\\%03d", cp));
            else out.appendCodePoint(cp); // UTF-8 passes through; Lua strings are byte strings
        });
        return out.append('"').toString();
    }

    /** Values: null, Boolean, Integer/Long, Double, String, List, Map (string keys, kept in order). */
    static String write(Object v, int indent) {
        String pad = "  ".repeat(indent), pad2 = "  ".repeat(indent + 1);
        if (v == null) return "nil";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Integer || v instanceof Long) return v.toString();
        if (v instanceof Double d) {
            if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite number in job data");
            return Py.repr(d);
        }
        if (v instanceof String s) return str(s);
        if (v instanceof List<?> list) {
            if (list.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            for (Object x : list) sb.append(pad2).append(write(x, indent + 1)).append(",\n");
            return sb.append(pad).append('}').toString();
        }
        if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new IllegalArgumentException("only string keys are supported");
                String key = IDENT.matcher(k).matches() && !KEYWORDS.contains(k) ? k : "[" + str(k) + "]";
                sb.append(pad2).append(key).append(" = ").append(write(e.getValue(), indent + 1)).append(",\n");
            }
            return sb.append(pad).append('}').toString();
        }
        throw new IllegalArgumentException("cannot serialise " + v.getClass().getSimpleName());
    }
}
