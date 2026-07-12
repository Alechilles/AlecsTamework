package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Resolution;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Status;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Adoption;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleasePopulationCoordinator.BackendPreparation;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionLiveEvidenceRevision;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionEvidence;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for lifecycle-only projections persisted outside ordinary repair rows. */
class ManagedCoopPersistedReleaseProjectionRecoveryServiceTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 32, 64, 48);
    private static final String PROFILE = "profile-a";
    private static final String OPERATION = "managed-coop-release:" + "a".repeat(64);
    private static final String COOP = "coop_chicken";
    private static final UUID SOURCE = new UUID(1L, 1L);
    private static final UUID PLANNED = new UUID(2L, 2L);
    private static final UUID ALTERNATE = new UUID(3L, 3L);
    private static final CoopResidentStateSnapshotCodec CODEC =
            new CoopResidentStateSnapshotCodec();

    @TempDir
    Path tempDir;

    @Test
    void exactLiveMarkerIsAdoptableFromTheSealedPersistedScan() {
        CompanionPersistedProjectionEvidenceRegistry registry = registry(List.of(
                marker("exact", PLANNED, PLANNED, false, "world", 2, 3)));

        Resolution resolution = resolve(registry);

        assertEquals(Status.EXACT, resolution.status());
        assertEquals("world", resolution.worldName());
        assertEquals(2, resolution.chunkX());
        assertEquals(3, resolution.chunkZ());
        assertTrue(registry.snapshot().sealed());
    }

    @Test
    void alternateComponentIdentityBlocksLifecycleRecovery() {
        CompanionPersistedProjectionEvidenceRegistry registry = registry(List.of(
                marker("alternate", ALTERNATE, ALTERNATE, false, "world", 2, 3)));

        Resolution resolution = resolve(registry);

        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals("managed_coop_persisted_projection_identity_mismatch", resolution.detail());
    }

    @Test
    void duplicateExactMarkersBlockLifecycleRecovery() {
        CompanionPersistedProjectionEvidenceRegistry registry = registry(List.of(
                marker("duplicate-a", PLANNED, PLANNED, false, "world", 2, 3),
                marker("duplicate-b", PLANNED, PLANNED, false, "world", 2, 3)));

        Resolution resolution = resolve(registry);

        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals("managed_coop_persisted_projection_evidence_duplicated", resolution.detail());
    }

    @Test
    void deadExactMarkerBlocksLifecycleRecovery() {
        CompanionPersistedProjectionEvidenceRegistry registry = registry(List.of(
                marker("dead", PLANNED, PLANNED, true, "world", 2, 3)));

        Resolution resolution = resolve(registry);

        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals("managed_coop_persisted_projection_is_dead", resolution.detail());
    }

    @Test
    void missingLegacyIdentityAndWrongWorldBothFailClosed() {
        Resolution missingLegacy = resolve(registry(List.of(
                marker("missing-legacy", PLANNED, null, false, "world", 2, 3))));
        Resolution wrongWorld = resolve(registry(List.of(
                marker("wrong-world", PLANNED, PLANNED, false, "other", 2, 3))));

        assertEquals(Status.BLOCKED, missingLegacy.status());
        assertEquals("managed_coop_persisted_projection_identity_mismatch",
                missingLegacy.detail());
        assertEquals(Status.BLOCKED, wrongWorld.status());
        assertEquals("managed_coop_persisted_projection_location_mismatch", wrongWorld.detail());
    }

    @Test
    void unsealedEvidenceNeverAuthorizesSpawnRecovery() {
        Resolution resolution = resolve(new CompanionPersistedProjectionEvidenceRegistry());

        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals("managed_coop_persisted_projection_evidence_unsealed", resolution.detail());
    }

    @Test
    void exactLoadedProjectionMarkerBlocksLifecycleReplay() {
        Fixture fixture = fixture(
                List.of(), List.of(loaded(PLANNED, PLANNED, "loaded-store")));

        Resolution resolution = resolve(fixture.registry());

        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals("managed_coop_loaded_projection_observed", resolution.detail());
    }

    /** Regression: markerless target state cannot be mistaken for safe release absence. */
    @Test
    void markerlessOrdinaryEvidenceAndConflictsBlockLifecycleReplay() {
        String reason = "managed_coop_ordinary_evidence_without_projection_marker";
        assertOrdinaryBlocked(List.of(ordinaryPhysical("physical", "world", 2, 3)), reason);
        for (CompanionPopulationEvidence.Kind kind : List.of(
                CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT,
                CompanionPopulationEvidence.Kind.LOST_SNAPSHOT,
                CompanionPopulationEvidence.Kind.COOP_SNAPSHOT,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                CompanionPopulationEvidence.Kind.PROFILE_RECORD)) {
            assertOrdinaryBlocked(List.of(ordinaryDormant("ordinary-" + kind.name(), kind)), reason);
        }
        assertOrdinaryBlocked(List.of(
                ordinaryPhysical("conflict-a", "world", 2, 3),
                ordinaryPhysical("conflict-b", "world", 8, 9)), reason);
    }

    @Test
    void markerAddAndUnloadAfterResolutionInvalidatesTheAbsenceToken() {
        Fixture fixture = fixture(List.of(), List.of());
        Resolution resolution = resolve(fixture.registry());
        assertEquals(Status.ABSENT, resolution.status());
        LoadedNpcObservation observation = loaded(PLANNED, PLANNED, "loaded-store");

        fixture.loadedIndex().recordAdded(observation);
        fixture.loadedIndex().recordRemoved(observation);

        assertFalse(fixture.registry().current(
                resolution.evidenceRevision(), resolution.loadedIdentityRevision()));
        Resolution retried = resolve(fixture.registry());
        assertEquals(Status.BLOCKED, retried.status());
        assertEquals("managed_coop_loaded_projection_absence_stale", retried.detail());
    }

    /** A stale exact projection may cancel population preparation but must retain SPAWN_CLAIMED. */
    @Test
    void projectionMutationDuringPreparationNeverClaimsCommitsOrRollsBackLifecycle()
            throws Exception {
        Fixture fixture = fixture(List.of(
                marker("exact", PLANNED, PLANNED, false, "world", 2, 3)), List.of());
        DelayedBackend backend = new DelayedBackend();
        RecordingRollback rollback = new RecordingRollback();
        ManagedCoopReleasePopulationCoordinator populations =
                new ManagedCoopReleasePopulationCoordinator(
                        CODEC, backend, rollback, () -> 30L);

        try (TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(
                tempDir.resolve("stale-projection"), null)) {
            ManagedCoopPersistedReleaseProjectionRecoveryService service =
                    new ManagedCoopPersistedReleaseProjectionRecoveryService(
                            fixture.registry(),
                            populations,
                            persistence.getCoopLifecycleOperationRepository(),
                            persistence.getManagedCoopServices().compositeIndexRefreshService(),
                            new OwnerPopulationIndex(),
                            new CompanionIdentityResolver(),
                            new ClaimOccupancyIndex(),
                            CODEC,
                            () -> 40L);
            Resolution projection = resolve(fixture.registry());

            CompletableFuture<Adoption> adoption = service.adopt(
                    operation(), spawnReady(), resident(), projection);
            assertFalse(adoption.isDone());
            fixture.loadedIndex().recordAdded(loaded(PLANNED, PLANNED, "loaded-store"));
            backend.completePreparation();

            Adoption result = adoption.get(5, TimeUnit.SECONDS);
            assertFalse(result.adopted());
            assertEquals(
                    "managed_coop_persisted_projection_evidence_changed_before_claim",
                    result.detail());
            assertEquals(1, backend.cancels.get());
            assertEquals(0, backend.claims.get());
            assertEquals(0, backend.commits.get());
            assertEquals(0, rollback.calls.get());
            assertTrue(backend.degraded.get() > 0);
        }
    }

    private static Resolution resolve(CompanionPersistedProjectionEvidenceRegistry registry) {
        return ManagedCoopPersistedReleaseProjectionRecoveryService.resolvePersisted(
                registry, CODEC, operation(), resident());
    }

    private static CompanionPersistedProjectionEvidenceRegistry registry(
            List<CompanionPopulationEvidence> evidence) {
        return fixture(evidence, List.of()).registry();
    }

    private static Fixture fixture(
            List<CompanionPopulationEvidence> evidence,
            List<LoadedNpcObservation> loadedObservations) {
        CompanionPersistedProjectionEvidenceRegistry registry =
                new CompanionPersistedProjectionEvidenceRegistry();
        LoadedNpcIdentityIndex loadedIndex = new LoadedNpcIdentityIndex();
        for (LoadedNpcObservation observation : loadedObservations) {
            loadedIndex.recordAdded(observation);
        }
        loadedIndex.markInitializationComplete();
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        registry.bindLoadedIdentityIndex(loadedIndex);
        registry.bindLiveEvidenceRevision(liveEvidence);
        registry.begin("scan-a");
        assertTrue(registry.publishSealed(
                "scan-a", new CompanionPopulationEvidenceSet(evidence),
                loadedIndex.snapshot().mutationRevision(), liveEvidence.capture()));
        return new Fixture(registry, loadedIndex);
    }

    private static LoadedNpcObservation loaded(
            UUID componentUuid, UUID legacyUuid, String storeIdentity) {
        return new LoadedNpcObservation(
                componentUuid,
                legacyUuid,
                new Location("world", storeIdentity),
                new ProjectionKey(
                        PROFILE,
                        OPERATION,
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                        AUTHORITY.slotKey(0),
                        SOURCE,
                1L));
    }

    private static void assertOrdinaryBlocked(
            List<CompanionPopulationEvidence> evidence,
            String expectedReason) {
        Resolution resolution = resolve(registry(evidence));
        assertEquals(Status.BLOCKED, resolution.status());
        assertEquals(expectedReason, resolution.detail());
    }

    private static CompanionPopulationEvidence ordinaryPhysical(
            String key,
            String world,
            int chunkX,
            int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                PLANNED,
                null,
                true,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "world-source");
    }

    private static CompanionPopulationEvidence ordinaryDormant(
            String key,
            CompanionPopulationEvidence.Kind kind) {
        return new CompanionPopulationEvidence(
                key,
                PLANNED,
                null,
                kind != CompanionPopulationEvidence.Kind.PROFILE_RECORD,
                kind,
                "world",
                null,
                null,
                null,
                "world-source");
    }

    private static CompanionPopulationEvidence marker(
            String key,
            UUID componentUuid,
            UUID legacyUuid,
            boolean dead,
            String world,
            int chunkX,
            int chunkZ) {
        String fingerprint = CompanionProjectionEvidence.fingerprint(
                PROFILE,
                OPERATION,
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                AUTHORITY.slotKey(0),
                SOURCE,
                1L);
        UUID evidenceUuid = componentUuid != null
                ? componentUuid : legacyUuid != null ? legacyUuid : PLANNED;
        return new CompanionPopulationEvidence(
                CompanionProjectionEvidence.appendToEvidenceKey(
                        key, fingerprint, componentUuid, legacyUuid, dead),
                evidenceUuid,
                null,
                true,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                world,
                world,
                chunkX,
                chunkZ,
                "world-source");
    }

    private static OperationRecord operation() {
        String snapshot = snapshot();
        return new OperationRecord(
                OPERATION,
                OperationKind.RELEASE,
                PROFILE,
                AUTHORITY,
                COOP,
                0,
                null,
                PLANNED,
                null,
                OperationState.SPAWN_CLAIMED,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                0L,
                1L,
                0,
                true,
                10L,
                20L,
                0L,
                null);
    }

    private static SpawnReady spawnReady() {
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot());
        return new SpawnReady(
                OPERATION,
                PROFILE,
                ManagedCoopCaptureClaimValidator.residentId(PROFILE),
                AUTHORITY,
                COOP,
                0,
                SOURCE,
                PLANNED,
                null,
                hash,
                0L,
                1L,
                1L,
                OperationState.SPAWN_CLAIMED,
                20L,
                true);
    }

    private static ResidentRecord resident() {
        String snapshot = snapshot();
        return new ResidentRecord(
                ManagedCoopCaptureClaimValidator.residentId(PROFILE),
                AUTHORITY,
                COOP,
                0,
                PROFILE,
                "mob_chicken",
                SOURCE,
                SOURCE,
                null,
                snapshot,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                1,
                ResidentState.RELEASING,
                1L,
                true,
                10L,
                0L,
                10L,
                20L);
    }

    private static String snapshot() {
        return CODEC.encode(new CoopResidentStateSnapshot(
                SOURCE, COOP, 0, "mob_chicken",
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, 10L));
    }

    private record Fixture(
            CompanionPersistedProjectionEvidenceRegistry registry,
            LoadedNpcIdentityIndex loadedIndex) {
    }

    private static final class DelayedBackend
            implements ManagedCoopReleasePopulationCoordinator.AdmissionBackend {
        private final Object handle = new Object();
        private final CompletableFuture<BackendPreparation> preparation =
                new CompletableFuture<>();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final AtomicInteger degraded = new AtomicInteger();

        @Override
        public CompletableFuture<BackendPreparation> prepare(
                ReleaseRequest request,
                Function<UUID, String> durableContextFactory) {
            durableContextFactory.apply(request.plannedNpcUuid());
            return preparation;
        }

        void completePreparation() {
            preparation.complete(new BackendPreparation(
                    ManagedCoopReleasePopulationCoordinator.PreparationStatus.PREPARED,
                    PROFILE,
                    PLANNED,
                    handle,
                    "prepared"));
        }

        @Override
        public boolean claim(Object candidate) {
            claims.incrementAndGet();
            return candidate == handle;
        }

        @Override
        public boolean writeSpawnHolder(Object candidate, Holder<EntityStore> holder) {
            return candidate == handle;
        }

        @Override
        public CompletableFuture<CompanionPopulationCommitResult> commit(Object candidate) {
            commits.incrementAndGet();
            return CompletableFuture.completedFuture(new CompanionPopulationCommitResult(
                    true, "committed", true, null));
        }

        @Override
        public CompletableFuture<Boolean> cancel(Object candidate, String reason) {
            cancels.incrementAndGet();
            return CompletableFuture.completedFuture(candidate == handle);
        }

        @Override
        public void markReadinessDegraded(String reason) {
            degraded.incrementAndGet();
        }
    }

    private static final class RecordingRollback
            implements ManagedCoopReleasePopulationCoordinator.LifecycleRollbackGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<MutationResult> failBeforeProjection(
                String operationId,
                long expectedOperationGeneration,
                String reason,
                long nowMs) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new MutationResult(
                    MutationStatus.APPLIED, null, null));
        }
    }
}
