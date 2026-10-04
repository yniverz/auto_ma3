package automa3.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMigrationTest {

    private static Config load(String json) throws Exception {
        Path dir = Files.createTempDirectory("automa3-config");
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return new ConfigStore(file).get();
    }

    @Test
    void newConfigUsesPort8081() throws Exception {
        Path dir = Files.createTempDirectory("automa3-config");
        Config c = new ConfigStore(dir.resolve("config.json")).get();
        assertEquals(8081, c.webPort);
        assertEquals(Config.CURRENT_CONFIG_VERSION, c.configVersion);
        assertTrue(Files.readString(dir.resolve("config.json")).contains("\"configVersion\" : 2"), "version saved");
    }

    @Test
    void oldConfigWithTheOldDefaultMovesTo8081() throws Exception {
        Path dir = Files.createTempDirectory("automa3-config");
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"webPort\": 8080, \"looks\": []}");
        Config c = new ConfigStore(file).get();
        assertEquals(8081, c.webPort);
        assertEquals(Config.CURRENT_CONFIG_VERSION, c.configVersion);
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"webPort\" : 8081") && saved.contains("\"configVersion\" : 2"), "migrated file written once");
    }

    @Test
    void otherPortsAndNewerConfigsAreKept() throws Exception {
        assertEquals(9000, load("{\"webPort\": 9000}").webPort, "a port the user chose stays");
        assertEquals(8080, load("{\"webPort\": 8080, \"configVersion\": 2}").webPort, "8080 chosen after the change stays");
    }

    @Test
    void savedSetupsAreMigratedWhenLoaded() throws Exception {
        Config old = ConfigStore.JSON.readValue("{\"webPort\": 8080}", Config.class);
        assertEquals(8081, ConfigStore.normalize(old).webPort);
    }
}
