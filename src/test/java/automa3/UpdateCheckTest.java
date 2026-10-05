package automa3;

import automa3.config.ConfigStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckTest {

    @Test
    void versionComparison() {
        assertTrue(Version.compare("1.0.12", "1.0.9") > 0, "numeric, not text");
        assertTrue(Version.compare("v1.1.0", "1.0.99") > 0);
        assertEquals(0, Version.compare("1.0", "1.0.0"));
        assertTrue(Version.compare("1.0.1", "dev") > 0, "dev is older than any release");
    }

    @Test
    void parsesGitHubRelease() throws Exception {
        String json = """
                {"tag_name": "v1.0.12", "draft": false, "html_url": "https://github.com/yniverz/auto_ma3/releases/tag/v1.0.12",
                 "assets": [{"name": "AutoMA3-1.0.12-mac-arm64.zip", "browser_download_url": "https://x/zip", "size": 200}]}""";
        UpdateCheck.Release r = UpdateCheck.parse(ConfigStore.JSON.readTree(json)).orElseThrow();
        assertEquals("1.0.12", r.version());
        assertEquals("https://github.com/yniverz/auto_ma3/releases/tag/v1.0.12", r.pageUrl());
        assertTrue(UpdateCheck.parse(ConfigStore.JSON.readTree("{\"tag_name\": \"v2\", \"draft\": true}")).isEmpty(), "drafts are ignored");
        assertTrue(UpdateCheck.parse(ConfigStore.JSON.readTree("{}")).isEmpty());
    }
}
