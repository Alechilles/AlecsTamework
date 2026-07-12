package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleasePopulationCoordinator.BackendPreparation;
import com.alechilles.alecstamework.items.ManagedCoopReleasePopulationCoordinator.Preparation;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the exact UUID/chunk population handoff on managed release. */
class ManagedCoopReleasePopulationCoordinatorTest {
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final UUID PLANNED = new UUID(0L, 2L);
    private static final UUID OWNER = new UUID(0L, 3L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @Test
    void preparesCallerPlannedUuidAndExactResolvedChunkWithAtomicV5Context() {
        Bundle bundle = bundle();
        RecordingBackend backend = new RecordingBackend();
        ManagedCoopReleasePopulationCoordinator coordinator =
                coordinator(backend, new RecordingRollback());

        Preparation preparation = coordinator.prepareAsync(
                bundle.claim(), bundle.resident(), "WORLD", -4, 7).join();

        assertTrue(preparation.preparedSuccessfully());
        assertEquals(PLANNED, preparation.prepared().plannedTargetUuid());
        assertEquals(bundle.claim().operationId(), preparation.prepared().operationId());
        assertNotNull(backend.request.get());
        assertEquals(SOURCE, backend.request.get().previousNpcUuid());
        assertEquals(PLANNED, backend.request.get().plannedNpcUuid());
        assertEquals(OWNER, backend.request.get().ownerId());
        assertEquals("Owner", backend.request.get().ownerName());
        assertEquals("world", backend.request.get().worldName());
        assertEquals(-4, backend.request.get().chunkX());
        assertEquals(7, backend.request.get().chunkZ());

        JsonObject mutation = JsonParser.parseString(backend.extension.get())
                .getAsJsonObject().getAsJsonObject("managedCoopMutation");
        assertEquals("RELEASE", mutation.get("mode").getAsString());
        assertEquals(bundle.claim().operationId(), mutation.get("operationId").getAsString());
        assertEquals(PLANNED.toString(), mutation.get("plannedTargetUuid").getAsString());
        assertEquals(PLANNED.toString(), mutation.get("actualTargetUuid").getAsString());
        assertEquals(1L, mutation.get("expectedOperationGeneration").getAsLong());
        assertEquals(-500L, mutation.get("nowMs").getAsLong());
    }

    @Test
    void commitsPopulationAsTheOnlyDurableFinalizerAndRejectsAnotherActualUuid() {
        Bundle bundle = bundle();
        RecordingBackend backend = new RecordingBackend();
        ManagedCoopReleasePopulationCoordinator coordinator =
                coordinator(backend, new RecordingRollback());
        var prepared = coordinator.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 1, 2).join().prepared();

        assertTrue(coordinator.claimForSpawn(prepared, bundle.claim()));
        var committed = coordinator.commitAsync(prepared, bundle.claim(), PLANNED).join();
        var mismatch = coordinator.commitAsync(
                prepared, bundle.claim(), new UUID(0L, 99L)).join();

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.FinalizationStatus.FINALIZED,
                committed.status());
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.FinalizationStatus.FAILED,
                mismatch.status());
        assertEquals(1, backend.claims.get());
        assertEquals(1, backend.commits.get());
        assertTrue(backend.degraded.get() > 0);
    }

    @Test
    void definitiveCancelClosesPopulationBeforeExactGenerationLifecycleRollback() {
        Bundle bundle = bundle();
        AtomicInteger sequence = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(sequence);
        RecordingRollback rollback = new RecordingRollback(sequence);
        ManagedCoopReleasePopulationCoordinator coordinator = coordinator(backend, rollback);
        var prepared = coordinator.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 1, 2).join().prepared();

        boolean cancelled = coordinator.cancelAsync(
                prepared, "spawn_definitively_absent").join();

        assertTrue(cancelled);
        assertEquals(1, backend.cancelOrder.get());
        assertEquals(2, rollback.order.get());
        assertEquals(bundle.claim().operationId(), rollback.operationId.get());
        assertEquals(1L, rollback.generation.get());
        assertEquals("spawn_definitively_absent", rollback.reason.get());
        assertEquals(-500L, rollback.nowMs.get());
    }

    @Test
    void prePreparationFailureRollsBackExactSpawnClaimWithoutPopulationCancel() {
        Bundle bundle = bundle();
        AtomicInteger sequence = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend(sequence);
        RecordingRollback rollback = new RecordingRollback(sequence);
        ManagedCoopReleasePopulationCoordinator coordinator = coordinator(backend, rollback);

        boolean rolledBack = coordinator.rollbackBeforePreparationAsync(
                bundle.claim(), "release_world_unavailable").join();

        assertTrue(rolledBack);
        assertEquals(0, backend.cancelOrder.get());
        assertEquals(1, rollback.order.get());
        assertEquals(bundle.claim().operationId(), rollback.operationId.get());
        assertEquals(1L, rollback.generation.get());
        assertEquals("release_world_unavailable", rollback.reason.get());
    }

    @Test
    void corruptSnapshotFailsBeforePopulationAdmission() {
        Bundle bundle = bundle();
        RecordingBackend backend = new RecordingBackend();
        ManagedCoopReleasePopulationCoordinator coordinator =
                coordinator(backend, new RecordingRollback());
        ResidentRecord resident = bundle.resident();
        ResidentRecord corrupt = new ResidentRecord(
                resident.residentId(), resident.authorityKey(), resident.coopId(),
                resident.residentSlot(), resident.profileId(), resident.roleId(),
                resident.residentUuid(), resident.sourceNpcUuid(), resident.deployedNpcUuid(),
                resident.snapshotJson() + " ", resident.snapshotHash(), resident.snapshotVersion(),
                resident.state(), resident.generation(), resident.active(), resident.capturedAtMs(),
                resident.releasedAtMs(), resident.createdAtMs(), resident.updatedAtMs());

        Preparation result = coordinator.prepareAsync(
                bundle.claim(), corrupt, "world", 1, 2).join();

        assertFalse(result.preparedSuccessfully());
        assertEquals(ManagedCoopReleasePopulationCoordinator.PreparationStatus.DENIED,
                result.status());
        assertEquals(0, backend.prepares.get());
    }

    private static ManagedCoopReleasePopulationCoordinator coordinator(
            RecordingBackend backend,
            RecordingRollback rollback) {
        return new ManagedCoopReleasePopulationCoordinator(
                new CoopResidentStateSnapshotCodec(), backend, rollback, () -> -500L);
    }

    private static Bundle bundle() {
        CoopResidentStateSnapshotCodec codec = new CoopResidentStateSnapshotCodec();
        var snapshot = new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                SOURCE, "coop-a", 2, "tamed_test", null,
                new TameworkOwnerComponent(OWNER, "Owner"),
                null, null, null, null, null, null, null, null, null,
                null, null, -100L);
        String json = codec.encode(snapshot);
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(json);
        ResidentRecord resident = new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 2, "profile-a", "tamed_test",
                SOURCE, SOURCE, null, json, hash, 1, ResidentState.RELEASING,
                1L, true, -100L, 0L, -100L, -90L);
        SpawnReady claim = new SpawnReady(
                "managed-coop-release:test", "profile-a", "resident-a", AUTHORITY,
                "coop-a", 2, SOURCE, PLANNED, null, hash,
                0L, 1L, 1L, OperationState.SPAWN_CLAIMED, 8L, true);
        return new Bundle(claim, resident);
    }

    private record Bundle(SpawnReady claim, ResidentRecord resident) {
    }

    private static final class RecordingBackend
            implements ManagedCoopReleasePopulationCoordinator.AdmissionBackend {
        private final Object handle = new Object();
        private final AtomicReference<ReleaseRequest> request = new AtomicReference<>();
        private final AtomicReference<String> extension = new AtomicReference<>();
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger degraded = new AtomicInteger();
        private final AtomicInteger sequence;
        private final AtomicInteger cancelOrder = new AtomicInteger();

        private RecordingBackend() {
            this(new AtomicInteger());
        }

        private RecordingBackend(AtomicInteger sequence) {
            this.sequence = sequence;
        }

        @Override
        public CompletableFuture<BackendPreparation> prepare(
                ReleaseRequest request,
                Function<UUID, String> durableContextFactory) {
            prepares.incrementAndGet();
            this.request.set(request);
            extension.set(durableContextFactory.apply(request.plannedNpcUuid()));
            return CompletableFuture.completedFuture(new BackendPreparation(
                    true, "profile-a", request.plannedNpcUuid(), handle, "prepared"));
        }

        @Override
        public boolean claim(Object handle) {
            claims.incrementAndGet();
            return this.handle == handle;
        }

        @Override
        public boolean writeSpawnHolder(Object handle, Holder<EntityStore> holder) {
            return this.handle == handle;
        }

        @Override
        public CompletableFuture<CompanionPopulationCommitResult> commit(Object handle) {
            commits.incrementAndGet();
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    true, "committed", true, null));
        }

        @Override
        public CompletableFuture<Boolean> cancel(Object handle, String reason) {
            cancelOrder.set(sequence.incrementAndGet());
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void markReadinessDegraded(String reason) {
            degraded.incrementAndGet();
        }
    }

    private static final class RecordingRollback
            implements ManagedCoopReleasePopulationCoordinator.LifecycleRollbackGateway {
        private final AtomicInteger sequence;
        private final AtomicInteger order = new AtomicInteger();
        private final AtomicReference<String> operationId = new AtomicReference<>();
        private final AtomicReference<Long> generation = new AtomicReference<>();
        private final AtomicReference<String> reason = new AtomicReference<>();
        private final AtomicReference<Long> nowMs = new AtomicReference<>();

        private RecordingRollback() {
            this(new AtomicInteger());
        }

        private RecordingRollback(AtomicInteger sequence) {
            this.sequence = sequence;
        }

        @Override
        public CompletableFuture<MutationResult> failBeforeProjection(
                String operationId,
                long expectedOperationGeneration,
                String reason,
                long nowMs) {
            order.set(sequence.incrementAndGet());
            this.operationId.set(operationId);
            generation.set(expectedOperationGeneration);
            this.reason.set(reason);
            this.nowMs.set(nowMs);
            return CompletableFuture.completedFuture(new MutationResult(
                    MutationStatus.APPLIED, null, null));
        }
    }
}
