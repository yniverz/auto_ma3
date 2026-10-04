package automa3;

import java.io.InputStream;
import java.util.Properties;

/**
 * The app's version (from version.properties, filled in by the build) and version comparison.
 */
public final class Version {

    private static final String CURRENT = load();

    private Version() {
    }

    public static String current() {
        return CURRENT;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "").trim();
            return v.isEmpty() || v.contains("${") ? "dev" : v;
        } catch (Exception e) {
            return "dev";
        }
    }

    /** Compare dotted versions numerically ("v" prefix ignored); "dev" is older than everything. */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty() || !Character.isDigit(s.charAt(0))) return new int[]{-1};
        String[] p = s.split("[.\\-+]");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].replaceAll("\\D.*", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
