package io.github.jelmer.junitsubunit;

import io.github.jelmer.junitsubunit.sample.MixedSample;
import io.github.jelmer.junitsubunit.sample.PassingSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainIntegrationTest {

    private static final String PASSING = PassingSample.class.getName();
    private static final String MIXED = MixedSample.class.getName();

    @Test
    void testList() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"--list", "--select-class=" + PASSING}, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        for (SubunitV2Reader.Packet p : packets) {
            assertEquals(SubunitV2Writer.Status.EXISTS, p.status);
            assertTrue(p.runnable);
            assertNull(p.timestamp);
        }
        Set<String> ids = packets.stream().map(p -> p.testId).collect(Collectors.toSet());
        assertTrue(ids.stream().anyMatch(s -> s.contains("addition()")));
        assertTrue(ids.stream().anyMatch(s -> s.contains("concatenation()")));
    }

    @Test
    void testSuccess() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"--select-class=" + PASSING}, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(4, packets.size());

        long inprogress = packets.stream()
                .filter(p -> p.status == SubunitV2Writer.Status.INPROGRESS).count();
        long success = packets.stream()
                .filter(p -> p.status == SubunitV2Writer.Status.SUCCESS).count();
        assertEquals(2, inprogress);
        assertEquals(2, success);
        for (SubunitV2Reader.Packet p : packets) {
            assertNotNull(p.timestamp);
            assertNotNull(p.testId);
        }
    }

    @Test
    void testFailure() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"--select-method=" + MIXED + "#boom"}, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        SubunitV2Reader.Packet inprogress = packets.get(0);
        SubunitV2Reader.Packet finished = packets.get(1);
        assertEquals(SubunitV2Writer.Status.INPROGRESS, inprogress.status);
        assertEquals(SubunitV2Writer.Status.FAIL, finished.status);
        assertAll(
                () -> assertEquals("traceback", finished.fileName),
                () -> assertEquals("text/plain;charset=utf8", finished.mimeType),
                () -> assertTrue(finished.eof),
                () -> assertNotNull(finished.fileContent),
                () -> assertTrue(new String(finished.fileContent, StandardCharsets.UTF_8)
                        .contains("boom!"))
        );
    }

    @Test
    void testXfail() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {
                "--select-method=" + MIXED + "#skippedByAssumption"
        }, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        assertEquals(SubunitV2Writer.Status.XFAIL, packets.get(1).status);
    }

    @Test
    void testLoadList(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream discovery = new ByteArrayOutputStream();
        Main.run(new String[] {"--list", "--select-class=" + PASSING}, discovery);
        List<SubunitV2Reader.Packet> all = SubunitV2Reader.readAll(discovery.toByteArray());
        String additionId = all.stream()
                .map(p -> p.testId)
                .filter(s -> s.contains("addition()"))
                .findFirst().orElseThrow();

        Path listFile = tmp.resolve("ids.txt");
        Files.writeString(listFile, additionId + "\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"--load-list=" + listFile}, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        for (SubunitV2Reader.Packet p : packets) {
            assertEquals(additionId, p.testId);
        }
    }

    @Test
    void testPositionalId() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {PASSING + "#addition"}, out);
        assertEquals(0, rc);

        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        assertEquals(SubunitV2Writer.Status.INPROGRESS, packets.get(0).status);
        assertEquals(SubunitV2Writer.Status.SUCCESS, packets.get(1).status);
        assertTrue(packets.get(0).testId.contains("addition()"));
    }

    @Test
    void testNoSelectors() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {}, out);
        assertEquals(2, rc);
        assertEquals(0, out.size());
    }
}
