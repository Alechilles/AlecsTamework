package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstelemetry.api.TelemetryDiagnosticBundle;
import com.alechilles.alecstelemetry.api.TelemetryDiagnosticBundleResult;
import com.alechilles.alecstelemetry.api.TelemetryDiagnosticDisposition;
import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PersistenceAutomaticDiagnosticReporterTest {

    @Test
    void submitsOneBoundedBundleForRepeatedFailureSignal() {
        byte[] packageBytes = {1, 2, 3, 4};
        List<TelemetryDiagnosticBundle> submitted = new ArrayList<>();
        PersistenceAutomaticDiagnosticReporter reporter =
                new PersistenceAutomaticDiagnosticReporter(
                        ignored -> new PersistenceDiagnosticExporter.FailurePackage(
                                "support-id", packageBytes, 2
                        ),
                        bundle -> {
                            submitted.add(bundle);
                            return new TelemetryDiagnosticBundleResult(
                                    TelemetryDiagnosticBundleResult.Status.QUEUED,
                                    null
                            );
                        },
                        Runnable::run,
                        null
                );
        PersistenceFailureSignal signal = new PersistenceFailureSignal(
                "persistence_write_failed",
                "write:companion-profile:storage",
                "companion_profile_write",
                "write",
                "storage_failure",
                new IllegalStateException("private detail")
        );

        reporter.accept(signal);
        reporter.accept(signal);

        assertEquals(1, submitted.size());
        TelemetryDiagnosticBundle bundle = submitted.getFirst();
        assertEquals("automatic", bundle.source());
        assertEquals("persistence_failure", bundle.diagnosticKind());
        assertEquals("error", bundle.severity());
        assertEquals(
                TelemetryDiagnosticDisposition.CREATE_OR_JOIN_ISSUE,
                bundle.disposition().mode()
        );
        assertFalse(bundle.disposition().fingerprint().isBlank());
        assertEquals(1, bundle.attachments().size());
        assertEquals("tamework_debugdb_export", bundle.attachments().getFirst().kind());
        assertEquals("application/zip", bundle.attachments().getFirst().contentType());
        assertEquals("base64", bundle.attachments().getFirst().contentEncoding());
        assertArrayEquals(
                packageBytes,
                java.util.Base64.getDecoder().decode(bundle.attachments().getFirst().content())
        );

        reporter.close();
        reporter.accept(new PersistenceFailureSignal(
                "persistence_read_failed", "second", "read", "read",
                "storage_failure", null
        ));
        assertEquals(1, submitted.size());
    }

    @Test
    void packageFailureNeverEscapesOrSubmits() {
        AtomicInteger submissions = new AtomicInteger();
        PersistenceAutomaticDiagnosticReporter reporter =
                new PersistenceAutomaticDiagnosticReporter(
                        ignored -> {
                            throw new IllegalStateException("export failed");
                        },
                        bundle -> {
                            submissions.incrementAndGet();
                            return TelemetryDiagnosticBundleResult.unsupported();
                        },
                        Runnable::run,
                        null
                );

        reporter.accept(new PersistenceFailureSignal(
                "persistence_checkpoint_failed", "checkpoint", "checkpoint",
                "checkpoint", "storage_failure", null
        ));

        assertEquals(0, submissions.get());
        reporter.close();
    }

    @Test
    void queuedReportCanDrainAfterCloseStarts() {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger submissions = new AtomicInteger();
        PersistenceAutomaticDiagnosticReporter reporter =
                new PersistenceAutomaticDiagnosticReporter(
                        ignored -> new PersistenceDiagnosticExporter.FailurePackage(
                                "support-id", new byte[]{1}, 2
                        ),
                        bundle -> {
                            submissions.incrementAndGet();
                            return new TelemetryDiagnosticBundleResult(
                                    TelemetryDiagnosticBundleResult.Status.QUEUED,
                                    null
                            );
                        },
                        queued::add,
                        null
                );

        reporter.accept(new PersistenceFailureSignal(
                "persistence_shutdown_timeout", "shutdown", "shutdown",
                "shutdown", "outstanding_operations", null
        ));
        reporter.close();
        queued.getFirst().run();

        assertEquals(1, submissions.get());
    }
}
