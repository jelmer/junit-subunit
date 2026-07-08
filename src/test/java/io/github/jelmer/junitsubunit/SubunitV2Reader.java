package io.github.jelmer.junitsubunit;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

final class SubunitV2Reader {

    static class Packet {
        String testId;
        SubunitV2Writer.Status status;
        boolean runnable;
        boolean eof;
        Instant timestamp;
        List<String> tags;
        String mimeType;
        String fileName;
        byte[] fileContent;
        String routingCode;
    }

    static Packet readOne(byte[] data) throws IOException {
        List<Packet> packets = readAll(data);
        if (packets.size() != 1) {
            throw new IOException("expected 1 packet, got " + packets.size());
        }
        return packets.get(0);
    }

    static List<Packet> readAll(byte[] data) throws IOException {
        List<Packet> out = new ArrayList<>();
        int off = 0;
        while (off < data.length) {
            int start = off;
            if ((data[off] & 0xFF) != 0xB3) {
                throw new IOException("bad signature at offset " + off);
            }
            off++;
            int flags = readShortBE(data, off); off += 2;
            int[] len = readVli(data, off);
            int packetLen = len[0];
            off += len[1];

            int bodyEnd = start + packetLen - 4;

            Packet p = new Packet();
            int status = flags & 0x7;
            p.status = SubunitV2Writer.Status.values()[status];
            p.runnable = (flags & 0x0100) != 0;
            p.eof = (flags & 0x0010) != 0;

            if ((flags & 0x0200) != 0) {
                long secs = ((long) readIntBE(data, off)) & 0xFFFFFFFFL;
                off += 4;
                int[] ns = readVli(data, off);
                off += ns[1];
                p.timestamp = Instant.ofEpochSecond(secs, ns[0]);
            }
            if ((flags & 0x0800) != 0) {
                Object[] sr = readString(data, off);
                p.testId = (String) sr[0];
                off = (int) sr[1];
            }
            if ((flags & 0x0080) != 0) {
                int[] count = readVli(data, off);
                off += count[1];
                p.tags = new ArrayList<>();
                for (int i = 0; i < count[0]; i++) {
                    Object[] sr = readString(data, off);
                    p.tags.add((String) sr[0]);
                    off = (int) sr[1];
                }
            }
            if ((flags & 0x0020) != 0) {
                Object[] sr = readString(data, off);
                p.mimeType = (String) sr[0];
                off = (int) sr[1];
            }
            if ((flags & 0x0040) != 0) {
                Object[] sr = readString(data, off);
                p.fileName = (String) sr[0];
                off = (int) sr[1];
                int[] clen = readVli(data, off);
                off += clen[1];
                p.fileContent = new byte[clen[0]];
                System.arraycopy(data, off, p.fileContent, 0, clen[0]);
                off += clen[0];
            }
            if ((flags & 0x0400) != 0) {
                Object[] sr = readString(data, off);
                p.routingCode = (String) sr[0];
                off = (int) sr[1];
            }

            if (off != bodyEnd) {
                throw new IOException("body length mismatch at packet start " + start);
            }

            CRC32 crc = new CRC32();
            crc.update(data, start, packetLen - 4);
            long expected = crc.getValue() & 0xFFFFFFFFL;
            long actual = ((long) readIntBE(data, off)) & 0xFFFFFFFFL;
            if (expected != actual) {
                throw new IOException("crc mismatch at packet start " + start
                        + ": expected " + expected + ", got " + actual);
            }
            off += 4;

            out.add(p);
        }
        return out;
    }

    private static int readShortBE(byte[] data, int off) throws IOException {
        require(data, off, 2);
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int readIntBE(byte[] data, int off) throws IOException {
        require(data, off, 4);
        return ((data[off] & 0xFF) << 24)
             | ((data[off + 1] & 0xFF) << 16)
             | ((data[off + 2] & 0xFF) << 8)
             | (data[off + 3] & 0xFF);
    }

    private static int[] readVli(byte[] data, int off) throws IOException {
        require(data, off, 1);
        int first = data[off] & 0xFF;
        int size = 1 + (first >> 6);
        require(data, off, size);
        int value = first & 0x3F;
        for (int i = 1; i < size; i++) {
            value = (value << 8) | (data[off + i] & 0xFF);
        }
        return new int[] {value, size};
    }

    private static Object[] readString(byte[] data, int off) throws IOException {
        int[] len = readVli(data, off);
        off += len[1];
        require(data, off, len[0]);
        String s = new String(data, off, len[0], StandardCharsets.UTF_8);
        return new Object[] {s, off + len[0]};
    }

    private static void require(byte[] data, int off, int n) throws IOException {
        if (off + n > data.length) {
            throw new EOFException("wanted " + n + " bytes at " + off
                    + " of " + data.length);
        }
    }
}
