package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationReconciliationRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void evidenceAndCursorAdvanceAtomicallyAndResumeAtTheDurableOffset() throws Exception {
        try (Harness harness = harness("cursor.sqlite")) {
            CompanionPopulationEvidenceSource.Descriptor descriptor = descriptor("generation-a", 2);
            CompanionPopulationEvidence first = captured("first", UUID.randomUUID(), UUID.randomUUID());

            CompanionPopulationReconciliationRepository.StageResult staged = harness.repository.stageAsync(
                    descriptor,
                    new CompanionPopulationEvidenceSource.Batch(List.of(first), 1, 1, false),
                    0
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(staged.committed());
            assertEquals(1, harness.repository.resumePoint(descriptor).offset());
            assertFalse(harness.repository.resumePoint(descriptor).complete());
            assertEquals(List.of(first), harness.repository.loadEvidence(List.of(descriptor)));

            CompanionPopulationReconciliationRepository.StageResult stale = harness.repository.stageAsync(
                    descriptor,
                    new CompanionPopulationEvidenceSource.Batch(List.of(), 2, 1, true),
                    0
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertFalse(stale.committed());
            assertEquals("reconciliation-cursor-conflict", stale.reason());
            assertEquals(1, harness.repository.resumePoint(descriptor).offset());

            CompanionPopulationReconciliationRepository.StageResult completed = harness.repository.stageAsync(
                    descriptor,
                    new CompanionPopulationEvidenceSource.Batch(List.of(), 2, 1, true),
                    1
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(completed.committed());
            assertTrue(harness.repository.resumePoint(descriptor).complete());
        }
    }

    @Test
    void changedSourceGenerationDiscardsStalePartialEvidenceBeforeRestarting() throws Exception {
        try (Harness harness = harness("generation.sqlite")) {
            CompanionPopulationEvidenceSource.Descriptor old = descriptor("old", 2);
            CompanionPopulationEvidence oldEvidence = captured("old", UUID.randomUUID(), UUID.randomUUID());
            harness.repository.stageAsync(
                    old,
                    new CompanionPopulationEvidenceSource.Batch(List.of(oldEvidence), 1, 1, false),
                    0
            ).completion().get(2, TimeUnit.SECONDS);

            CompanionPopulationEvidenceSource.Descriptor replacement = descriptor("replacement", 1);
            CompanionPopulationEvidence replacementEvidence =
                    captured("replacement", UUID.randomUUID(), UUID.randomUUID());
            harness.repository.stageAsync(
                    replacement,
                    new CompanionPopulationEvidenceSource.Batch(List.of(replacementEvidence), 1, 1, true),
                    0
            ).completion().get(2, TimeUnit.SECONDS);

            assertTrue(harness.repository.loadEvidence(List.of(old)).isEmpty());
            assertEquals(List.of(replacementEvidence), harness.repository.loadEvidence(List.of(replacement)));
            assertTrue(harness.repository.resumePoint(replacement).complete());
        }
    }

    private Harness harness(String file) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(file));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        PersistenceHealthService health = new PersistenceHealthService();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null);
        return new Harness(queue, new CompanionPopulationReconciliationRepository(connections, queue));
    }

    private static CompanionPopulationEvidenceSource.Descriptor descriptor(String generation, long total) {
        return new CompanionPopulationEvidenceSource.Descriptor(
                "player-saves:test",
                CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                "test-save",
                generation,
                total
        );
    }

    private static CompanionPopulationEvidence captured(String key, UUID npcUuid, UUID ownerUuid) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null,
                null,
                null,
                null,
                "test"
        );
    }

    private record Harness(PersistenceWriteQueue queue,
                           CompanionPopulationReconciliationRepository repository)
            implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}
