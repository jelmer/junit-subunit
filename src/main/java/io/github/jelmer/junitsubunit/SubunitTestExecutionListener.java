package io.github.jelmer.junitsubunit;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

public final class SubunitTestExecutionListener implements TestExecutionListener {

    private static final String TEXT_UTF8 = "text/plain;charset=utf8";

    private final SubunitV2Writer writer;
    private final Clock clock;

    public SubunitTestExecutionListener(SubunitV2Writer writer) {
        this(writer, Clock.systemUTC());
    }

    public SubunitTestExecutionListener(SubunitV2Writer writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (!id.isTest()) return;
        try {
            writer.status(testId(id), SubunitV2Writer.Status.INPROGRESS,
                    true, clock.instant(), null, null, null, null, false, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) return;
        SubunitV2Writer.Status status;
        switch (result.getStatus()) {
            case SUCCESSFUL: status = SubunitV2Writer.Status.SUCCESS; break;
            case ABORTED:    status = SubunitV2Writer.Status.XFAIL; break;
            case FAILED:
            default:         status = SubunitV2Writer.Status.FAIL; break;
        }

        Instant now = clock.instant();
        try {
            if (result.getThrowable().isPresent()) {
                byte[] tb = stackTrace(result.getThrowable().get());
                writer.status(testId(id), status, true, now, null,
                        TEXT_UTF8, "traceback", tb, true, null);
            } else {
                writer.status(testId(id), status, true, now,
                        null, null, null, null, false, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!id.isTest()) return;
        Instant now = clock.instant();
        try {
            if (reason != null && !reason.isEmpty()) {
                writer.status(testId(id), SubunitV2Writer.Status.SKIP, true, now,
                        null, TEXT_UTF8, "reason",
                        reason.getBytes(StandardCharsets.UTF_8), true, null);
            } else {
                writer.status(testId(id), SubunitV2Writer.Status.SKIP, true, now,
                        null, null, null, null, false, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        if (!id.isTest()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entry.getKeyValuePairs().entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            writer.status(testId(id), SubunitV2Writer.Status.UNDEFINED, false,
                    clock.instant(), null,
                    TEXT_UTF8, "reportEntry",
                    sb.toString().getBytes(StandardCharsets.UTF_8), true, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) { }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) { }

    static String testId(TestIdentifier id) {
        return id.getUniqueId();
    }

    private static byte[] stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }
}
