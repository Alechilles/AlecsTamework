package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused persistence coverage for all-or-none population-group reservations. */
class UnifiedPopulationPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void groupReservationDenialWritesNothingAndCanceledEvidenceReleasesCapacity() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("group-reservation.sqlite")) {
            PopulationGroupRepository repository = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            UUID owner = UUID.randomUUID();
            PopulationGroupRepository.ReservationEvidence evidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            owner, "soul_bond", PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 0, 1, 0, 7L);

            PopulationGroupRepository.ReservationResult first = await(
                    repository.reserveOperationAsync(operation("cap-one", "profile-one", owner),
                            List.of(evidence)));
            assertEquals(PopulationGroupRepository.Status.PREPARED, first.status());
            assertEquals(new PopulationGroupRepository.Counts(0, 0, 1, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));
            PopulationGroupRepository.ReservationEvidence changedEvidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            owner, "soul_bond", PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 0, 2, 0, 7L);
            assertEquals(PopulationGroupRepository.Status.CONFLICT,
                    await(repository.reserveOperationAsync(
                            operation("cap-one", "profile-one", owner),
                            List.of(changedEvidence))).status());

            PopulationGroupRepository.ReservationResult denied = await(
                    repository.reserveOperationAsync(operation("cap-two", "profile-two", owner),
                            List.of(evidence)));
            assertEquals(PopulationGroupRepository.Status.DENIED, denied.status());
            assertNull(repository.findOperation("cap-two"));
            assertTrue(repository.loadCountEvidence("cap-two").isEmpty());

            assertEquals(PopulationGroupRepository.Status.CANCELED,
                    await(repository.advanceOperationAsync(
                            "cap-one", PopulationGroupOperationRecord.State.PREPARED,
                            PopulationGroupOperationRecord.State.CANCELED, "caller-canceled",
                            30L)).status());
            assertEquals(new PopulationGroupRepository.Counts(0, 0, 0, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));
            assertEquals(PopulationGroupRepository.Status.PREPARED,
                    await(repository.reserveOperationAsync(
                            operation("cap-two", "profile-two", owner),
                            List.of(evidence))).status());
        }
    }

    private PopulationGroupOperationRecord operation(
            String operationId, String profileId, UUID owner) {
        return new PopulationGroupOperationRecord(
                operationId, UUID.nameUUIDFromBytes(
                        operationId.getBytes(StandardCharsets.UTF_8)).toString(), profileId,
                "NEW_OWNERSHIP", PopulationGroupOperationRecord.State.PREPARED, 0L, 7L,
                null, owner, null, "miniwyvern", List.of(), List.of("soul_bond"),
                null, "ACTIVE", null, "default", null, "PREPARING",
                10L, 10L, 0L);
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
