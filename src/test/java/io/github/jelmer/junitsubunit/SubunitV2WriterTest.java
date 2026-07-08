package io.github.jelmer.junitsubunit;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SubunitV2WriterTest {

    @Test
    void vliOneByte() {
        assertArrayEquals(new byte[] {0x00}, SubunitV2Writer.encodeVli(0));
        assertArrayEquals(new byte[] {0x0c}, SubunitV2Writer.encodeVli(12));
        assertArrayEquals(new byte[] {0x3f}, SubunitV2Writer.encodeVli(63));
    }

    @Test
    void vliTwoByte() {
        assertArrayEquals(new byte[] {0x41, 0x2c}, SubunitV2Writer.encodeVli(300));
        assertArrayEquals(new byte[] {(byte) 0x7f, (byte) 0xff},
                SubunitV2Writer.encodeVli(0x3FFF));
    }

    @Test
    void vliThreeByte() {
        assertArrayEquals(new byte[] {(byte) 0x80, 0x40, 0x00},
                SubunitV2Writer.encodeVli(0x4000));
    }

    @Test
    void vliFourByte() {
        assertArrayEquals(new byte[] {(byte) 0xc0, 0x40, 0x00, 0x00},
                SubunitV2Writer.encodeVli(0x400000));
    }

    // Canonical example from the subunit README: an "exists" packet for
    // test id "foo" encodes to b3 2901 0c 03 66 6f 6f 08 55 5f 1b.
    @Test
    void trivialExistsPacket() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SubunitV2Writer(out).exists("foo");

        byte[] expected = {
                (byte) 0xb3,
                (byte) 0x29, (byte) 0x01,
                (byte) 0x0c,
                (byte) 0x03,
                (byte) 0x66, (byte) 0x6f, (byte) 0x6f,
                (byte) 0x08, (byte) 0x55, (byte) 0x5f, (byte) 0x1b
        };
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    void packetWithTimestampAndTags() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SubunitV2Writer w = new SubunitV2Writer(out);
        w.status("mytest", SubunitV2Writer.Status.INPROGRESS, true,
                 Instant.ofEpochSecond(1_700_000_000L, 500_000_000),
                 List.of("slow"),
                 null, null, null, false, null);

        byte[] bytes = out.toByteArray();
        assertEquals((byte) 0xb3, bytes[0]);
        int flags = ((bytes[1] & 0xff) << 8) | (bytes[2] & 0xff);
        assertEquals(0x2000, flags & 0xF000);
        assertEquals(0x0800, flags & 0x0800);
        assertEquals(0x0200, flags & 0x0200);
        assertEquals(0x0100, flags & 0x0100);
        assertEquals(0x0080, flags & 0x0080);
        assertEquals(0x0002, flags & 0x0007);
    }

    @Test
    void fileContentRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SubunitV2Writer w = new SubunitV2Writer(out);
        byte[] payload = "boom".getBytes(StandardCharsets.UTF_8);
        w.status("t", SubunitV2Writer.Status.FAIL, true,
                 Instant.ofEpochSecond(1_700_000_000L, 0),
                 null,
                 "text/plain;charset=utf8", "traceback", payload, true, null);

        SubunitV2Reader.Packet p = SubunitV2Reader.readOne(out.toByteArray());
        assertEquals("t", p.testId);
        assertEquals(SubunitV2Writer.Status.FAIL, p.status);
        assertEquals("text/plain;charset=utf8", p.mimeType);
        assertEquals("traceback", p.fileName);
        assertArrayEquals(payload, p.fileContent);
        assertEquals(true, p.eof);
    }
}
