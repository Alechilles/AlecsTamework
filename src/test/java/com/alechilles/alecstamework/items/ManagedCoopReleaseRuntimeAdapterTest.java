package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateRestorer.ComponentSlot;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityDecision;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.LiveIdentityRequest;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.SpawnPlacement;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Finalization;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.BLOCKED;
import static com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status.PERSISTENCE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopReleaseRuntimeAdapterTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            registry.removeStore(store);
        }
        if (registry != null) {
            registry.shutdown();
        }
    }

    @Test
    void verifiesSnapshotBuildsManagedMarkerAndDefersPresentationUntilFinalized()
            throws Exception {
        Bundle bundle = bundle("coop-a");
        RecordingGateway gateway = new RecordingGateway();
        CompletableFuture<Finalization> persistence = new CompletableFuture<>();
        List<String> events = new ArrayList<>();
        AtomicReference<LiveIdentityRequest> inspected = new AtomicReference<>();
        AtomicReference<UUID> finalizedActual = new AtomicReference<>();
        AtomicReference<ManagedCoopReleaseSpawnOrchestrator.PresentationCommand> presentation =
                new AtomicReference<>();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            events.add("finalization");
                            finalizedActual.set(actual);
                            assertEquals(500L, recordedAt);
                            return persistence;
                        },
                        command -> {
                            events.add("presentation");
                            presentation.set(command);
                        }
                );
        ManagedCoopReleaseRuntimeAdapter adapter = adapter(
                gateway,
                orchestrator,
                (request, owningStore) -> {
                    events.add("guard");
                    inspected.set(request);
                    assertEquals(store, owningStore);
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> true
        );

        CompletableFuture<Outcome> completion = adapter.release(
                bundle.claim(), bundle.resident(), new SpawnPlacement(1, 2, 3, 4, 5, 6), store);

        assertEquals(List.of("guard", "finalization"), events);
        assertFalse(completion.isDone());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(PLANNED, finalizedActual.get());
        assertNotNull(gateway.request);
        assertEquals("tamed_test", gateway.request.roleId());
        assertEquals(PLANNED, gateway.request.plannedNpcUuid());
        assertEquals(SOURCE, gateway.request.fullSnapshot().npcUuid());
        assertEquals(List.of("uuid", "legacy", "snapshot"), gateway.target.steps);

        TameworkProjectionIdentityComponent marker = gateway.installedMarker();
        assertNotNull(marker);
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                marker.getProjectionKind());
        assertEquals(bundle.claim().operationId(), marker.getOperationId());
        assertEquals(bundle.claim().profileId(), marker.getProfileId());
        assertEquals(AUTHORITY.slotKey(2), marker.getSlotKey());
        assertEquals(SOURCE, marker.getSourceNpcUuid());
        assertEquals(1L, marker.getGeneration());
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                inspected.get().projectionKind());
        assertEquals(AUTHORITY.slotKey(2), inspected.get().authoritySlotKey());

        persistence.complete(finalized(bundle.claim()));
        Outcome outcome = completion.get(3, TimeUnit.SECONDS);
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, outcome.status());
        assertTrue(outcome.presentationDispatched());
        assertEquals(List.of("guard", "finalization", "presentation"), events);
        assertEquals(PLANNED, presentation.get().actualTargetUuid());
        assertEquals(bundle.claim().residentId(), presentation.get().residentId());
        assertEquals(bundle.claim().snapshotHash(), presentation.get().snapshotHash());
    }

    @Test
    void snapshotMetadataMismatchFailsBeforeLiveLookupSpawnOrFinalization() throws Exception {
        Bundle bundle = bundle("other-coop");
        RecordingGateway gateway = new RecordingGateway();
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );
        ManagedCoopReleaseRuntimeAdapter adapter = adapter(
                gateway,
                orchestrator,
                (request, owningStore) -> {
                    guards.incrementAndGet();
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> true
        );

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("snapshot metadata"));
        assertEquals(0, guards.get());
        assertEquals(0, gateway.spawnCalls);
        assertEquals(0, finalizations.get());
    }

    @Test
    void plannedSpawnerIdentityFailureNeverCallsProjectionFinalizer() throws Exception {
        Bundle bundle = bundle("coop-a");
        RecordingGateway gateway = new RecordingGateway();
        gateway.returnMismatchedUuid = true;
        AtomicInteger finalizations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> { }
                );
        ManagedCoopReleaseRuntimeAdapter adapter = adapter(
                gateway, orchestrator,
                (request, owningStore) -> LiveIdentityDecision.clearToSpawn(),
                owningStore -> true
        );

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_FAILED,
                outcome.status());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(1, gateway.quarantineCalls);
        assertEquals(0, finalizations.get());
    }

    @Test
    void wrongThreadAndLiveConflictBothFailClosed() throws Exception {
        Bundle bundle = bundle("coop-a");
        RecordingGateway wrongThreadGateway = new RecordingGateway();
        AtomicInteger guards = new AtomicInteger();
        ManagedCoopReleaseRuntimeAdapter wrongThread = adapter(
                wrongThreadGateway,
                rejectingOrchestrator(),
                (request, owningStore) -> {
                    guards.incrementAndGet();
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> false
        );
        Outcome threadOutcome = wrongThread.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        RecordingGateway conflictGateway = new RecordingGateway();
        ManagedCoopReleaseRuntimeAdapter conflict = adapter(
                conflictGateway,
                rejectingOrchestrator(),
                (request, owningStore) -> LiveIdentityDecision.conflict(
                        "conflicting_live_profile_alias"),
                owningStore -> true
        );
        Outcome conflictOutcome = conflict.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, threadOutcome.status());
        assertTrue(threadOutcome.detail().contains("wrong_world_thread"));
        assertEquals(0, guards.get());
        assertEquals(0, wrongThreadGateway.spawnCalls);
        assertEquals(BLOCKED, conflictOutcome.status());
        assertTrue(conflictOutcome.detail().contains("conflicting_live_profile_alias"));
        assertEquals(0, conflictGateway.spawnCalls);
    }

    @Test
    void populationOnlyConstructionRejectsLegacyGatewayBeforeLiveWork() throws Exception {
        Bundle bundle = bundle("coop-a");
        AtomicInteger guards = new AtomicInteger();
        ManagedCoopReleaseRuntimeAdapter adapter = new ManagedCoopReleaseRuntimeAdapter(
                (request, owningStore) -> {
                    guards.incrementAndGet();
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> true,
                command -> { });

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, outcome.status());
        assertTrue(outcome.detail().contains("population_release_gateway_required"));
        assertEquals(0, guards.get());
    }

    @Test
    void finalizationFailureReplaysSameMarkedUuidWithoutSecondSpawnerCall()
            throws Exception {
        Bundle bundle = bundle("coop-a");
        RecordingGateway gateway = new RecordingGateway();
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        AtomicInteger presentations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> CompletableFuture.completedFuture(
                                finalizations.incrementAndGet() == 1
                                        ? Finalization.failed("write_failed")
                                        : finalized(claim)),
                        command -> presentations.incrementAndGet()
                );
        ManagedCoopReleaseRuntimeAdapter adapter = adapter(
                gateway,
                orchestrator,
                (request, owningStore) -> guards.incrementAndGet() == 1
                        ? LiveIdentityDecision.clearToSpawn()
                        : LiveIdentityDecision.matching(PLANNED),
                owningStore -> true
        );

        Outcome first = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);
        Outcome replay = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(PERSISTENCE_FAILED, first.status());
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, replay.status());
        assertFalse(replay.spawnedThisAttempt());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(2, finalizations.get());
        assertEquals(1, presentations.get());
    }

    @Test
    void committedDeployedReplayRequiresSameMarkedProjectionAndNeverSpawns()
            throws Exception {
        Bundle bundle = deployedBundle();
        RecordingGateway missingGateway = new RecordingGateway();
        ManagedCoopReleaseRuntimeAdapter missingProjection = adapter(
                missingGateway,
                rejectingOrchestrator(),
                (request, owningStore) -> LiveIdentityDecision.clearToSpawn(),
                owningStore -> true
        );
        Outcome missing = missingProjection.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        RecordingGateway matchingGateway = new RecordingGateway();
        AtomicInteger finalizations = new AtomicInteger();
        AtomicInteger presentations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator replayOrchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            finalizations.incrementAndGet();
                            return CompletableFuture.completedFuture(finalized(claim));
                        },
                        command -> presentations.incrementAndGet()
                );
        ManagedCoopReleaseRuntimeAdapter matchingProjection = adapter(
                matchingGateway,
                replayOrchestrator,
                (request, owningStore) -> LiveIdentityDecision.matching(PLANNED),
                owningStore -> true
        );
        Outcome replay = matchingProjection.release(
                bundle.claim(), bundle.resident(), placement(), store
        ).get(3, TimeUnit.SECONDS);

        assertEquals(BLOCKED, missing.status());
        assertTrue(missing.detail().contains("deployed_resident_requires_matching"));
        assertEquals(0, missingGateway.spawnCalls);
        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, replay.status());
        assertFalse(replay.spawnedThisAttempt());
        assertEquals(0, matchingGateway.spawnCalls);
        assertEquals(1, finalizations.get());
        assertEquals(1, presentations.get());
    }

    @Test
    void populationPathAdoptsExactPostSpawnMarkerAndUsesOnlyAtomicFinalizer()
            throws Exception {
        Bundle bundle = bundle("coop-a");
        PopulationBackend backend = new PopulationBackend();
        ManagedCoopReleasePopulationCoordinator populations =
                new ManagedCoopReleasePopulationCoordinator(
                        new CoopResidentStateSnapshotCodec(), backend,
                        (operationId, generation, reason, nowMs) ->
                                CompletableFuture.completedFuture(
                                        new MutationResult(
                                                MutationStatus.APPLIED, null, null)),
                        () -> 400L);
        var prepared = populations.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 0, 0).join().prepared();
        AtomicInteger guardCalls = new AtomicInteger();
        AtomicInteger presentations = new AtomicInteger();
        ManagedCoopReleaseSpawnOrchestrator orchestrator =
                new ManagedCoopReleaseSpawnOrchestrator(
                        (claim, actual, recordedAt) -> {
                            throw new AssertionError(
                                    "legacy projection finalizer must not run");
                        },
                        command -> presentations.incrementAndGet());
        ManagedCoopReleaseProjectionSpawner populationSpawner =
                new ManagedCoopReleaseProjectionSpawner(
                        new CoopResidentStateRestorer(),
                        (request, installer) ->
                                ManagedCoopReleaseProjectionSpawner.GatewayResult.failed(
                                        ManagedCoopReleaseProjectionSpawner.Status.SPAWN_FAILED,
                                        "spawn_return_ambiguous"));
        ManagedCoopReleaseRuntimeAdapter adapter = new ManagedCoopReleaseRuntimeAdapter(
                new CoopResidentStateSnapshotCodec(),
                new PlannedNpcProjectionSpawner(),
                populationSpawner,
                orchestrator,
                (request, owningStore) -> guardCalls.incrementAndGet() == 1
                        ? LiveIdentityDecision.clearToSpawn()
                        : LiveIdentityDecision.matching(PLANNED),
                owningStore -> true,
                () -> 500L);

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store,
                prepared, populations).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED, outcome.status());
        assertTrue(outcome.spawnedThisAttempt());
        assertEquals(2, guardCalls.get());
        assertEquals(1, backend.claims.get());
        assertEquals(1, backend.commits.get());
        assertEquals(1, presentations.get());
    }

    /**
     * Regression for the live canary where a source alias loaded while the replacement failed
     * before Store.addEntity. That stale alias must not turn a proven pre-add failure into global
     * persistence degradation.
     */
    @Test
    void preAddFailureIsDefiniteWithoutASecondLiveIdentityProbe() throws Exception {
        Bundle bundle = bundle("coop-a");
        PopulationBackend backend = new PopulationBackend();
        ManagedCoopReleasePopulationCoordinator populations =
                new ManagedCoopReleasePopulationCoordinator(
                        new CoopResidentStateSnapshotCodec(), backend,
                        (operationId, generation, reason, nowMs) ->
                                CompletableFuture.completedFuture(
                                        new MutationResult(
                                                MutationStatus.APPLIED, null, null)),
                        () -> 400L);
        var prepared = populations.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 0, 0).join().prepared();
        AtomicInteger guardCalls = new AtomicInteger();
        ManagedCoopReleaseProjectionSpawner populationSpawner =
                new ManagedCoopReleaseProjectionSpawner(
                        new CoopResidentStateRestorer(),
                        (request, installer) ->
                                ManagedCoopReleaseProjectionSpawner.GatewayResult.failed(
                                        ManagedCoopReleaseProjectionSpawner.Status.PRE_ADD_FAILED,
                                        "managed_release_pre_add_failed:traits"));
        ManagedCoopReleaseRuntimeAdapter adapter = new ManagedCoopReleaseRuntimeAdapter(
                new CoopResidentStateSnapshotCodec(),
                new PlannedNpcProjectionSpawner(),
                populationSpawner,
                rejectingOrchestrator(),
                (request, owningStore) -> {
                    guardCalls.incrementAndGet();
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> true,
                () -> 500L);

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store,
                prepared, populations).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_FAILED,
                outcome.status());
        assertTrue(outcome.detail().contains("managed_release_pre_add_failed:traits"));
        assertEquals(1, guardCalls.get());
        assertEquals(1, backend.claims.get());
        assertEquals(0, backend.commits.get());
        assertEquals(0, backend.degraded.get());
    }

    @Test
    void definiteStoreRejectionPersistsItsExactGatewayEvidence() throws Exception {
        Bundle bundle = bundle("coop-a");
        PopulationBackend backend = new PopulationBackend();
        ManagedCoopReleasePopulationCoordinator populations =
                new ManagedCoopReleasePopulationCoordinator(
                        new CoopResidentStateSnapshotCodec(), backend,
                        (operationId, generation, reason, nowMs) ->
                                CompletableFuture.completedFuture(
                                        new MutationResult(
                                                MutationStatus.APPLIED, null, null)),
                        () -> 400L);
        var prepared = populations.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 0, 0).join().prepared();
        ManagedCoopReleaseProjectionSpawner populationSpawner =
                new ManagedCoopReleaseProjectionSpawner(
                        new CoopResidentStateRestorer(),
                        (request, installer) ->
                                ManagedCoopReleaseProjectionSpawner.GatewayResult.failed(
                                        ManagedCoopReleaseProjectionSpawner.Status.SPAWN_FAILED,
                                        "managed_release_store_rejected_with_exact_identity"));
        AtomicInteger guardCalls = new AtomicInteger();
        ManagedCoopReleaseRuntimeAdapter adapter = new ManagedCoopReleaseRuntimeAdapter(
                new CoopResidentStateSnapshotCodec(),
                new PlannedNpcProjectionSpawner(),
                populationSpawner,
                rejectingOrchestrator(),
                (request, owningStore) -> {
                    guardCalls.incrementAndGet();
                    return LiveIdentityDecision.clearToSpawn();
                },
                owningStore -> true,
                () -> 500L);

        Outcome outcome = adapter.release(
                bundle.claim(), bundle.resident(), placement(), store,
                prepared, populations).get(3, TimeUnit.SECONDS);

        assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_FAILED,
                outcome.status());
        assertTrue(outcome.detail().contains(
                "managed_release_store_rejected_with_exact_identity"));
        assertEquals(2, guardCalls.get());
        assertEquals(0, backend.degraded.get());
    }

    @Test
    void alternateMarkerEvidenceRemainsAmbiguousAcrossRestartAndNeverSpawns()
            throws Exception {
        Bundle bundle = bundle("coop-a");
        PopulationBackend backend = new PopulationBackend();
        ManagedCoopReleasePopulationCoordinator populations =
                new ManagedCoopReleasePopulationCoordinator(
                        new CoopResidentStateSnapshotCodec(), backend,
                        (operationId, generation, reason, nowMs) ->
                                CompletableFuture.completedFuture(
                                        new MutationResult(
                                                MutationStatus.APPLIED, null, null)),
                        () -> 400L);
        var prepared = populations.prepareAsync(
                bundle.claim(), bundle.resident(), "world", 0, 0).join().prepared();
        AtomicInteger spawnCalls = new AtomicInteger();
        ManagedCoopReleaseProjectionSpawner populationSpawner =
                new ManagedCoopReleaseProjectionSpawner(
                        new CoopResidentStateRestorer(),
                        (request, installer) -> {
                            spawnCalls.incrementAndGet();
                            return ManagedCoopReleaseProjectionSpawner.GatewayResult.failed(
                                    ManagedCoopReleaseProjectionSpawner.Status.SPAWN_FAILED,
                                    "must_not_spawn");
                        });

        for (int restart = 0; restart < 2; restart++) {
            ManagedCoopReleaseRuntimeAdapter adapter =
                    new ManagedCoopReleaseRuntimeAdapter(
                            new CoopResidentStateSnapshotCodec(),
                            new PlannedNpcProjectionSpawner(),
                            populationSpawner,
                            rejectingOrchestrator(),
                            (request, owningStore) -> LiveIdentityDecision.lookupFailed(
                                    "release_projection_marker_found_at_unexpected_identity"),
                            owningStore -> true,
                            () -> 500L);

            Outcome outcome = adapter.release(
                    bundle.claim(), bundle.resident(), placement(), store,
                    prepared, populations).get(3, TimeUnit.SECONDS);

            assertEquals(ManagedCoopReleaseSpawnOrchestrator.Status.SPAWN_AMBIGUOUS,
                    outcome.status());
            assertTrue(outcome.detail().contains("unexpected_identity"));
        }
        assertEquals(0, spawnCalls.get());
        assertEquals(0, backend.claims.get());
        assertEquals(0, backend.commits.get());
        assertTrue(backend.degraded.get() >= 2);
    }

    private ManagedCoopReleaseRuntimeAdapter adapter(
            RecordingGateway gateway,
            ManagedCoopReleaseSpawnOrchestrator orchestrator,
            ManagedCoopReleaseRuntimeAdapter.LiveIdentityGuard liveGuard,
            ManagedCoopReleaseRuntimeAdapter.OwningWorldThreadGuard threadGuard) {
        return new ManagedCoopReleaseRuntimeAdapter(
                new CoopResidentStateSnapshotCodec(),
                new PlannedNpcProjectionSpawner(new PlannedNpcProjectionSpawnPlanner(), gateway),
                orchestrator,
                liveGuard,
                threadGuard,
                () -> 500L
        );
    }

    private ManagedCoopReleaseSpawnOrchestrator rejectingOrchestrator() {
        return new ManagedCoopReleaseSpawnOrchestrator(
                (claim, actual, recordedAt) -> {
                    throw new AssertionError("blocked release must not finalize");
                },
                command -> {
                    throw new AssertionError("blocked release must not present");
                }
        );
    }

    private Bundle bundle(String snapshotCoopId) {
        CoopResidentStateSnapshotCodec codec = new CoopResidentStateSnapshotCodec();
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        SOURCE, snapshotCoopId, 2, "tamed_test",
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, -100L
                );
        String snapshotJson = codec.encode(snapshot);
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        ResidentRecord resident = new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 2, "profile-a", "tamed_test",
                SOURCE, SOURCE, null, snapshotJson, hash, 1, ResidentState.RELEASING,
                1L, true, -100L, 0L, -100L, -90L
        );
        SpawnReady claim = new SpawnReady(
                "managed-coop-release:test", "profile-a", "resident-a", AUTHORITY,
                "coop-a", 2, SOURCE, PLANNED, null, hash,
                0L, 1L, 1L, OperationState.SPAWN_CLAIMED, 8L, true
        );
        return new Bundle(claim, resident);
    }

    private Bundle deployedBundle() {
        Bundle releasing = bundle("coop-a");
        ResidentRecord resident = releasing.resident();
        return new Bundle(
                releasing.claim(),
                new ResidentRecord(
                        resident.residentId(), resident.authorityKey(), resident.coopId(),
                        resident.residentSlot(), resident.profileId(), resident.roleId(),
                        PLANNED, resident.sourceNpcUuid(), PLANNED, resident.snapshotJson(),
                        resident.snapshotHash(), resident.snapshotVersion(), ResidentState.DEPLOYED,
                        2L, true, resident.capturedAtMs(), 600L,
                        resident.createdAtMs(), 600L
                )
        );
    }

    private Finalization finalized(SpawnReady claim) {
        return Finalization.finalized(null);
    }

    private SpawnPlacement placement() {
        return new SpawnPlacement(1, 2, 3, 0, 0, 0);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record Bundle(SpawnReady claim, ResidentRecord resident) {
    }

    private static final class RecordingGateway
            implements PlannedNpcProjectionSpawner.SpawnGateway {
        private int spawnCalls;
        private int quarantineCalls;
        private boolean returnMismatchedUuid;
        private PlannedNpcProjectionSpawner.SpawnRequest request;
        private RecordingTarget target;

        @Override
        public PlannedNpcProjectionSpawner.GatewayResult spawn(
                PlannedNpcProjectionSpawner.SpawnRequest request,
                PlannedNpcProjectionSpawner.PreAddInstaller installer) {
            spawnCalls++;
            this.request = request;
            target = new RecordingTarget();
            CoopResidentStateRestorer.PostAddWork work = installer.install(target);
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(target.legacyNpcUuid);
            return new PlannedNpcProjectionSpawner.GatewayResult(
                    PlannedNpcProjectionSpawner.Status.SPAWNED,
                    new PlannedNpcProjectionSpawner.SpawnedProjection(
                            new Ref<>(null, 7), npc,
                            returnMismatchedUuid ? uuid(99) : target.uuidComponentValue,
                            target.legacyNpcUuid, installedMarker(), work
                    )
            );
        }

        @Override
        public void quarantine(PlannedNpcProjectionSpawner.SpawnedProjection spawned) {
            quarantineCalls++;
        }

        private TameworkProjectionIdentityComponent installedMarker() {
            return target != null
                    ? (TameworkProjectionIdentityComponent) target.components.get(
                        ComponentSlot.PROJECTION_IDENTITY)
                    : null;
        }
    }

    private static final class RecordingTarget
            implements PlannedNpcProjectionSpawner.PreAddTarget {
        private final List<String> steps = new ArrayList<>();
        private final Map<ComponentSlot, Component<EntityStore>> components =
                new EnumMap<>(ComponentSlot.class);
        private UUID uuidComponentValue;
        private UUID legacyNpcUuid;

        @Override
        public void replaceUuidComponent(UUID plannedNpcUuid) {
            steps.add("uuid");
            uuidComponentValue = plannedNpcUuid;
        }

        @Override
        public void setLegacyNpcUuid(UUID plannedNpcUuid) {
            steps.add("legacy");
            legacyNpcUuid = plannedNpcUuid;
        }

        @Override
        public CoopResidentStateRestorer.PostAddWork restoreFullState(
                CoopResidentStateRestorer restorer,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                TameworkProjectionIdentityComponent projectionMarker) {
            steps.add("snapshot");
            return restorer.restore(components::put, snapshot, projectionMarker);
        }
    }

    private static final class PopulationBackend
            implements ManagedCoopReleasePopulationCoordinator.AdmissionBackend {
        private final Object handle = new Object();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger degraded = new AtomicInteger();

        @Override
        public CompletableFuture<ManagedCoopReleasePopulationCoordinator.BackendPreparation>
                prepare(ReleaseRequest request,
                        Function<UUID, String> durableContextFactory) {
            durableContextFactory.apply(request.plannedNpcUuid());
            return CompletableFuture.completedFuture(
                    new ManagedCoopReleasePopulationCoordinator.BackendPreparation(
                            ManagedCoopReleasePopulationCoordinator.PreparationStatus.PREPARED,
                            "profile-a", request.plannedNpcUuid(), handle, "prepared"));
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
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void markReadinessDegraded(String reason) {
            degraded.incrementAndGet();
        }
    }
}
