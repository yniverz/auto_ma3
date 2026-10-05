package automa3.show;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything a show build needs besides the show files: the target console's grandMA3 version and command
 * overrides, the reserved ranges, the speed master AutoMA3 drives, and where grandMA3 keeps its files.
 */
public record ShowSettings(String ma3Version, Path ma3Root, Range groups, Range sequences, Range matricks, int page,
                           Range executors, String cueMethod, String audience, int speedMaster,
                           Map<String, String> commandOverrides) {

    /** Inclusive number range. */
    public record Range(int start, int end) {
        public boolean contains(int n) {
            return n >= start && n <= end;
        }

        public int size() {
            return end - start + 1;
        }

        static Range of(int[] v, String what) throws ShowException {
            if (v == null || v.length != 2 || v[0] < 1 || v[0] > v[1]) {
                throw new ShowException(what + " must be [start, end] with 1 <= start <= end");
            }
            return new Range(v[0], v[1]);
        }
    }

    /** Sub-folder of grandMA3's plugin folder where jobs and logs are exchanged with the plugin. */
    public static final String EXCHANGE_DIR = "showcreator";

    public Path libraryDir() {
        return ma3Root.resolve("gma3_library");
    }

    public Path pluginsDir() {
        return libraryDir().resolve("datapools").resolve("plugins");
    }

    public Path exchangeDir() {
        return pluginsDir().resolve(EXCHANGE_DIR);
    }
}
