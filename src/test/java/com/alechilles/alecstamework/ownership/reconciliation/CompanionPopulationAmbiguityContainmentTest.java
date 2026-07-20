package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationAmbiguityContainmentTest {
    @TempDir
    Path tempDir;

    @Test
    void ambiguousJournalDurablyFencesOnlyItsOperationAndProfile() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("runtime"), null)) {
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory());
            var ambiguity = new CompanionPopulationOperationRecoveryService.AmbiguousOperation(
                    "operation-a", "profile-a",
                    "operation-recovery-source-finalization-pending:spawner_item");

            assertTrue(containment.containAsync(List.of(ambiguity))
                    .get(5L, TimeUnit.SECONDS));

            var incidents = persistence.getIncidentRepository().listOpen(10);
            assertEquals(1, incidents.size());
            assertEquals(PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY,
                    incidents.getFirst().failureClass());
            assertEquals(PersistenceDisposition.SCOPED_QUARANTINE,
                    incidents.getFirst().disposition());
            assertEquals(PersistenceDomain.RECONCILIATION, incidents.getFirst().domain());
            assertEquals(PersistenceOperationPhase.RECOVERY, incidents.getFirst().phase());
            assertEquals(2, persistence.getQuarantineRepository().listActive().size());
            assertTrue(persistence.getQuarantineRegistry()
                    .find(PersistenceScopeType.OPERATION, "operation-a").isPresent());
            assertTrue(persistence.getQuarantineRegistry()
                    .find(PersistenceScopeType.PROFILE, "profile-a").isPresent());

            assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                    availability(persistence, "profile-a").status());
            assertEquals(PersistenceMutationAvailabilityStatus.ALLOW,
                    availability(persistence, "profile-b").status());

            assertTrue(containment.containAsync(List.of(ambiguity))
                    .get(5L, TimeUnit.SECONDS));
            assertEquals(1, persistence.getIncidentRepository().listOpen(10).size());
            assertEquals(2, persistence.getQuarantineRepository().listActive().size());
            assertEquals(2L,
                    persistence.getIncidentRepository().listOpen(10).getFirst().occurrenceCount());
        }
    }

    @Test
    void conflictingEvidenceDurablyFencesOnlyItsCanonicalProfile() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("evidence"), null)) {
            UUID npcUuid = UUID.randomUUID();
            UUID historicalUuid = UUID.randomUUID();
            seedCanonicalProfile(persistence, "profile-evidence", historicalUuid);
            assertTrue(persistence.getNpcProfileRepository()
                    .remapCurrentUuidAsync(historicalUuid, npcUuid));
            assertTrue(persistence.awaitWriteQueueIdle(3_000L));
            String profileId = persistence.getNpcProfileRepository().resolveProfileId(npcUuid);
            var evidenceSet = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.captured("a", npcUuid, UUID.randomUUID()),
                    CompanionPopulationEvidenceSetTest.captured("b", npcUuid, UUID.randomUUID())
            ));
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getQuarantineRegistry());

            var result = containment.containEvidenceAsync(evidenceSet.conflicts())
                    .get(5L, TimeUnit.SECONDS);

            assertTrue(result.complete());
            assertEquals(Set.of(npcUuid, historicalUuid), result.containedNpcUuids());
            assertEquals(1, result.containedProfileCount());
            assertEquals(PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION,
                    persistence.getIncidentRepository().listOpen(10).getFirst().failureClass());
            assertEquals(1, persistence.getQuarantineRepository().listActive().size());
            assertTrue(persistence.getQuarantineRegistry()
                    .find(PersistenceScopeType.PROFILE, profileId).isPresent());
            assertEquals(PersistenceMutationAvailabilityStatus.QUARANTINED,
                    availability(persistence, profileId).status());
            assertEquals(PersistenceMutationAvailabilityStatus.ALLOW,
                    availability(persistence, "unrelated-profile").status());

            assertTrue(containment.containEvidenceAsync(evidenceSet.conflicts())
                    .get(5L, TimeUnit.SECONDS).complete());
            assertEquals(1, persistence.getIncidentRepository().listOpen(10).size());
            assertEquals(1, persistence.getQuarantineRepository().listActive().size());
            assertEquals(2L,
                    persistence.getIncidentRepository().listOpen(10).getFirst().occurrenceCount());
        }
    }

    @Test
    void evidenceWithoutAnExactCanonicalIdentityCannotBeScoped() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("unknown"), null)) {
            UUID npcUuid = UUID.randomUUID();
            var evidenceSet = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.captured("a", npcUuid, UUID.randomUUID()),
                    CompanionPopulationEvidenceSetTest.captured("b", npcUuid, UUID.randomUUID())
            ));
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getQuarantineRegistry());

            var result = containment.containEvidenceAsync(evidenceSet.conflicts())
                    .get(5L, TimeUnit.SECONDS);

            assertFalse(result.complete());
            assertTrue(persistence.getIncidentRepository().listOpen(10).isEmpty());
            assertTrue(persistence.getQuarantineRepository().listActive().isEmpty());
        }
    }

    /** Protects support bundle 6d755cb8: a corrected restart scan must heal its old profile fence. */
    @Test
    void freshConflictFreeLegacyCaptureEvidenceStagesRecoveryForTheExactProfile() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("proof"), null)) {
            UUID npcUuid = UUID.randomUUID();
            UUID formerOwner = UUID.randomUUID();
            String profileId = "profile-restart-capture";
            seedCanonicalProfile(persistence, profileId, npcUuid);
            ReconciliationEvidenceRecoveryProofRegistry proofs =
                    new ReconciliationEvidenceRecoveryProofRegistry();
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getQuarantineRegistry(), proofs
            );
            var conflicting = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.captured("item", npcUuid, formerOwner),
                    CompanionPopulationEvidenceSetTest.dormant(
                            "snapshot", npcUuid, null,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT
                    )
            ));
            assertTrue(containment.containEvidenceAsync(conflicting.conflicts())
                    .get(5L, TimeUnit.SECONDS).complete());

            var corrected = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.dormant(
                            "legacy-item", npcUuid, formerOwner,
                            CompanionPopulationEvidence.Kind.CAPTURED_ITEM_LEGACY_OWNER_HINT
                    ),
                    CompanionPopulationEvidenceSetTest.dormant(
                            "snapshot", npcUuid, null,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT
                    )
            ));
            assertTrue(containment.stageEvidenceRecoveryProofs("scan-fixed", corrected)
                    .get(5L, TimeUnit.SECONDS));

            assertFalse(proofs.isSealedConflictFree(profileId));
            proofs.seal("scan-fixed");
            assertTrue(proofs.isSealedConflictFree(profileId));
        }
    }

    @Test
    void aliasWithoutCanonicalPopulationStateCannotBeScoped() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("missing-state"), null)) {
            UUID npcUuid = UUID.randomUUID();
            assertTrue(persistence.getNpcProfileRepository().upsertAsync(
                    new com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository.ProfileUpdate(
                            npcUuid, UUID.randomUUID(), null, null, null, null,
                            true, null, null, null, null
                    )));
            assertTrue(persistence.awaitWriteQueueIdle(3_000L));
            var evidenceSet = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.captured("a", npcUuid, UUID.randomUUID()),
                    CompanionPopulationEvidenceSetTest.captured("b", npcUuid, UUID.randomUUID())
            ));
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getQuarantineRegistry());

            assertFalse(containment.containEvidenceAsync(evidenceSet.conflicts())
                    .get(5L, TimeUnit.SECONDS).complete());
            assertTrue(persistence.getQuarantineRegistry().snapshot().isEmpty());
        }
    }

    @Test
    void existingDurableProfileFenceAlsoContainsEvidenceForThatProfile() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir.resolve("overlap"), null)) {
            UUID npcUuid = UUID.randomUUID();
            String profileId = "profile-overlap";
            seedCanonicalProfile(persistence, profileId, npcUuid);
            var containment = new CompanionPopulationAmbiguityContainment(
                    persistence.getIncidentReporter(), persistence.getPersistenceScopeFactory(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getQuarantineRegistry());
            assertTrue(containment.containAsync(List.of(
                    new CompanionPopulationOperationRecoveryService.AmbiguousOperation(
                            "operation-overlap", profileId, "source-finalization-pending")
            )).get(5L, TimeUnit.SECONDS));
            var evidenceSet = new CompanionPopulationEvidenceSet(List.of(
                    CompanionPopulationEvidenceSetTest.captured("a", npcUuid, UUID.randomUUID()),
                    CompanionPopulationEvidenceSetTest.captured("b", npcUuid, UUID.randomUUID())
            ));

            assertTrue(containment.containEvidenceAsync(evidenceSet.conflicts())
                    .get(5L, TimeUnit.SECONDS).complete());
            assertEquals(1, persistence.getIncidentRepository().listOpen(10).size());
            assertEquals(2, persistence.getQuarantineRepository().listActive().size());
        }
    }

    private static void seedCanonicalProfile(
            TameworkPersistenceRuntime persistence,
            String profileId,
            UUID npcUuid
    ) throws Exception {
        long now = System.currentTimeMillis();
        var baseline = new CompanionPopulationStateRecord(
                profileId, npcUuid, UUID.randomUUID(), "default", "default",
                CompanionLifecycleState.CAPTURED.name(), null, null, null,
                0L, "test", now, now
        );
        var operation = new CompanionPopulationOperationRecord(
                "seed-" + profileId, profileId, OwnerPopulationOperation.RESTORE.name(),
                CompanionPopulationOperationRecord.State.PREPARED, 0L,
                "{}", "{}", null, now, now, 0L, null
        );
        var repository = persistence.getCompanionPopulationRepository();
        assertTrue(repository.prepareAsync(new PopulationPersistenceTransition.Prepare(
                operation, baseline)).completion().get(5L, TimeUnit.SECONDS).isCommitted());
        assertTrue(repository.advanceOperationAsync(
                operation.operationId(), CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.FAILED, "test-seed-complete"
        ).completion().get(5L, TimeUnit.SECONDS).isCommitted());
    }

    private static com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision
    availability(TameworkPersistenceRuntime persistence, String profileId) {
        var scope = persistence.getPersistenceScopeFactory().profile(profileId);
        return persistence.getMutationAvailabilityService().decide(new PersistenceMutationContext(
                PersistenceDomain.CAPTURE_RELEASE,
                "release",
                List.of(scope),
                Set.of(),
                PersistenceMutationDelta.ZERO,
                null,
                null,
                true,
                false
        ));
    }
}
