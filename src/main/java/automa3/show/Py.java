package automa3.show;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Number formatting and rounding exactly as the Python show creator did it ({@code round}, {@code repr},
 * {@code format(v, "g")}), so the generated grandMA3 commands, the brief and the validator messages are the same
 * as before the port.
 */
final class Py {

    private Py() {
    }

    /** {@code round(v, digits)}: half to even on the exact binary value. */
    static double round(double v, int digits) {
        if (!Double.isFinite(v)) return v;
        return new BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code round(v)} to an integer: half to even on the exact binary value. */
    static long roundInt(double v) {
        return new BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** {@code repr(float)}: shortest representation, "1.0", "1e-05", "1e+16". */
    static String repr(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        if (v == 0) return (1 / v < 0) ? "-0.0" : "0.0";
        BigDecimal d = new BigDecimal(Double.toString(v)).stripTrailingZeros(); // shortest digits (Java 19+)
        String digits = d.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - d.scale(); // decimal exponent of the first digit
        String sign = v < 0 ? "-" : "";
        if (exp < -4 || exp >= 16) {
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        String plain = d.abs().toPlainString();
        return sign + (plain.contains(".") ? plain : plain + ".0");
    }

    /** {@code format(v, "g")}: 6 significant digits, no trailing zeros, exponent below 1e-4 or from 1e6. */
    static String g(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        if (v == 0) return (1 / v < 0) ? "-0" : "0";
        BigDecimal d = new BigDecimal(v).round(new java.math.MathContext(6, RoundingMode.HALF_EVEN));
        int exp = d.precision() - 1 - d.scale();
        String sign = v < 0 ? "-" : "";
        if (exp < -4 || exp >= 6) {
            String digits = d.unscaledValue().abs().toString().replaceAll("0+$", "");
            if (digits.isEmpty()) digits = "0";
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        String plain = d.abs().stripTrailingZeros().toPlainString();
        return sign + plain;
    }

    /** {@code format(v, ".0f")}: no decimals, half to even. */
    static String f0(double v) {
        return new BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** The compiler's {@code _num}: integers without ".0", other numbers with "g". */
    static String num(JsonNode v) {
        if (v.isIntegralNumber()) return v.bigIntegerValue().toString();
        return num(v.doubleValue());
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e18) return Long.toString((long) v);
        return g(v);
    }

    /** {@code format(v, "g")} of a JSON number that may be an integer. */
    static String g(JsonNode v) {
        return v.isIntegralNumber() ? v.bigIntegerValue().toString() : g(v.doubleValue());
    }

    /** {@code str(v)} of a JSON value. */
    static String str(JsonNode v) {
        if (v == null || v.isNull()) return "None";
        if (v.isTextual()) return v.textValue();
        return repr(v);
    }

    /** {@code repr(v)} of a JSON value (as Python prints the parsed value). */
    static String repr(JsonNode v) {
        if (v == null || v.isNull()) return "None";
        if (v.isBoolean()) return v.booleanValue() ? "True" : "False";
        if (v.isIntegralNumber()) return v.bigIntegerValue().toString();
        if (v.isNumber()) return repr(v.doubleValue());
        if (v.isTextual()) return reprString(v.textValue());
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < v.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(v.get(i)));
            }
            return sb.append(']').toString();
        }
        StringBuilder sb = new StringBuilder("{");
        Iterator<Map.Entry<String, JsonNode>> it = v.fields();
        boolean first = true;
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!first) sb.append(", ");
            first = false;
            sb.append(reprString(e.getKey())).append(": ").append(repr(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** {@code repr(list)} of Java values (strings, numbers). */
    static String repr(List<?> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            Object o = values.get(i);
            sb.append(o instanceof String s ? reprString(s) : String.valueOf(o));
        }
        return sb.append(']').toString();
    }

    /** {@code repr(str)}: single quotes unless the text has a single quote and no double quote. */
    static String reprString(String s) {
        char q = s.contains("'") && !s.contains("\"") ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == q || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 32 || c == 127) sb.append(String.format("\\x%02x", (int) c));
            else sb.append(c);
        }
        return sb.append(q).toString();
    }

    /** Python type name of a JSON value, for "expected ..., got <type>". */
    static String typeName(JsonNode v) {
        if (v == null || v.isNull()) return "NoneType";
        if (v.isBoolean()) return "bool";
        if (v.isIntegralNumber()) return "int";
        if (v.isNumber()) return "float";
        if (v.isTextual()) return "str";
        if (v.isArray()) return "list";
        return "dict";
    }
}
