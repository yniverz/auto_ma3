package automa3.ma3;

import automa3.config.Config;
import automa3.config.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sends actions to every enabled console (each with its own version profile) via OSC "/cmd",
 * and receives OSC from consoles (playback feedback and operator controls).
 */
public class ConsoleHub {

    private static final Logger log = LoggerFactory.getLogger(ConsoleHub.class);

    public record LogEntry(long timeMs, String command, String reason, String target, boolean sent) {
    }

    private final ConfigStore configStore;
    private final DatagramSocket sendSocket;
    private DatagramSocket receiveSocket;
    private Thread receiveThread;
    private final Deque<LogEntry> recent = new ArrayDeque<>();
    private final List<Consumer<LogEntry>> logListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<OscCodec.Message, InetAddress>> oscListeners = new CopyOnWriteArrayList<>();
    private volatile long lastReceiveMs;
    private volatile String lastSendError;
    /** When true nothing is sent to any console; commands are only logged. Runtime only. */
    private volatile boolean dryRun;

    public ConsoleHub(ConfigStore configStore) throws SocketException {
        this.configStore = configStore;
        this.sendSocket = new DatagramSocket();
    }

    public void send(Ma3Action action) {
        Config cfg = configStore.get();
        boolean any = false;
        for (Config.ConsoleConfig console : cfg.consoles) {
            if (!console.enabled) continue;
            any = true;
            Ma3Profile profile = Ma3Profile.forVersion(console.version);
            String command = action.render(profile, console.commandOverrides);
            if (command == null) continue;
            boolean sent = false;
            if (!dryRun) {
                sent = sendCommand(console, command);
            }
            record(new LogEntry(System.currentTimeMillis(), command, action.reason(), console.name, sent));
        }
        if (!any) {
            record(new LogEntry(System.currentTimeMillis(), action.render(Ma3Profile.forVersion(null), null),
                    action.reason(), "(no console)", false));
        }
    }

    private boolean sendCommand(Config.ConsoleConfig console, String command) {
        String prefix = console.prefix == null || console.prefix.isBlank() ? "" : "/" + console.prefix.replace("/", "");
        byte[] data = OscCodec.encode(prefix + "/cmd", command);
        try {
            sendSocket.send(new DatagramPacket(data, data.length, new InetSocketAddress(console.host, console.port)));
            lastSendError = null;
            return true;
        } catch (IOException | IllegalArgumentException e) {
            if (!String.valueOf(e.getMessage()).equals(lastSendError)) {
                log.warn("Cannot send to {} ({}:{}): {}", console.name, console.host, console.port, e.getMessage());
            }
            lastSendError = e.getMessage();
            return false;
        }
    }

    private void record(LogEntry entry) {
        synchronized (recent) {
            recent.addLast(entry);
            while (recent.size() > 300) recent.removeFirst();
        }
        log.debug("{} -> {} ({})", entry.target(), entry.command(), entry.reason());
        for (Consumer<LogEntry> l : logListeners) l.accept(entry);
    }

    public List<LogEntry> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public void onLog(Consumer<LogEntry> listener) {
        logListeners.add(listener);
    }

    public void onOsc(BiConsumer<OscCodec.Message, InetAddress> listener) {
        oscListeners.add(listener);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public long lastReceiveMs() {
        return lastReceiveMs;
    }

    public String lastSendError() {
        return lastSendError;
    }

    public synchronized void startReceiver() {
        Config.OscInConfig in = configStore.get().oscIn;
        if (!in.enabled || receiveThread != null) return;
        try {
            receiveSocket = new DatagramSocket(in.port);
        } catch (SocketException e) {
            log.error("Cannot listen for OSC on port {}: {}", in.port, e.getMessage());
            return;
        }
        receiveThread = new Thread(() -> {
            byte[] buf = new byte[65536];
            while (!receiveSocket.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    receiveSocket.receive(p);
                } catch (IOException e) {
                    if (!receiveSocket.isClosed()) log.warn("OSC receive failed: {}", e.getMessage());
                    continue;
                }
                lastReceiveMs = System.currentTimeMillis();
                try {
                    for (OscCodec.Message m : OscCodec.decode(p.getData(), p.getLength())) {
                        for (var l : oscListeners) l.accept(m, p.getAddress());
                    }
                } catch (RuntimeException e) {
                    log.debug("Bad OSC packet: {}", e.toString());
                }
            }
        }, "osc-in");
        receiveThread.setDaemon(true);
        receiveThread.start();
        log.info("Listening for OSC from consoles on UDP port {}", in.port);
    }

    public synchronized void stop() {
        if (receiveSocket != null) receiveSocket.close();
        receiveThread = null;
        sendSocket.close();
    }
}
