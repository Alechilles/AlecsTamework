package com.alechilles.alecstamework.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesDuplicateFingerprintAndPrunesOldest() throws Exception {
        Path pendingDir = tempDir.resolve("telemetry").resolve("crash-reports").resolve("pending");
        CrashReportStore store = new CrashReportStore(pendingDir, 2, null);

        CrashReportEnvelope first = report("fingerprint-a", "first");
        CrashReportEnvelope duplicate = report("fingerprint-a", "duplicate");
        CrashReportEnvelope second = report("fingerprint-b", "second");
        CrashReportEnvelope third = report("fingerprint-c", "third");

        assertEquals(CrashReportStore.WriteResult.WRITTEN, store.persist(first));
        assertEquals(CrashReportStore.WriteResult.UPDATED, store.persist(duplicate));

        List<CrashReportStore.PendingReport> afterDuplicate = store.listPendingReports(10);
        assertEquals(1, afterDuplicate.size());
        JsonObject duplicateJson = JsonParser.parseString(afterDuplicate.get(0).payload()).getAsJsonObject();
        assertEquals(2, duplicateJson.get("occurrenceCount").getAsInt());
        assertTrue(duplicateJson.has("lastCapturedAtUtc"));

        assertEquals(CrashReportStore.WriteResult.WRITTEN, store.persist(second));
        assertEquals(CrashReportStore.WriteResult.WRITTEN, store.persist(third));

        assertEquals(2, store.pendingCount());

        List<CrashReportStore.PendingReport> pending = store.listPendingReports(10);
        assertEquals(2, pending.size());
        assertFalse(pending.stream().anyMatch(report -> report.path().getFileName().toString().contains("fingerprint-a")));

        assertTrue(Files.list(pendingDir).noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }

    private static CrashReportEnvelope report(String fingerprint, String message) {
        RuntimeException throwable = new RuntimeException(message);
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.TestClass", "run", "TestClass.java", 1)
        });
        CrashAttribution.AttributionResult attribution = CrashAttribution.classify(
                throwable,
                new com.hypixel.hytale.common.plugin.PluginIdentifier("Alechilles", "Alec's Tamework!")
        );

        return CrashReportEnvelope.create(
                "unit_test",
                fingerprint,
                "Alechilles:Alec's Tamework!",
                "2.7.3",
                "TestThread",
                null,
                null,
                null,
                attribution,
                throwable
        );
    }
}
