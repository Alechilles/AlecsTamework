package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationLimitScope;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationRuntimeReconcilerTest {
    @Test
    void semanticNpcChangesAdvanceLiveEvidenceButPeriodicUnchangedObservationsDoNot() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            long baseline = harness.liveEvidence.capture();

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.NO_CHANGE,
                    harness.reconciler.observePhysical(
                            npcUuid, ownerUuid, "default", 0, 0,
                            CompanionLifecycleState.ACTIVE, "periodic-unchanged"));
            assertTrue(harness.liveEvidence.isCurrent(baseline));

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED,
                    harness.reconciler.observePhysical(
                            npcUuid, ownerUuid, "default", 1, 0,
                            CompanionLifecycleState.ACTIVE, "semantic-move"));
            assertFalse(harness.liveEvidence.isCurrent(baseline));
        }
    }

    @Test
    void naturalMovementUpdatesClaimOccupancyImmediatelyEvenWhileReconciliationIsNotReady() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            harness.claimIndex.setReadiness(ClaimOccupancyReadiness.RECONCILING);

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observePhysical(
                            npcUuid,
                            ownerUuid,
                            "default",
                            7,
                            -3,
                            CompanionLifecycleState.ACTIVE,
                            "natural-movement"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED, result);
            assertEquals(new ClaimChunkCoordinate("default", 7, -3),
                    harness.claimIndex.entry(harness.profileId).orElseThrow().physicalChunk());
            assertEquals(1L, harness.writer.metrics().observations());
        }
    }

    @Test
    void unavoidableCrossWorldOverCapRelocationIsPreservedWarnedAndMetered() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid, 1)) {
            String existingProfile = "destination-resident";
            assertTrue(harness.ownerIndex.tryReconcileCommittedEntry(new OwnerPopulationEntry(
                    existingProfile,
                    ownerUuid,
                    "destination",
                    CompanionLifecycleState.ACTIVE,
                    1L
            )));
            List<String> warnings = new ArrayList<>();
            harness.reconciler.setWarningSink(warnings::add);

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observePhysical(
                            npcUuid,
                            ownerUuid,
                            "destination",
                            8,
                            9,
                            CompanionLifecycleState.ACTIVE,
                            "unavoidable-world-relocation"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED, result);
            assertEquals("destination", harness.ownerIndex.entry(harness.profileId)
                    .orElseThrow().ownershipWorldName());
            assertEquals(2L, harness.ownerIndex.counts(ownerUuid, "destination").worldCommitted());
            assertEquals(1L, harness.ownerIndex.metrics(
                    OwnerPopulationLimitScope.PER_WORLD, 1
            ).overCapBuckets());
            assertEquals(1L, harness.reconciler.unavoidablePerWorldOverCapRelocations());
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("per-world owner over-cap"));
            assertTrue(warnings.getFirst().contains("preserved the companion"));
            OwnerPopulationDecision laterAdmission = harness.ownerIndex.reserve(
                    new OwnerPopulationTransitionRequest(
                            "later-positive-admission",
                            OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                            null,
                            null,
                            ownerUuid,
                            "destination",
                            CompanionLifecycleState.ACTIVE,
                            OwnerPopulationOperation.NEW_OWNERSHIP,
                            OwnerPopulationLimitScope.PER_WORLD,
                            1,
                            false
                    )
            );
            assertFalse(laterAdmission.allowed());

            harness.reconciler.observePhysical(
                    npcUuid, ownerUuid, "default", 0, 0,
                    CompanionLifecycleState.ACTIVE, "relocation-reset"
            );
            harness.reconciler.observePhysical(
                    npcUuid, ownerUuid, "destination", 8, 9,
                    CompanionLifecycleState.ACTIVE, "relocation-repeat"
            );

            assertEquals(2L, harness.reconciler.unavoidablePerWorldOverCapRelocations());
            assertEquals(1, warnings.size(), "same owner/world warning bucket must be throttled");
        }
    }

    @Test
    void unloadRetainsPhysicalOccupancyWhileDormancyClearsOnlyClaimOccupancy() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            harness.reconciler.observePhysical(
                    npcUuid, ownerUuid, "default", 0, 0,
                    CompanionLifecycleState.UNLOADED, "unload"
            );

            assertTrue(harness.claimIndex.entry(harness.profileId).orElseThrow().occupiesClaim());
            assertEquals(1L, harness.ownerIndex.counts(ownerUuid, "default").globalCommitted());

            harness.reconciler.observeDormant(
                    npcUuid, ownerUuid, "default", CompanionLifecycleState.CAPTURED, "capture"
            );

            ClaimOccupancyEntry dormant = harness.claimIndex.entry(harness.profileId).orElseThrow();
            assertFalse(dormant.occupiesClaim());
            assertNull(dormant.physicalChunk());
            assertEquals(1L, harness.ownerIndex.counts(ownerUuid, "default").globalCommitted());
        }
    }

    @Test
    void directOwnerMutationIsAdoptedWithoutCapDenialAndWarned() {
        UUID npcUuid = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, oldOwner)) {
            List<String> warnings = new ArrayList<>();
            harness.reconciler.setWarningSink(warnings::add);

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observePhysical(
                            npcUuid, newOwner, "default", 0, 0,
                            CompanionLifecycleState.ACTIVE, "direct-owner-write"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED, result);
            assertEquals(newOwner, harness.ownerIndex.entry(harness.profileId).orElseThrow().ownerId());
            assertEquals(0L, harness.ownerIndex.counts(oldOwner, "default").globalCommitted());
            assertEquals(1L, harness.ownerIndex.counts(newOwner, "default").globalCommitted());
            assertEquals(1, warnings.size());
        }
    }

    @Test
    void directOwnerRemovalRetainsCanonicalSlotAndWarnsUntilJournaledClear() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            List<String> warnings = new ArrayList<>();
            harness.reconciler.setWarningSink(warnings::add);

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observeOwnerComponentRemoval(
                            npcUuid, ownerUuid, "default", 0, 0, "owner-component-removed"
                    );

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.REJECTED_UNJOURNALED_CLEAR,
                    result
            );
            assertEquals(ownerUuid, harness.ownerIndex.entry(harness.profileId).orElseThrow().ownerId());
            assertEquals(1L, harness.ownerIndex.counts(ownerUuid, "default").globalCommitted());
            assertTrue(harness.claimIndex.entry(harness.profileId).orElseThrow().occupiesClaim());
            assertEquals(0L, harness.writer.metrics().observations());
            assertEquals(1, warnings.size());
        }
    }

    @Test
    void reservedOwnerClearDoesNotAuthorizeAnEarlyComponentRemoval() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            OwnerPopulationEntry before = harness.ownerIndex.entry(harness.profileId).orElseThrow();
            OwnerPopulationDecision decision = harness.ownerIndex.reserve(new OwnerPopulationTransitionRequest(
                    harness.profileId,
                    before.revision(),
                    ownerUuid,
                    "default",
                    null,
                    "default",
                    CompanionLifecycleState.RELEASED,
                    OwnerPopulationOperation.OWNER_CLEAR,
                    OwnerPopulationLimitScope.GLOBAL,
                    0,
                    false
            ));
            assertTrue(decision.allowed());

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observeOwnerComponentRemoval(
                            npcUuid, ownerUuid, "default", 0, 0, "early-owner-component-removed"
                    );

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.REJECTED_UNJOURNALED_CLEAR,
                    result
            );
            assertEquals(ownerUuid, harness.ownerIndex.entry(harness.profileId).orElseThrow().ownerId());
            assertTrue(harness.ownerIndex.cancel(decision.reservation()));
        }
    }

    @Test
    void exactApplyingOwnerClearAuthorizesItsComponentRemoval() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            OwnerPopulationEntry before = harness.ownerIndex.entry(harness.profileId).orElseThrow();
            OwnerPopulationDecision decision = harness.ownerIndex.reserve(new OwnerPopulationTransitionRequest(
                    harness.profileId,
                    before.revision(),
                    ownerUuid,
                    "default",
                    null,
                    "default",
                    CompanionLifecycleState.RELEASED,
                    OwnerPopulationOperation.OWNER_CLEAR,
                    OwnerPopulationLimitScope.GLOBAL,
                    0,
                    false
            ));
            assertTrue(decision.allowed());
            assertTrue(harness.ownerIndex.claimForApply(decision.reservation()));

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observeOwnerComponentRemoval(
                            npcUuid, ownerUuid, "default", 0, 0, "applying-owner-component-removed"
                    );

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.SUPPRESSED_IN_FLIGHT,
                    result
            );
            assertTrue(harness.ownerIndex.cancel(decision.reservation()));
        }
    }

    @Test
    void warningSinkFailureCannotSuppressDurableObservationQueueing() {
        UUID npcUuid = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, oldOwner)) {
            harness.reconciler.setWarningSink(message -> {
                throw new IllegalStateException("warning sink unavailable");
            });

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observePhysical(
                            npcUuid, newOwner, "default", 3, 4,
                            CompanionLifecycleState.ACTIVE, "warning-failure"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED, result);
            assertEquals(1L, harness.writer.metrics().observations());
        }
    }

    @Test
    void applyingOwnerMutationDefersRefChangeObservationUntilCoordinatorCommit() {
        UUID npcUuid = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, oldOwner)) {
            OwnerPopulationEntry before = harness.ownerIndex.entry(harness.profileId).orElseThrow();
            ClaimOccupancyEntry claimBefore = harness.claimIndex.entry(harness.profileId).orElseThrow();
            OwnerPopulationDecision decision = harness.ownerIndex.reserve(new OwnerPopulationTransitionRequest(
                    harness.profileId,
                    before.revision(),
                    oldOwner,
                    "default",
                    newOwner,
                    "default",
                    CompanionLifecycleState.ACTIVE,
                    OwnerPopulationOperation.OWNER_TRANSFER,
                    OwnerPopulationLimitScope.GLOBAL,
                    0,
                    false
            ));
            assertTrue(decision.allowed());
            assertTrue(harness.ownerIndex.claimForApply(decision.reservation()));

            CompanionPopulationRuntimeReconciler.ObservationOutcome observed =
                    harness.reconciler.observePhysical(
                            npcUuid, newOwner, "default", 0, 0,
                            CompanionLifecycleState.ACTIVE, "owner-component-set"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.SUPPRESSED_IN_FLIGHT, observed);
            assertEquals(before, harness.ownerIndex.entry(harness.profileId).orElseThrow());
            assertEquals(claimBefore, harness.claimIndex.entry(harness.profileId).orElseThrow());
            assertEquals(1L, harness.writer.metrics().observations());
            assertTrue(harness.ownerIndex.commit(decision.reservation()));
            assertEquals(newOwner, harness.ownerIndex.entry(harness.profileId).orElseThrow().ownerId());
        }
    }

    @Test
    void terminalObservationFailureLogsContextOnceAndKeepsItsOriginalReason() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            List<String> warnings = new ArrayList<>();
            harness.reconciler.setWarningSink(warnings::add);
            CompanionPopulationObservation observation = CompanionPopulationObservation.fromRuntime(
                    harness.profileId,
                    npcUuid,
                    ownerUuid,
                    "default",
                    CompanionLifecycleState.ACTIVE,
                    new ClaimChunkCoordinate("default", 2, 3),
                    4L,
                    "coop-release"
            );

            harness.reconciler.onCompleted(
                    observation,
                    new CompanionPopulationObservationPersistResult(
                            CompanionPopulationObservationPersistResult.Status.IDENTITY_CONFLICT,
                            4L,
                            "npc-uuid-in-use"
                    )
            );
            harness.reconciler.onCompleted(
                    observation,
                    new CompanionPopulationObservationPersistResult(
                            CompanionPopulationObservationPersistResult.Status.FAILED,
                            4L,
                            "persistence_unhealthy"
                    )
            );

            assertEquals("population-observation-failed:npc-uuid-in-use",
                    harness.health.getState().reason());
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("profile=" + harness.profileId));
            assertTrue(warnings.get(0).contains("npc=" + npcUuid));
            assertTrue(warnings.get(0).contains("source=coop-release"));
            assertTrue(warnings.get(0).contains("status=IDENTITY_CONFLICT"));
        }
    }

    @Test
    void preparedReplacementAliasKeepsSpawnObservationOnPendingCanonicalProfile() {
        UUID previousNpcUuid = UUID.randomUUID();
        UUID plannedNpcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(previousNpcUuid, ownerUuid)) {
            assertTrue(harness.identities.retainPreparedAlias(
                    harness.profileId, plannedNpcUuid));
            OwnerPopulationEntry before = harness.ownerIndex.entry(
                    harness.profileId).orElseThrow();
            OwnerPopulationDecision decision = harness.ownerIndex.reserve(
                    new OwnerPopulationTransitionRequest(
                            harness.profileId,
                            before.revision(),
                            ownerUuid,
                            "default",
                            ownerUuid,
                            "default",
                            CompanionLifecycleState.ACTIVE,
                            OwnerPopulationOperation.RESTORE,
                            OwnerPopulationLimitScope.GLOBAL,
                            0,
                            false
                    )
            );
            assertTrue(decision.allowed());
            assertTrue(harness.ownerIndex.claimForApply(decision.reservation()));

            CompanionPopulationRuntimeReconciler.ObservationOutcome observed =
                    harness.reconciler.observePhysical(
                            plannedNpcUuid, ownerUuid, "default", 0, 0,
                            CompanionLifecycleState.ACTIVE, "managed-coop-release-spawn"
                    );

            assertEquals(
                    CompanionPopulationRuntimeReconciler.ObservationOutcome.SUPPRESSED_IN_FLIGHT,
                    observed
            );
            assertEquals(harness.profileId,
                    harness.identities.resolveProfileId(plannedNpcUuid).orElseThrow());
            assertEquals(previousNpcUuid,
                    harness.identities.currentNpcUuid(harness.profileId).orElseThrow());
            assertEquals(before, harness.ownerIndex.entry(harness.profileId).orElseThrow());
            assertTrue(harness.ownerIndex.cancel(decision.reservation()));
            assertTrue(harness.identities.releasePreparedAlias(
                    harness.profileId, plannedNpcUuid));
        }
    }

    @Test
    void removalDuringPendingMutationPersistsAndReplaysAfterCancellation() throws Exception {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            OwnerPopulationEntry before = harness.ownerIndex.entry(harness.profileId).orElseThrow();
            OwnerPopulationDecision decision = harness.ownerIndex.reserve(new OwnerPopulationTransitionRequest(
                    harness.profileId,
                    before.revision(),
                    ownerUuid,
                    "default",
                    UUID.randomUUID(),
                    "default",
                    CompanionLifecycleState.ACTIVE,
                    OwnerPopulationOperation.OWNER_TRANSFER,
                    OwnerPopulationLimitScope.GLOBAL,
                    0,
                    false
            ));
            assertTrue(decision.allowed());
            assertTrue(harness.ownerIndex.claimForApply(decision.reservation()));

            CompanionPopulationRuntimeReconciler.ObservationOutcome observed =
                    harness.reconciler.observeDormant(
                            npcUuid,
                            null,
                            "default",
                            CompanionLifecycleState.RELEASED,
                            "death-during-prepare"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.SUPPRESSED_IN_FLIGHT, observed);
            assertEquals(ownerUuid, harness.ownerIndex.entry(harness.profileId).orElseThrow().ownerId());
            assertTrue(harness.ownerIndex.cancel(decision.reservation()));
            harness.writer.flushPendingNow().get(5, TimeUnit.SECONDS);

            OwnerPopulationEntry released = harness.ownerIndex.entry(harness.profileId).orElseThrow();
            assertNull(released.ownerId());
            assertEquals(CompanionLifecycleState.RELEASED, released.lifecycleState());
            assertFalse(harness.claimIndex.entry(harness.profileId).orElseThrow().occupiesClaim());
        }
    }

    @Test
    void observationsDuringCanonicalReloadReplayAfterBootstrapReplacement() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            harness.reconciler.beginCanonicalReload();
            harness.reconciler.observePhysical(
                    npcUuid, ownerUuid, "default", 12, -8,
                    CompanionLifecycleState.ACTIVE, "reload-race"
            );
            OwnerPopulationEntry staleOwner = new OwnerPopulationEntry(
                    harness.profileId, ownerUuid, "default", CompanionLifecycleState.ACTIVE, 4L
            );
            ClaimOccupancyEntry staleClaim = new ClaimOccupancyEntry(
                    harness.profileId,
                    ownerUuid,
                    CompanionLifecycleState.ACTIVE,
                    new ClaimChunkCoordinate("default", 0, 0),
                    4L
            );
            harness.ownerIndex.replaceCommittedEntries(
                    List.of(staleOwner), OwnerPopulationReadiness.RECONCILING
            );
            harness.claimIndex.replaceCommittedEntries(
                    List.of(staleClaim), ClaimOccupancyReadiness.RECONCILING
            );

            harness.reconciler.finishCanonicalReload();

            assertEquals(new ClaimChunkCoordinate("default", 12, -8),
                    harness.claimIndex.entry(harness.profileId).orElseThrow().physicalChunk());
        }
    }

    @Test
    void rejectedObservationQueueDegradesBothAuthoritiesAfterAdoptingLiveTruth() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Harness harness = harness(npcUuid, ownerUuid)) {
            harness.writer.close();

            CompanionPopulationRuntimeReconciler.ObservationOutcome result =
                    harness.reconciler.observePhysical(
                            npcUuid, ownerUuid, "default", 5, 6,
                            CompanionLifecycleState.ACTIVE, "closed-writer"
                    );

            assertEquals(CompanionPopulationRuntimeReconciler.ObservationOutcome.UPDATED, result);
            assertEquals(
                    new ClaimChunkCoordinate("default", 5, 6),
                    harness.claimIndex.entry(harness.profileId).orElseThrow().physicalChunk()
            );
            assertEquals(
                    OwnerPopulationReadiness.DEGRADED,
                    harness.ownerIndex.readiness(OwnerPopulationLimitScope.GLOBAL)
            );
            assertEquals(ClaimOccupancyReadiness.DEGRADED, harness.claimIndex.readiness());
        }
    }

    private static Harness harness(UUID npcUuid, UUID ownerUuid) {
        return harness(npcUuid, ownerUuid, 0);
    }

    private static Harness harness(UUID npcUuid, UUID ownerUuid, int perWorldLimit) {
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        String profileId = identities.resolveOrAllocate(npcUuid, "test-profile").profileId();
        OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
        ownerIndex.replaceCommittedEntries(List.of(new OwnerPopulationEntry(
                profileId,
                ownerUuid,
                "default",
                CompanionLifecycleState.ACTIVE,
                4L
        )), OwnerPopulationReadiness.READY);
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        claimIndex.replaceCommittedEntries(List.of(new ClaimOccupancyEntry(
                profileId,
                ownerUuid,
                CompanionLifecycleState.ACTIVE,
                new ClaimChunkCoordinate("default", 0, 0),
                4L
        )), ClaimOccupancyReadiness.READY);
        CoalescedCompanionPopulationWriter writer = new CoalescedCompanionPopulationWriter(
                observation -> CompletableFuture.completedFuture(
                        new CompanionPopulationObservationPersistResult(
                                CompanionPopulationObservationPersistResult.Status.IDEMPOTENT,
                                observation.expectedRevision(),
                                null
                        )
                ),
                (observation, result) -> { },
                Executors.newSingleThreadScheduledExecutor(),
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MINUTES.toMillis(1)
        );
        CompanionPopulationObservationPolicy observationPolicy =
                new CompanionPopulationObservationPolicy(
                        ownerIndex,
                        () -> new CompanionPopulationObservationPolicy.PerWorldLimit(
                                true, perWorldLimit
                        ),
                        System::currentTimeMillis
                );
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        PersistenceHealthService health = new PersistenceHealthService();
        CompanionPopulationRuntimeReconciler reconciler = new CompanionPopulationRuntimeReconciler(
                ownerIndex,
                claimIndex,
                identities,
                writer,
                health,
                observationPolicy,
                liveEvidence
        );
        writer.setListener(reconciler);
        return new Harness(
                profileId, identities, ownerIndex, claimIndex, writer, reconciler, liveEvidence,
                health);
    }

    private record Harness(String profileId,
                           CompanionIdentityResolver identities,
                           OwnerPopulationIndex ownerIndex,
                           ClaimOccupancyIndex claimIndex,
                           CoalescedCompanionPopulationWriter writer,
                           CompanionPopulationRuntimeReconciler reconciler,
                           CompanionLiveEvidenceRevision liveEvidence,
                           PersistenceHealthService health) implements AutoCloseable {
        @Override
        public void close() {
            writer.close();
        }
    }
}
