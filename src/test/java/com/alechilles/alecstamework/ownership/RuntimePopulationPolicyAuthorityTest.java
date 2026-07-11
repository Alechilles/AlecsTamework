package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionMode;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePopulationPolicyAuthorityTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final PopulationAdmissionLocation DESTINATION =
            new PopulationAdmissionLocation("alpha", 2, 3);

    @TempDir
    Path tempDir;

    @Test
    void singleAdmissionIsAsyncIdempotentOpaqueAndCompletionAware() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("single"))) {
            PopulationAdmissionRequest request = ownedRequest(
                    "single-profile",
                    "single-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000201")
            );

            CompletionStage<PopulationAdmissionDecision> first = harness.authority.tryAdmit(request);
            CompletionStage<PopulationAdmissionDecision> retry = harness.authority.tryAdmit(request);
            assertSame(first, retry);
            PopulationAdmissionDecision reserved = first.toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.RESERVED, reserved.status());
            assertNotNull(reserved.token());
            assertEquals(1, harness.runtime.index().pendingReservationCount());
            PopulationAdmissionToken forged = new PopulationAdmissionToken(
                    reserved.token().operationId(),
                    UUID.randomUUID(),
                    reserved.token().expiresAtMonotonicNanos(),
                    reserved.token().settingsRevision(),
                    reserved.token().providerGenerationToken(),
                    reserved.token().readiness()
            );
            assertEquals(
                    PopulationAdmissionDecision.Status.UNAVAILABLE,
                    harness.authority.claimForApply(forged).status()
            );

            PopulationAdmissionDecision applying = harness.authority.claimForApply(reserved.token());
            assertEquals(PopulationAdmissionDecision.Status.APPLYING, applying.status());
            PopulationAdmissionDecision committed = harness.authority.commit(reserved.token())
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.COMMITTED, committed.status());
            assertEquals(committed, harness.authority.commit(reserved.token())
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS));
            assertEquals(OWNER, harness.runtime.index().entry("single-profile").orElseThrow().ownerId());
            assertEquals(
                    "single-profile",
                    harness.runtime.identityResolver().resolveProfileId(request.currentNpcUuid()).orElseThrow()
            );
            assertEquals(1L, harness.authority.populationDiagnostics().ownerReservations().committed());
        }
    }

    @Test
    void explicitBatchReturnsOneIndependentlyConsumableTokenPerChild() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("batch"))) {
            PopulationAdmissionRequest first = unownedBreedingRequest(
                    "batch-child-a", "batch-child-attempt-a",
                    UUID.fromString("00000000-0000-0000-0000-000000000211")
            );
            PopulationAdmissionRequest second = unownedBreedingRequest(
                    "batch-child-b", "batch-child-attempt-b",
                    UUID.fromString("00000000-0000-0000-0000-000000000212")
            );
            PopulationBatchAdmissionRequest request = new PopulationBatchAdmissionRequest(
                    "batch-attempt",
                    List.of(first, second),
                    PopulationBatchAdmissionMode.EXACT
            );

            CompletionStage<PopulationBatchAdmissionDecision> stage = harness.authority.tryAdmitBatch(request);
            assertSame(stage, harness.authority.tryAdmitBatch(request));
            PopulationBatchAdmissionDecision batch = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationBatchAdmissionDecision.Status.RESERVED_EXACT, batch.status());
            assertEquals(2, batch.admittedUnits());
            assertEquals(2, batch.unitDecisions().size());
            assertFalse(batch.unitDecisions().getFirst().token().equals(
                    batch.unitDecisions().getLast().token()
            ));
            for (PopulationAdmissionDecision unit : batch.unitDecisions()) {
                assertEquals(
                        PopulationAdmissionDecision.Status.APPLYING,
                        harness.authority.claimForApply(unit.token()).status()
                );
                assertEquals(
                        PopulationAdmissionDecision.Status.COMMITTED,
                        harness.authority.commit(unit.token()).toCompletableFuture()
                                .get(5L, TimeUnit.SECONDS).status()
                );
            }
            assertTrue(harness.runtime.index().entry("batch-child-a").isPresent());
            assertTrue(harness.runtime.index().entry("batch-child-b").isPresent());
            assertEquals(0L, harness.runtime.index().metrics(
                    OwnerPopulationLimitScope.GLOBAL, 1
            ).committedGlobalSlots());
        }
    }

    @Test
    void missingCurrentUuidAndConflictingIdempotencyFailWithoutReservation() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("validation"))) {
            PopulationAdmissionRequest missingUuid = ownedRequest(
                    "missing-uuid", "shared-attempt", null
            );
            PopulationAdmissionDecision missing = harness.authority.tryAdmit(missingUuid)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            PopulationAdmissionRequest conflict = ownedRequest(
                    "different-profile", "shared-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000221")
            );
            PopulationAdmissionDecision conflicting = harness.authority.tryAdmit(conflict)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.DENIED, missing.status());
            assertEquals("population-admission-current-npc-required", missing.reason());
            assertEquals(PopulationAdmissionDecision.Status.DENIED, conflicting.status());
            assertEquals("population-admission-idempotency-conflict", conflicting.reason());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
        }
    }

    @Test
    void canceledCapabilityReleasesAuthorityAllocatedProvisionalIdentity() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("provisional-cancel"))) {
            UUID npcUuid = UUID.fromString("00000000-0000-0000-0000-000000000225");
            PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                    new PopulationAdmissionIdentity(null, null, "allocated-provisional-attempt"),
                    npcUuid,
                    PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                    null,
                    OWNER,
                    null,
                    DESTINATION,
                    PopulationAdmissionOperation.NEW_OWNERSHIP,
                    1,
                    PopulationAdmissionForcePolicy.ENFORCE
            );

            PopulationAdmissionDecision reserved = harness.authority.tryAdmit(request)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.RESERVED, reserved.status());
            assertTrue(harness.runtime.identityResolver().resolveProfileId(npcUuid).isPresent());

            PopulationAdmissionDecision canceled = harness.authority.cancel(reserved.token())
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.CANCELED, canceled.status());
            assertTrue(harness.runtime.identityResolver().resolveProfileId(npcUuid).isEmpty());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
        }
    }

    /** Regression: a conflicting retry cannot steal or release the first request's allocated identity. */
    @Test
    void allocatedProvisionalIdentityBelongsToOriginalIdempotentPreparation() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("provisional-idempotency-owner"))) {
            UUID npcUuid = UUID.fromString("00000000-0000-0000-0000-000000000226");
            PopulationAdmissionRequest request = authorityAllocatedRequest(
                    "owned-provisional-attempt",
                    npcUuid,
                    OWNER
            );

            CompletionStage<PopulationAdmissionDecision> first = harness.authority.tryAdmit(request);
            assertSame(first, harness.authority.tryAdmit(request));
            PopulationAdmissionDecision reserved = first.toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS);
            String profileId = harness.runtime.identityResolver()
                    .resolveProfileId(npcUuid)
                    .orElseThrow();

            UUID competingNpc = UUID.fromString("00000000-0000-0000-0000-000000000227");
            PopulationAdmissionDecision conflict = harness.authority.tryAdmit(
                    authorityAllocatedRequest(
                            "owned-provisional-attempt",
                            competingNpc,
                            OTHER_OWNER
                    )
            ).toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.RESERVED, reserved.status());
            assertEquals(PopulationAdmissionDecision.Status.DENIED, conflict.status());
            assertEquals("population-admission-idempotency-conflict", conflict.reason());
            assertEquals(profileId, harness.runtime.identityResolver()
                    .resolveProfileId(npcUuid).orElseThrow());
            assertTrue(harness.runtime.identityResolver().resolveProfileId(competingNpc).isEmpty());
            assertEquals(1, harness.runtime.index().pendingReservationCount());

            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    harness.authority.cancel(reserved.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            assertTrue(harness.runtime.identityResolver().resolveProfileId(npcUuid).isEmpty());
        }
    }

    /** Regression: a public claim recheck failure must release an authority-allocated identity. */
    @Test
    void failedClaimForApplyReleasesPublicProvisionalIdentityAfterCancellation() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("provisional-failed-claim"))) {
            UUID npcUuid = UUID.fromString("00000000-0000-0000-0000-000000000228");
            PopulationAdmissionRequest request = authorityAllocatedRequest(
                    "failed-claim-provisional-attempt",
                    npcUuid,
                    OWNER
            );
            PopulationAdmissionDecision reserved = harness.authority.tryAdmit(request)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            String profileId = harness.runtime.identityResolver()
                    .resolveProfileId(npcUuid)
                    .orElseThrow();

            harness.runtime.claimOccupancyIndex().reconcileCommittedEntry(
                    new ClaimOccupancyEntry(
                            profileId,
                            OWNER,
                            CompanionLifecycleState.ACTIVE,
                            new ClaimChunkCoordinate(
                                    DESTINATION.worldName(),
                                    DESTINATION.chunkX(),
                                    DESTINATION.chunkZ()
                            ),
                            1L
                    )
            );

            PopulationAdmissionDecision rejected = harness.authority.claimForApply(reserved.token());
            PopulationAdmissionDecision closed = harness.authority.cancel(reserved.token())
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(PopulationAdmissionDecision.Status.CANCELED, rejected.status());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED, closed.status());
            assertTrue(harness.runtime.identityResolver().resolveProfileId(npcUuid).isEmpty());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
            assertEquals(0, harness.runtime.claimAdmissionService().pendingReservationCount());
        }
    }

    @Test
    void runtimeMaintenanceClosesExpiredCapabilityJournalWithoutPublicTraffic() throws Exception {
        AtomicLong cleanupClock = new AtomicLong(System.nanoTime());
        try (Harness harness = Harness.open(tempDir.resolve("scheduled-cleanup"), cleanupClock::get)) {
            PopulationAdmissionRequest request = ownedRequest(
                    "scheduled-cleanup-profile",
                    "scheduled-cleanup-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000230")
            );
            PopulationAdmissionDecision reserved = harness.authority.tryAdmit(request)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(1, harness.persistence.getCompanionPopulationRepository()
                    .loadNonterminalOperations().size());

            cleanupClock.set(reserved.token().expiresAtMonotonicNanos());
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try (PublicPopulationCapabilityMaintenance maintenance =
                         new PublicPopulationCapabilityMaintenance(harness.authority, executor)) {
                assertEquals(1, maintenance.cleanupOnce().toCompletableFuture()
                        .get(5L, TimeUnit.SECONDS));
            }

            assertTrue(harness.persistence.getCompanionPopulationRepository()
                    .loadNonterminalOperations().isEmpty());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
            assertEquals(0, harness.runtime.claimAdmissionService().pendingReservationCount());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    harness.authority.claimForApply(reserved.token()).status());
        }
    }

    @Test
    void boundedExpiredCleanupClosesJournalSoProfileCanPrepareAgain() throws Exception {
        AtomicLong cleanupClock = new AtomicLong(0L);
        try (Harness harness = Harness.open(tempDir.resolve("cleanup"), cleanupClock::get)) {
            PopulationAdmissionRequest firstRequest = ownedRequest(
                    "cleanup-profile",
                    "cleanup-attempt-a",
                    UUID.fromString("00000000-0000-0000-0000-000000000231")
            );
            PopulationAdmissionDecision first = harness.authority.tryAdmit(firstRequest)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.RESERVED, first.status());

            cleanupClock.set(Long.MAX_VALUE);
            PopulationAdmissionRequest retry = ownedRequest(
                    "cleanup-profile",
                    "cleanup-attempt-b",
                    firstRequest.currentNpcUuid()
            );
            PopulationAdmissionDecision preparedAgain = harness.authority.tryAdmit(retry)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.RESERVED, preparedAgain.status());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    harness.authority.claimForApply(first.token()).status());
            assertEquals(1, harness.runtime.index().pendingReservationCount());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    harness.authority.cancel(preparedAgain.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void idempotentSingleAndBatchRetriesNeverReturnPrunedTokens() throws Exception {
        AtomicLong cleanupClock = new AtomicLong(System.nanoTime());
        try (Harness harness = Harness.open(tempDir.resolve("idempotency-retention"), cleanupClock::get)) {
            PopulationAdmissionRequest singleRequest = ownedRequest(
                    "retained-single-profile", "retained-single-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000238")
            );
            CompletionStage<PopulationAdmissionDecision> singleStage =
                    harness.authority.tryAdmit(singleRequest);
            PopulationAdmissionDecision single = singleStage.toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                    harness.authority.claimForApply(single.token()).status());
            assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                    harness.authority.commit(single.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());

            PopulationAdmissionRequest first = unownedBreedingRequest(
                    "retained-batch-a", "retained-batch-unit-a",
                    UUID.fromString("00000000-0000-0000-0000-000000000239")
            );
            PopulationAdmissionRequest second = unownedBreedingRequest(
                    "retained-batch-b", "retained-batch-unit-b",
                    UUID.fromString("00000000-0000-0000-0000-000000000240")
            );
            PopulationBatchAdmissionRequest batchRequest = new PopulationBatchAdmissionRequest(
                    "retained-batch-attempt",
                    List.of(first, second),
                    PopulationBatchAdmissionMode.EXACT
            );
            CompletionStage<PopulationBatchAdmissionDecision> batchStage =
                    harness.authority.tryAdmitBatch(batchRequest);
            PopulationBatchAdmissionDecision batch = batchStage.toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS);
            for (PopulationAdmissionDecision unit : batch.unitDecisions()) {
                assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                        harness.authority.claimForApply(unit.token()).status());
                assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                        harness.authority.commit(unit.token()).toCompletableFuture()
                                .get(5L, TimeUnit.SECONDS).status());
            }

            long expired = batch.unitDecisions().stream()
                    .map(PopulationAdmissionDecision::token)
                    .mapToLong(PopulationAdmissionToken::expiresAtMonotonicNanos)
                    .max()
                    .orElseThrow();
            cleanupClock.set(Math.max(expired, single.token().expiresAtMonotonicNanos()));
            harness.authority.cleanupExpired().toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertSame(singleStage, harness.authority.tryAdmit(singleRequest));
            assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                    harness.authority.commit(single.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            assertSame(batchStage, harness.authority.tryAdmitBatch(batchRequest));
            for (PopulationAdmissionDecision unit : batch.unitDecisions()) {
                assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                        harness.authority.commit(unit.token()).toCompletableFuture()
                                .get(5L, TimeUnit.SECONDS).status());
            }
        }
    }

    @Test
    void cleanupPrunesExpiredTerminalResultsButPreservesApplyingCapabilities() throws Exception {
        AtomicLong cleanupClock = new AtomicLong(0L);
        try (Harness harness = Harness.open(tempDir.resolve("retention"), cleanupClock::get)) {
            PopulationAdmissionRequest terminalRequest = ownedRequest(
                    "retained-terminal-profile", "retained-terminal-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000241")
            );
            CompletionStage<PopulationAdmissionDecision> terminalStage =
                    harness.authority.tryAdmit(terminalRequest);
            PopulationAdmissionDecision terminal = terminalStage.toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                    harness.authority.claimForApply(terminal.token()).status());
            assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                    harness.authority.commit(terminal.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());

            PopulationAdmissionRequest applyingRequest = ownedRequest(
                    "retained-applying-profile", "retained-applying-attempt",
                    UUID.fromString("00000000-0000-0000-0000-000000000242")
            );
            CompletionStage<PopulationAdmissionDecision> applyingStage =
                    harness.authority.tryAdmit(applyingRequest);
            PopulationAdmissionDecision applying = applyingStage.toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                    harness.authority.claimForApply(applying.token()).status());

            cleanupClock.set(Long.MAX_VALUE);
            assertEquals(0, harness.authority.cleanupExpired().toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS));
            assertEquals(0, harness.authority.cleanupExpired().toCompletableFuture()
                    .get(5L, TimeUnit.SECONDS));

            assertSame(applyingStage, harness.authority.tryAdmit(applyingRequest));

            assertEquals(
                    OwnerPopulationReadiness.DEGRADED,
                    harness.runtime.index().readiness(OwnerPopulationLimitScope.GLOBAL)
            );
            assertEquals(
                    ClaimOccupancyReadiness.DEGRADED,
                    harness.runtime.claimOccupancyIndex().readiness()
            );
            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE,
                    harness.authority.commit(terminal.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                    harness.authority.commit(applying.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            CompletionStage<PopulationAdmissionDecision> retried =
                    harness.authority.tryAdmit(terminalRequest);
            assertFalse(retried == terminalStage);
            assertEquals(PopulationAdmissionDecision.Status.DENIED,
                    retried.toCompletableFuture().get(5L, TimeUnit.SECONDS).status());
        }
    }

    private static PopulationAdmissionRequest ownedRequest(String profileId,
                                                           String idempotencyKey,
                                                           UUID npcUuid) {
        return new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, profileId, idempotencyKey),
                npcUuid,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                OWNER,
                null,
                DESTINATION,
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
    }

    private static PopulationAdmissionRequest unownedBreedingRequest(String profileId,
                                                                     String idempotencyKey,
                                                                     UUID npcUuid) {
        return new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, profileId, idempotencyKey),
                npcUuid,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                null,
                DESTINATION,
                PopulationAdmissionOperation.BREEDING,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
    }

    private static PopulationAdmissionRequest authorityAllocatedRequest(String idempotencyKey,
                                                                        UUID npcUuid,
                                                                        UUID ownerUuid) {
        return new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, null, idempotencyKey),
                npcUuid,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                ownerUuid,
                null,
                DESTINATION,
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
    }

    private record Harness(TameworkPersistenceRuntime persistence,
                           OwnerPopulationRuntime runtime,
                           RuntimePopulationPolicyAuthority authority) implements AutoCloseable {
        static Harness open(Path path) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
            return new Harness(persistence, runtime, runtime.populationPolicyAuthority());
        }

        static Harness open(Path path, java.util.function.LongSupplier cleanupClock) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
            RuntimePopulationPolicyAuthority authority = new RuntimePopulationPolicyAuthority(
                    runtime.index(),
                    runtime.identityResolver(),
                    runtime.companionAdmissionCoordinator(),
                    runtime.companionBatchAdmissionCoordinator(),
                    runtime.claimOccupancyIndex(),
                    runtime.claimAdmissionService(),
                    runtime.claimProviderRegistry(),
                    cleanupClock,
                    new com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics()
            );
            return new Harness(persistence, runtime, authority);
        }

        @Override
        public void close() {
            runtime.close();
            persistence.close();
        }
    }
}
