package io.github.jelmer.junitsubunit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.zip.CRC32;

public final class SubunitV2Writer {

    public enum Status {
        UNDEFINED(0),
        EXISTS(1),
        INPROGRESS(2),
        SUCCESS(3),
        UXSUCCESS(4),
        SKIP(5),
        FAIL(6),
        XFAIL(7);

        final int bits;
        Status(int bits) { this.bits = bits; }
    }

    private static final int SIGNATURE = 0xB3;
    private static final int VERSION2 = 0x2000;

    private static final int FLAG_TEST_ID       = 0x0800;
    private static final int FLAG_ROUTING_CODE  = 0x0400;
    private static final int FLAG_TIMESTAMP     = 0x0200;
    private static final int FLAG_RUNNABLE      = 0x0100;
    private static final int FLAG_TAGS          = 0x0080;
    private static final int FLAG_FILE_CONTENT  = 0x0040;
    private static final int FLAG_MIME_TYPE     = 0x0020;
    private static final int FLAG_EOF           = 0x0010;

    private static final int MAX_PACKET_LENGTH = 4 * 1024 * 1024 - 1;

    private final OutputStream out;

    public SubunitV2Writer(OutputStream out) {
        this.out = out;
    }

    public synchronized void status(String testId,
                                    Status status,
                                    boolean runnable,
                                    Instant timestamp,
                                    Collection<String> tags,
                                    String mimeType,
                                    String fileName,
                                    byte[] fileContent,
                                    boolean eof,
                                    String routingCode) throws IOException {
        ByteBuf buf = new ByteBuf();

        int flags = VERSION2 | (status.bits & 0x7);
        if (runnable) flags |= FLAG_RUNNABLE;
        if (eof) flags |= FLAG_EOF;
        if (testId != null) flags |= FLAG_TEST_ID;
        if (routingCode != null) flags |= FLAG_ROUTING_CODE;
        if (timestamp != null) flags |= FLAG_TIMESTAMP;
        if (tags != null && !tags.isEmpty()) flags |= FLAG_TAGS;
        if (fileName != null) flags |= FLAG_FILE_CONTENT;
        if (mimeType != null) flags |= FLAG_MIME_TYPE;

        buf.writeShortBE(flags);

        ByteBuf body = new ByteBuf();
        if (timestamp != null) {
            body.writeIntBE((int) timestamp.getEpochSecond());
            body.writeVli(timestamp.getNano());
        }
        if (testId != null) {
            body.writeString(testId);
        }
        if (tags != null && !tags.isEmpty()) {
            body.writeVli(tags.size());
            for (String tag : tags) {
                body.writeString(tag);
            }
        }
        if (mimeType != null) {
            body.writeString(mimeType);
        }
        if (fileName != null) {
            body.writeString(fileName);
            byte[] content = fileContent != null ? fileContent : new byte[0];
            body.writeVli(content.length);
            body.write(content);
        }
        if (routingCode != null) {
            body.writeString(routingCode);
        }

        // The length prefix is VLI-encoded and its size is part of the total,
        // so probe for the smallest VLI that fits.
        int bodyLen = body.size();
        int totalLen = -1;
        int lenPrefixSize = -1;
        for (int probe = 1; probe <= 4; probe++) {
            int candidate = 1 /* sig */ + 2 /* flags */ + probe + bodyLen + 4 /* crc */;
            if (vliSize(candidate) == probe) {
                totalLen = candidate;
                lenPrefixSize = probe;
                break;
            }
        }
        if (totalLen < 0 || totalLen > MAX_PACKET_LENGTH) {
            throw new IOException("subunit v2 packet too large: " + totalLen);
        }

        ByteBuf packet = new ByteBuf();
        packet.write(SIGNATURE);
        packet.writeBytes(buf.toByteArray());
        packet.writeVliWithSize(totalLen, lenPrefixSize);
        packet.writeBytes(body.toByteArray());

        CRC32 crc = new CRC32();
        crc.update(packet.toByteArray(), 0, packet.size());
        packet.writeIntBE((int) crc.getValue());

        out.write(packet.toByteArray());
        out.flush();
    }

    public void exists(String testId) throws IOException {
        status(testId, Status.EXISTS, true, null, null, null, null, null, false, null);
    }

    static int vliSize(int value) {
        if (value < 0) throw new IllegalArgumentException("negative VLI: " + value);
        if (value <= 0x3F) return 1;
        if (value <= 0x3FFF) return 2;
        if (value <= 0x3FFFFF) return 3;
        if (value <= 0x3FFFFFFF) return 4;
        throw new IllegalArgumentException("VLI overflow: " + value);
    }

    static byte[] encodeVli(int value) {
        int size = vliSize(value);
        byte[] out = new byte[size];
        return encodeVliInto(value, size, out, 0);
    }

    private static byte[] encodeVliInto(int value, int size, byte[] out, int off) {
        switch (size) {
            case 1:
                out[off] = (byte) (value & 0x3F);
                break;
            case 2:
                out[off]     = (byte) (0x40 | ((value >> 8) & 0x3F));
                out[off + 1] = (byte) (value & 0xFF);
                break;
            case 3:
                out[off]     = (byte) (0x80 | ((value >> 16) & 0x3F));
                out[off + 1] = (byte) ((value >> 8) & 0xFF);
                out[off + 2] = (byte) (value & 0xFF);
                break;
            case 4:
                out[off]     = (byte) (0xC0 | ((value >> 24) & 0x3F));
                out[off + 1] = (byte) ((value >> 16) & 0xFF);
                out[off + 2] = (byte) ((value >> 8) & 0xFF);
                out[off + 3] = (byte) (value & 0xFF);
                break;
            default:
                throw new IllegalArgumentException("VLI size out of range: " + size);
        }
        return out;
    }

    private static final class ByteBuf {
        private byte[] buf = new byte[64];
        private int len = 0;

        void write(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        void write(byte[] data) {
            writeBytes(data);
        }

        void writeBytes(byte[] data) {
            ensure(data.length);
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;
        }

        void writeShortBE(int value) {
            ensure(2);
            buf[len++] = (byte) ((value >> 8) & 0xFF);
            buf[len++] = (byte) (value & 0xFF);
        }

        void writeIntBE(int value) {
            ensure(4);
            buf[len++] = (byte) ((value >> 24) & 0xFF);
            buf[len++] = (byte) ((value >> 16) & 0xFF);
            buf[len++] = (byte) ((value >> 8) & 0xFF);
            buf[len++] = (byte) (value & 0xFF);
        }

        void writeVli(int value) {
            int size = vliSize(value);
            ensure(size);
            encodeVliInto(value, size, buf, len);
            len += size;
        }

        void writeVliWithSize(int value, int size) {
            if (vliSize(value) != size) {
                throw new IllegalArgumentException(
                        "VLI size mismatch: value=" + value + " requested=" + size);
            }
            ensure(size);
            encodeVliInto(value, size, buf, len);
            len += size;
        }

        void writeString(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVli(bytes.length);
            writeBytes(bytes);
        }

        int size() { return len; }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }

        private void ensure(int extra) {
            if (len + extra > buf.length) {
                int cap = buf.length;
                while (cap < len + extra) cap <<= 1;
                byte[] bigger = new byte[cap];
                System.arraycopy(buf, 0, bigger, 0, len);
                buf = bigger;
            }
        }
    }
}
