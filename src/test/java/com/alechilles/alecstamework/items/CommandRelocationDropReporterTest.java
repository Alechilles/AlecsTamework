package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards truthful diagnostics for retry exhaustion without lifecycle inference. */
class CommandRelocationDropReporterTest {

    @Test
    void retryExhaustionReportsDropWithoutClaimingLostState() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        CommandRelocationDropReporter reporter = new CommandRelocationDropReporter(
                null, (level, message) -> diagnostics.add(new Diagnostic(level, message)));

        reporter.report(pending(), 500L);

        assertEquals(1, diagnostics.size());
        assertEquals(Level.WARNING, diagnostics.get(0).level());
        assertTrue(diagnostics.get(0).message().contains(
                "no lifecycle transition was inferred"
        ));
    }

    private PendingRelocation pending() {
        return new PendingRelocation(
                java.util.UUID.randomUUID(),
                new Vector3d(1.0, 2.0, 3.0),
                "world",
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                0L,
                100L,
                false,
                null,
                null
        );
    }

    private record Diagnostic(Level level, String message) {
    }
}
