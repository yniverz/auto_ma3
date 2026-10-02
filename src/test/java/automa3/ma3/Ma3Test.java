package automa3.ma3;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Ma3Test {

    @Test
    void oscRoundTrip() {
        byte[] data = OscCodec.encode("/cmd", "Go+ Page 1.201");
        // address "/cmd" = 4 bytes + 4 null padding, tags ",s" padded to 4, string 14 chars padded to 16
        assertEquals(8 + 4 + 16, data.length);
        List<OscCodec.Message> msgs = OscCodec.decode(data, data.length);
        assertEquals(1, msgs.size());
        assertEquals("/cmd", msgs.get(0).address());
        assertEquals("Go+ Page 1.201", msgs.get(0).str(0));
    }

    @Test
    void oscDecodesFeedbackTypes() {
        byte[] data = OscCodec.encode("/13.13.1.6.5", "FaderMaster", 3, 55.5f);
        OscCodec.Message m = OscCodec.decode(data, data.length).get(0);
        assertEquals("FaderMaster", m.str(0));
        assertEquals(3.0, m.num(1));
        assertEquals(55.5, m.num(2), 1e-6);
    }

    @Test
    void profileSelection() {
        assertEquals("2.0", Ma3Profile.forVersion("2.0.2.0").id);
        assertEquals("2.1-2.3", Ma3Profile.forVersion("2.3").id);
        assertEquals("2.4-2.5", Ma3Profile.forVersion("2.5.1.2").id);
        assertEquals("2.6+", Ma3Profile.forVersion("2.7").id);
        assertEquals("1.x", Ma3Profile.forVersion("1.9.7").id);
        assertEquals("2.4-2.5", Ma3Profile.forVersion("garbage").id);
        assertFalse(Ma3Profile.forVersion("2.0").playbackFeedback);
        assertTrue(Ma3Profile.forVersion("2.2").playbackFeedback);
    }

    @Test
    void renderCommands() {
        Ma3Profile p = Ma3Profile.forVersion("2.5");
        assertEquals("Go+ Page 2.105", Ma3Action.exec(Ma3Action.Kind.GO, 2, 105, "").render(p, null));
        assertEquals("Flash Off Page 1.201", Ma3Action.exec(Ma3Action.Kind.FLASH_OFF, 1, 201, "").render(p, null));
        assertEquals("FaderMaster Page 1.201 At 50 Fade 5", Ma3Action.fader(1, 201, 50, 5, "").render(p, null));
        assertEquals("FaderMaster Page 1.201 At 0", Ma3Action.fader(1, 201, 0, 0, "").render(p, null));
        assertEquals("Master 3.1 At BPM 128.5", Ma3Action.bpm(1, 128.5, "").render(p, null));
    }

    @Test
    void speedMasterLimitsPerVersion() {
        // 2.4+: master 16 is the audio BPM master and must not be driven
        assertNull(Ma3Action.bpm(16, 128, "").render(Ma3Profile.forVersion("2.5"), null));
        // 2.0-2.3 only have 15
        assertNull(Ma3Action.bpm(16, 128, "").render(Ma3Profile.forVersion("2.2"), null));
        // above 225 BPM is halved
        assertEquals("Master 3.2 At BPM 120", Ma3Action.bpm(2, 240, "").render(Ma3Profile.forVersion("2.5"), null));
    }

    @Test
    void perConsoleOverride() {
        Ma3Profile p = Ma3Profile.forVersion("2.5");
        String cmd = Ma3Action.exec(Ma3Action.Kind.GO, 1, 101, "")
                .render(p, Map.of("go", "Go+ Executor {page}.{exec}"));
        assertEquals("Go+ Executor 1.101", cmd);
    }

    @Test
    void sendsOscToConsole() throws Exception {
        try (DatagramSocket fakeConsole = new DatagramSocket(0)) {
            fakeConsole.setSoTimeout(2000);
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("automa3");
            automa3.config.Config c = new automa3.config.Config();
            automa3.config.Config.ConsoleConfig cc = new automa3.config.Config.ConsoleConfig();
            cc.port = fakeConsole.getLocalPort();
            cc.prefix = "gma3";
            c.consoles.add(cc);
            java.nio.file.Path file = dir.resolve("c.json");
            automa3.config.ConfigStore.JSON.writeValue(file.toFile(), c);
            ConsoleHub hub = new ConsoleHub(new automa3.config.ConfigStore(file));
            hub.send(Ma3Action.exec(Ma3Action.Kind.GO, 1, 101, "test"));
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            fakeConsole.receive(p);
            OscCodec.Message m = OscCodec.decode(p.getData(), p.getLength()).get(0);
            assertEquals("/gma3/cmd", m.address());
            assertEquals("Go+ Page 1.101", m.str(0));
            hub.stop();
        }
    }
}
