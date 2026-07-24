package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers sorted canonical assignments, CAS replacement, and persisted pending count evidence. */
class PopulationGroupRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void classificationAndReservationCountsRemainReplayableAcrossJournalStages() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("groups.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "miniwyvern", "ACTIVE", "default", 4L);
            PopulationGroupRepository repository = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            PopulationGroupClassificationRecord classification = new PopulationGroupClassificationRecord(
                    profileId, "miniwyvern", List.of("soul_bond", "dragon", "dragon"),
                    9L, PopulationGroupClassificationRecord.Status.RESOLVED,
                    "config-reconcile", 1L, 1L);

            assertEquals(PopulationGroupRepository.Status.APPLIED,
                    await(repository.replaceClassificationAsync(
                            new PopulationGroupRepository.ClassificationMutation(null, classification))).status());
            assertEquals(List.of("dragon", "soul_bond"),
                    repository.findClassification(profileId).groupIds());
            assertEquals(new PopulationGroupRepository.Counts(1, 1, 0, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));

            PopulationGroupOperationRecord operation = new PopulationGroupOperationRecord(
                    "groups-op", "population-op", "provisional-profile", "NEW_OWNERSHIP",
                    PopulationGroupOperationRecord.State.PREPARED, 0L, 9L,
                    null, owner, null, "miniwyvern", List.of(), List.of("soul_bond"),
                    null, "ACTIVE", null, "default", null, "NONE",
                    2L, 2L, 0L);
            PopulationGroupCountEvidenceRecord evidence = new PopulationGroupCountEvidenceRecord(
                    "groups-op", owner, "soul_bond",
                    PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null,
                    1, 1, 0, 0, 1, 0, 2, 1, 9L,
                    PopulationGroupCountEvidenceRecord.State.RESERVED, 2L, 2L);

            assertEquals(PopulationGroupRepository.Status.PREPARED,
                    await(repository.prepareOperationAsync(operation, List.of(evidence))).status());
            assertEquals(PopulationGroupRepository.Status.IDEMPOTENT,
                    await(repository.prepareOperationAsync(operation, List.of(evidence))).status());
            PopulationGroupOperationRecord changedOwner = new PopulationGroupOperationRecord(
                    "groups-op", "population-op", "provisional-profile", "NEW_OWNERSHIP",
                    PopulationGroupOperationRecord.State.PREPARED, 0L, 9L,
                    null, UUID.randomUUID(), null, "miniwyvern", List.of(), List.of("soul_bond"),
                    null, "ACTIVE", null, "default", null, "NONE",
                    2L, 2L, 0L);
            assertEquals(PopulationGroupRepository.Status.CONFLICT,
                    await(repository.prepareOperationAsync(changedOwner, List.of(evidence))).status());
            assertEquals(new PopulationGroupRepository.Counts(1, 1, 1, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));

            await(repository.advanceOperationAsync(
                    "groups-op", PopulationGroupOperationRecord.State.PREPARED,
                    PopulationGroupOperationRecord.State.APPLYING, null, 3L));
            assertEquals(PopulationGroupCountEvidenceRecord.State.RESERVED,
                    repository.loadCountEvidence("groups-op").getFirst().state());
            await(repository.advanceOperationAsync(
                    "groups-op", PopulationGroupOperationRecord.State.APPLYING,
                    PopulationGroupOperationRecord.State.APPLIED, null, 4L));
            assertEquals(PopulationGroupCountEvidenceRecord.State.APPLIED,
                    repository.loadCountEvidence("groups-op").getFirst().state());
            assertEquals(new PopulationGroupRepository.Counts(1, 1, 0, 0),
                    repository.count(owner, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL, null));
            assertEquals(1, repository.loadRecoverableOperations().size());
        }
    }

    @Test
    void staleClassificationRevisionCannotOverwriteNewerEvidence() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("classification-cas.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon", "CAPTURED", "default", 1L);
            PopulationGroupRepository repository = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            PopulationGroupClassificationRecord first = new PopulationGroupClassificationRecord(
                    profileId, "dragon", List.of("dragon"), 2L,
                    PopulationGroupClassificationRecord.Status.RESOLVED, "first", 1L, 1L);
            await(repository.replaceClassificationAsync(
                    new PopulationGroupRepository.ClassificationMutation(null, first)));
            PopulationGroupClassificationRecord stale = new PopulationGroupClassificationRecord(
                    profileId, "dragon", List.of("other"), 1L,
                    PopulationGroupClassificationRecord.Status.RESOLVED, "stale", 1L, 2L);

            PopulationGroupRepository.ClassificationResult denied = await(
                    repository.replaceClassificationAsync(
                            new PopulationGroupRepository.ClassificationMutation(1L, stale)));
            assertEquals(PopulationGroupRepository.Status.CONFLICT, denied.status());
            assertEquals(List.of("dragon"), repository.findClassification(profileId).groupIds());
        }
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
