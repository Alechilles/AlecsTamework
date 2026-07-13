package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompanionRelocationAdmissionServiceTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000402");

    @TempDir
    Path tempDir;

    @Test
    void sameChunkIsZeroDeltaAndCommitsThroughTeleportAdmission() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("same-chunk"))) {
            harness.seedOwnedProfile();

            CompanionRelocationAdmissionService.Decision applying = prepareAndClaim(
                    harness.service, request(OWNER, "alpha", 2, 3)
            );

            assertEquals(CompanionRelocationAdmissionService.Status.APPLYING, applying.status());
            assertNotNull(applying.admission());
            assertEquals(CompanionRelocationAdmissionService.Status.COMMITTED,
                    harness.service.commit(applying.admission()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            assertEquals(2L, harness.runtime.index().entry("relocation-profile").orElseThrow().revision());
            assertEquals(new ClaimChunkCoordinate("alpha", 2, 3),
                    harness.runtime.claimOccupancyIndex().entry("relocation-profile")
                            .orElseThrow().physicalChunk());
        }
    }

    @Test
    void crossWorldCancellationRollsBackReservationsAndLeavesCommittedSource() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("cross-world"))) {
            harness.seedOwnedProfile();
            CompanionRelocationAdmissionService.Decision applying = prepareAndClaim(
                    harness.service, request(OWNER, "beta", 8, 9)
            );

            assertEquals(CompanionRelocationAdmissionService.Status.APPLYING, applying.status());
            assertEquals(CompanionRelocationAdmissionService.Status.CANCELED,
                    harness.service.cancel(applying.admission()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
            assertEquals("alpha", harness.runtime.index().entry("relocation-profile")
                    .orElseThrow().ownershipWorldName());
            assertEquals(new ClaimChunkCoordinate("alpha", 2, 3),
                    harness.runtime.claimOccupancyIndex().entry("relocation-profile")
                            .orElseThrow().physicalChunk());
        }
    }

    @Test
    void playerRecallWithWrongOwnerIsDeniedBeforeReservation() throws Exception {
        try (Harness harness = Harness.open(tempDir.resolve("denied"))) {
            harness.seedOwnedProfile();
            UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000499");

            CompanionRelocationAdmissionService.Decision denied = harness.service.prepare(
                    request(stranger, "beta", 8, 9)
            ).toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(CompanionRelocationAdmissionService.Status.DENIED, denied.status());
            assertEquals("relocation-owner-mismatch", denied.reason());
            assertEquals(0, harness.runtime.index().pendingReservationCount());
        }
    }

    @Test
    void missingDormantProjectionIsDistinguishedFromTemporaryStateUnavailability() throws Exception {
        OwnerPopulationIndex owners = new OwnerPopulationIndex();
        owners.reconcileCommittedEntry(new OwnerPopulationEntry(
                "relocation-profile", OWNER, "alpha", CompanionLifecycleState.UNKNOWN_DORMANT, 2L
        ));
        ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
        claims.reconcileCommittedEntry(new ClaimOccupancyEntry(
                "relocation-profile", OWNER, CompanionLifecycleState.UNKNOWN_DORMANT, null, 2L
        ));
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        identities.markDurable("relocation-profile", NPC);
        CompanionRelocationAdmissionService service = new CompanionRelocationAdmissionService(
                owners, identities, claims, new FakeAdmissionApi(), Runnable::run
        );

        CompanionRelocationAdmissionService.Decision denied = service.prepare(
                request(OWNER, "beta", 8, 9)
        ).toCompletableFuture().get(5L, TimeUnit.SECONDS);

        assertEquals(CompanionRelocationAdmissionService.Status.DENIED, denied.status());
        assertEquals("relocation-source-projection-missing", denied.reason());
    }

    @Test
    void providerOrTopologyChangeAtFinalClaimClosesTheReservedCapability() throws Exception {
        FakeAdmissionApi api = new FakeAdmissionApi();
        OwnerPopulationIndex owners = new OwnerPopulationIndex();
        owners.reconcileCommittedEntry(new OwnerPopulationEntry(
                "relocation-profile", OWNER, "alpha", CompanionLifecycleState.ACTIVE, 1L
        ));
        ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
        claims.reconcileCommittedEntry(new ClaimOccupancyEntry(
                "relocation-profile", OWNER, CompanionLifecycleState.ACTIVE,
                new ClaimChunkCoordinate("alpha", 2, 3), 1L
        ));
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        identities.markDurable("relocation-profile", NPC);
        CompanionRelocationAdmissionService service = new CompanionRelocationAdmissionService(
                owners, identities, claims, api, Runnable::run
        );

        CompanionRelocationAdmissionService.Decision reserved = service.prepare(
                request(OWNER, "beta", 8, 9)
        ).toCompletableFuture().get(5L, TimeUnit.SECONDS);
        CompanionRelocationAdmissionService.Decision invalidated =
                service.claimForApply(reserved.admission());
        service.cancel(reserved.admission()).toCompletableFuture().get(5L, TimeUnit.SECONDS);

        assertEquals(CompanionRelocationAdmissionService.Status.RESERVED, reserved.status());
        assertEquals(CompanionRelocationAdmissionService.Status.CANCELED, invalidated.status());
        assertEquals(1, api.claims.get());
        assertEquals(1, api.cancels.get());
    }

    private static CompanionRelocationAdmissionService.Request request(
            UUID owner, String world, int chunkX, int chunkZ
    ) {
        return new CompanionRelocationAdmissionService.Request(
                NPC, owner, world, chunkX, chunkZ,
                CompanionRelocationAdmissionService.ForcePolicy.ENFORCE
        );
    }

    private static CompanionRelocationAdmissionService.Decision prepareAndClaim(
            CompanionRelocationAdmissionService service,
            CompanionRelocationAdmissionService.Request request
    ) throws Exception {
        CompanionRelocationAdmissionService.Decision reserved = service.prepare(request)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);
        assertEquals(CompanionRelocationAdmissionService.Status.RESERVED, reserved.status());
        return service.claimForApply(reserved.admission());
    }

    private record Harness(TameworkPersistenceRuntime persistence,
                           OwnerPopulationRuntime runtime,
                           CompanionRelocationAdmissionService service) implements AutoCloseable {
        static Harness open(Path path) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
            return new Harness(persistence, runtime, runtime.relocationAdmissionService());
        }

        void seedOwnedProfile() throws Exception {
            PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                    new PopulationAdmissionIdentity(null, "relocation-profile", null),
                    NPC,
                    PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                    null,
                    OWNER,
                    null,
                    new PopulationAdmissionLocation("alpha", 2, 3),
                    PopulationAdmissionOperation.NEW_OWNERSHIP,
                    1,
                    PopulationAdmissionForcePolicy.ENFORCE
            );
            PopulationAdmissionDecision reserved = runtime.populationPolicyAuthority().tryAdmit(request)
                    .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                    runtime.populationPolicyAuthority().claimForApply(reserved.token()).status());
            assertEquals(PopulationAdmissionDecision.Status.COMMITTED,
                    runtime.populationPolicyAuthority().commit(reserved.token()).toCompletableFuture()
                            .get(5L, TimeUnit.SECONDS).status());
        }

        @Override
        public void close() {
            runtime.close();
            persistence.close();
        }
    }

    private static final class FakeAdmissionApi implements PopulationAdmissionApi {
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final PopulationAdmissionToken token = new PopulationAdmissionToken(
                UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE, 1L, "test:1",
                OwnerPopulationCapDecisionViewV2.Readiness.READY
        );

        @Override
        public CompletionStage<PopulationAdmissionDecision> tryAdmit(PopulationAdmissionRequest request) {
            return CompletableFuture.completedFuture(decision(
                    PopulationAdmissionDecision.Status.RESERVED, "reserved", token
            ));
        }

        @Override
        public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
                PopulationBatchAdmissionRequest request
        ) {
            return CompletableFuture.completedFuture(
                    PopulationBatchAdmissionDecision.unavailable(request.units().size(), "unused")
            );
        }

        @Override
        public PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token) {
            claims.incrementAndGet();
            return decision(PopulationAdmissionDecision.Status.CANCELED, "provider-topology-changed", null);
        }

        @Override
        public CompletionStage<PopulationAdmissionDecision> commit(PopulationAdmissionToken token) {
            return CompletableFuture.completedFuture(
                    decision(PopulationAdmissionDecision.Status.DEGRADED, "unused", null)
            );
        }

        @Override
        public CompletionStage<PopulationAdmissionDecision> cancel(PopulationAdmissionToken token) {
            cancels.incrementAndGet();
            return CompletableFuture.completedFuture(
                    decision(PopulationAdmissionDecision.Status.CANCELED, "canceled", null)
            );
        }

        @Override
        public CompletionStage<Integer> cleanupExpired() {
            return CompletableFuture.completedFuture(0);
        }

        private static PopulationAdmissionDecision decision(
                PopulationAdmissionDecision.Status status,
                String reason,
                PopulationAdmissionToken token
        ) {
            return new PopulationAdmissionDecision(
                    status, reason, token, OwnerPopulationCapDecisionViewV2.Readiness.READY, 1L, 0L
            );
        }
    }
}
