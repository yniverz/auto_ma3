package automa3.ma3;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OSC 1.0/1.1 message encoder and decoder. grandMA3 does not support bundles,
 * so only single messages are sent; received bundles are unpacked anyway.
 */
public final class OscCodec {

    private OscCodec() {
    }

    public record Message(String address, List<Object> args) {
        public String str(int i) {
            return i < args.size() && args.get(i) != null ? args.get(i).toString() : null;
        }

        public Double num(int i) {
            if (i >= args.size()) return null;
            Object o = args.get(i);
            if (o instanceof Number n) return n.doubleValue();
            if (o instanceof Boolean b) return b ? 1.0 : 0.0;
            if (o instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(address);
            for (Object a : args) sb.append(' ').append(a instanceof String ? "\"" + a + "\"" : a);
            return sb.toString();
        }
    }

    public static byte[] encode(String address, Object... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, address);
        StringBuilder tags = new StringBuilder(",");
        for (Object a : args) {
            if (a instanceof Integer || a instanceof Short || a instanceof Byte) tags.append('i');
            else if (a instanceof Long) tags.append('h');
            else if (a instanceof Float) tags.append('f');
            else if (a instanceof Double) tags.append('f');
            else if (a instanceof Boolean b) tags.append(b ? 'T' : 'F');
            else if (a == null) tags.append('N');
            else tags.append('s');
        }
        writeString(out, tags.toString());
        for (Object a : args) {
            if (a instanceof Integer || a instanceof Short || a instanceof Byte) {
                out.writeBytes(ByteBuffer.allocate(4).putInt(((Number) a).intValue()).array());
            } else if (a instanceof Long l) {
                out.writeBytes(ByteBuffer.allocate(8).putLong(l).array());
            } else if (a instanceof Float || a instanceof Double) {
                out.writeBytes(ByteBuffer.allocate(4).putFloat(((Number) a).floatValue()).array());
            } else if (a instanceof Boolean || a == null) {
                // no payload
            } else {
                writeString(out, a.toString());
            }
        }
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(b);
        int pad = 4 - (b.length % 4);
        for (int i = 0; i < pad; i++) out.write(0);
    }

    /** Decode a packet into messages (one, or several if it is a bundle). */
    public static List<Message> decode(byte[] data, int length) {
        List<Message> result = new ArrayList<>();
        decodeInto(ByteBuffer.wrap(data, 0, length).slice(), result);
        return result;
    }

    private static void decodeInto(ByteBuffer buf, List<Message> result) {
        if (buf.remaining() < 4) return;
        String first = readString(buf);
        if ("#bundle".equals(first)) {
            if (buf.remaining() < 8) return;
            buf.getLong(); // timetag
            while (buf.remaining() >= 4) {
                int size = buf.getInt();
                if (size <= 0 || size > buf.remaining()) return;
                ByteBuffer element = buf.slice(buf.position(), size);
                buf.position(buf.position() + size);
                decodeInto(element, result);
            }
            return;
        }
        List<Object> args = new ArrayList<>();
        if (buf.remaining() > 0) {
            String tags = readString(buf);
            for (int i = 1; i < tags.length() && buf.remaining() >= 0; i++) {
                switch (tags.charAt(i)) {
                    case 'i' -> args.add(buf.getInt());
                    case 'h' -> args.add(buf.getLong());
                    case 'f' -> args.add(buf.getFloat());
                    case 'd' -> args.add(buf.getDouble());
                    case 's', 'S' -> args.add(readString(buf));
                    case 'T' -> args.add(Boolean.TRUE);
                    case 'F' -> args.add(Boolean.FALSE);
                    case 'N', 'I' -> args.add(null);
                    case 't' -> args.add(buf.getLong());
                    case 'b' -> {
                        int n = buf.getInt();
                        byte[] blob = new byte[n];
                        buf.get(blob);
                        buf.position(buf.position() + ((4 - n % 4) % 4));
                        args.add(blob);
                    }
                    default -> {
                        return; // unknown type tag: stop parsing this message
                    }
                }
            }
        }
        result.add(new Message(first, args));
    }

    private static String readString(ByteBuffer buf) {
        int start = buf.position();
        int end = start;
        while (end < buf.limit() && buf.get(end) != 0) end++;
        byte[] b = new byte[end - start];
        buf.get(b);
        int consumed = end - start + 1;
        int padded = (consumed + 3) & ~3;
        buf.position(Math.min(buf.limit(), start + padded));
        return new String(b, StandardCharsets.UTF_8);
    }
}
