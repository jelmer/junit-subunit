package io.github.jelmer.junitsubunit;

import io.github.jelmer.junitsubunit.sample.MixedFixture;
import io.github.jelmer.junitsubunit.sample.PassingFixture;
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

    private static final String PASSING = PassingFixture.class.getName();
    private static final String MIXED = MixedFixture.class.getName();

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

    @Test
    void testShortSelectClass() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"-c", PASSING}, out);
        assertEquals(0, rc);
        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(4, packets.size());
    }

    @Test
    void testShortListAndSelectMethod() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"-l", "-m", MIXED + "#boom"}, out);
        assertEquals(0, rc);
        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(1, packets.size());
        assertEquals(SubunitV2Writer.Status.EXISTS, packets.get(0).status);
        assertTrue(packets.get(0).testId.contains("boom"));
    }

    @Test
    void testExcludeClassnameFilter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {
                "--list",
                "-p", "io.github.jelmer.junitsubunit.sample",
                "-N", ".*MixedFixture"
        }, out);
        assertEquals(0, rc);
        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        Set<String> ids = packets.stream().map(p -> p.testId).collect(Collectors.toSet());
        assertTrue(ids.stream().allMatch(s -> !s.contains("MixedFixture")));
        assertTrue(ids.stream().anyMatch(s -> s.contains("PassingFixture")));
    }

    @Test
    void testExcludeEngineFilter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {
                "--list",
                "--select-class=" + PASSING,
                "-E", "junit-jupiter"
        }, out);
        assertEquals(0, rc);
        assertEquals(0, SubunitV2Reader.readAll(out.toByteArray()).size());
    }

    @Test
    void testUnknownOptionReports() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {"--nope"}, out);
        assertEquals(2, rc);
    }

    @Test
    void testExtraClasspath(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("ExtraFixture.java");
        Files.writeString(src, ""
                + "package extra;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "public class ExtraFixture {\n"
                + "  @Test void ok() { assertEquals(2, 1 + 1); }\n"
                + "}\n");
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return;
        }
        Path outDir = tmp.resolve("classes");
        Files.createDirectories(outDir);
        int compile = javac.run(null, null, null,
                "-d", outDir.toString(),
                "-cp", System.getProperty("java.class.path"),
                src.toString());
        assertEquals(0, compile);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {
                "--classpath=" + outDir,
                "--select-class=extra.ExtraFixture"
        }, out);
        assertEquals(0, rc);
        List<SubunitV2Reader.Packet> packets = SubunitV2Reader.readAll(out.toByteArray());
        assertEquals(2, packets.size());
        assertEquals(SubunitV2Writer.Status.INPROGRESS, packets.get(0).status);
        assertEquals(SubunitV2Writer.Status.SUCCESS, packets.get(1).status);
        assertTrue(packets.get(0).testId.contains("ExtraFixture"));
    }
}
