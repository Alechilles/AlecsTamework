package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards truthful diagnostics and pre-shutdown Lost submission for temporary worlds. */
class CommandRelocationDropReporterTest {

    @Test
    void deleteOnRemoveSubmissionReportsAcceptedLostTransition() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        CommandRelocationDropReporter reporter = new CommandRelocationDropReporter(
                null, (level, message) -> diagnostics.add(new Diagnostic(level, message)));
        reporter.setListener((npcUuid, ownerUuid, source, home, destination, queued, dropped, retries) -> true);

        reporter.reportWorldRemoval(candidate());

        assertEquals(1, diagnostics.size());
        assertEquals(Level.INFO, diagnostics.get(0).level());
        assertTrue(diagnostics.get(0).message().contains("lostTransitionSubmitted=true"));
    }

    @Test
    void rejectedLostTransitionNeverClaimsCompanionWasMarkedLost() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        CommandRelocationDropReporter reporter = new CommandRelocationDropReporter(
                null, (level, message) -> diagnostics.add(new Diagnostic(level, message)));
        reporter.setListener((npcUuid, ownerUuid, source, home, destination, queued, dropped, retries) -> false);

        reporter.reportWorldRemoval(candidate());

        assertEquals(Level.WARNING, diagnostics.get(0).level());
        assertTrue(diagnostics.get(0).message().contains("lostTransitionSubmitted=false"));
    }

    private CommandRelocationNpcTracker.WorldRemovalCandidate candidate() {
        return new CommandRelocationNpcTracker.WorldRemovalCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Vector3d(1.0, 2.0, 3.0),
                "instance-Forgotten_Temple-test",
                500L
        );
    }

    private record Diagnostic(Level level, String message) {
    }
}
