package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationCounts;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationLimitScope;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationScanSessionRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationReconciliationServiceIntegrationTest {
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000802");

    @TempDir
    Path tempDir;

    @Test
    void mixedEvidenceBuildsCanonicalDatabaseAndReadyIndexes() throws Exception {
        UUID liveNpc = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID deadNpc = UUID.fromString("00000000-0000-0000-0000-000000000812");
        UUID capturedNpc = UUID.fromString("00000000-0000-0000-0000-000000000813");
        UUID coopNpc = UUID.fromString("00000000-0000-0000-0000-000000000814");
        CompanionPopulationReconciliationCatalog catalog = sealedCatalog(
                List.of(profile("profile-live", liveNpc, OWNER_A, "alpha")),
                List.of(
                        physical("world-live", liveNpc, OWNER_A, "alpha", 2, 3),
                        deadPhysical("world-dead", deadNpc, OWNER_A, "beta", 4, 5)
                ),
                List.of(captured("player-captured", capturedNpc, OWNER_B, "gamma")),
                List.of(),
                List.of(coop("custom-coop", coopNpc, OWNER_B, "delta"))
        );

        try (Harness harness = Harness.open(tempDir.resolve("mixed"), catalog)) {
            CompanionPopulationReconciliationService.Result result = harness.reconcile(1);

            assertEquals(CompanionPopulationReconciliationService.Status.READY, result.status());
            assertEquals(4, result.profileCount());
            assertEquals(1, result.duplicateObservations());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.SEALED,
                    harness.projections().snapshot().state()
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.READY,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );

            Map<UUID, CompanionPopulationStateRecord> states = harness.statesByNpc();
            assertEquals(4, states.size());
            assertState(states.get(liveNpc), OWNER_A, "alpha", CompanionLifecycleState.UNLOADED,
                    "alpha", 2, 3);
            assertState(states.get(deadNpc), OWNER_A, "beta", CompanionLifecycleState.DEAD_REVIVABLE,
                    "beta", 4, 5);
            assertState(states.get(capturedNpc), OWNER_B, "gamma", CompanionLifecycleState.CAPTURED,
                    null, null, null);
            assertState(states.get(coopNpc), OWNER_B, "delta", CompanionLifecycleState.COOP,
                    null, null, null);

            Projection projection = harness.bootstrap();
            assertEquals(OwnerPopulationReadiness.READY, projection.result().globalReadiness());
            assertEquals(OwnerPopulationReadiness.READY, projection.result().perWorldReadiness());
            assertEquals(ClaimOccupancyReadiness.READY, projection.claimIndex().readiness());
            assertEquals(2L, projection.ownerIndex().counts(OWNER_A, "alpha").globalCommitted());
            assertEquals(1L, projection.ownerIndex().counts(OWNER_A, "alpha").worldCommitted());
            assertEquals(1L, projection.ownerIndex().counts(OWNER_A, "beta").worldCommitted());
            assertEquals(2L, projection.ownerIndex().counts(OWNER_B, "gamma").globalCommitted());
            assertEquals(1L, projection.ownerIndex().counts(OWNER_B, "delta").worldCommitted());
            assertEquals(1, projection.claimIndex().snapshot().occupiedProfileCount());
            assertEquals(1, projection.claimIndex().snapshot().profilesByChunk().get(
                    new ClaimChunkCoordinate("alpha", 2, 3)
            ).size());
            assertFalse(projection.claimIndex().snapshot().profilesByChunk().containsKey(
                    new ClaimChunkCoordinate("beta", 4, 5)
            ));
            assertEquals(4, projection.identityResolver().aliasCount());
            assertTrue(projection.identityResolver().resolveProfileId(liveNpc).isPresent());
        }
    }

    @Test
    void unknownWorldKeepsOnlyPositivePerWorldAdmissionsClosed() throws Exception {
        UUID unknownNpc = UUID.fromString("00000000-0000-0000-0000-000000000821");
        CompanionPopulationReconciliationCatalog catalog = sealedCatalog(
                List.of(),
                List.of(),
                List.of(captured("unknown-world", unknownNpc, OWNER_A, null)),
                List.of(),
                List.of()
        );

        try (Harness harness = Harness.open(tempDir.resolve("unknown-world"), catalog)) {
            CompanionPopulationReconciliationService.Result staged =
                    harness.reconcileBeforeFinalReload(2);
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );

            CompanionPopulationReconciliationService.Result reconciliation = harness.service()
                    .completePartialAfterCanonicalReloadAsync(staged)
                    .get(10L, TimeUnit.SECONDS);
            assertEquals(
                    CompanionPopulationReconciliationService.Status.RECONCILING,
                    reconciliation.status()
            );
            assertEquals("owned-profiles-have-unknown-world", reconciliation.reason());
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.READY
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.ACTIVE,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );

            Projection projection = harness.bootstrap();
            OwnerPopulationIndex index = projection.ownerIndex();
            assertEquals(OwnerPopulationReadiness.READY,
                    index.readiness(OwnerPopulationLimitScope.GLOBAL));
            assertEquals(OwnerPopulationReadiness.RECONCILING,
                    index.readiness(OwnerPopulationLimitScope.PER_WORLD));
            assertEquals(ClaimOccupancyReadiness.READY, projection.claimIndex().readiness());

            CompanionPopulationStateRecord state = harness.statesByNpc().get(unknownNpc);
            assertNull(state.ownershipWorldName());
            OwnerPopulationEntry entry = index.entry(state.profileId()).orElseThrow();
            assertEquals(OWNER_A, entry.ownerId());
            assertNull(entry.ownershipWorldName());
            OwnerPopulationCounts counts = index.counts(OWNER_A, "alpha");
            assertEquals(1L, counts.globalCommitted());
            assertEquals(0L, counts.worldCommitted());
            OwnerPopulationDecision perWorldPositive = index.reserve(newProfile(
                    "per-world-positive", OwnerPopulationLimitScope.PER_WORLD
            ));
            assertFalse(perWorldPositive.allowed());
            assertEquals("owner-population-not-ready", perWorldPositive.reason());

            OwnerPopulationDecision globalPositive = index.reserve(newProfile(
                    "global-positive", OwnerPopulationLimitScope.GLOBAL
            ));
            assertTrue(globalPositive.allowed());
            assertTrue(index.cancel(globalPositive.reservation()));

            OwnerPopulationDecision release = index.reserve(new OwnerPopulationTransitionRequest(
                    state.profileId(),
                    state.revision(),
                    OWNER_A,
                    null,
                    null,
                    null,
                    CompanionLifecycleState.RELEASED,
                    OwnerPopulationOperation.OWNER_CLEAR,
                    OwnerPopulationLimitScope.PER_WORLD,
                    5,
                    false
            ));
            assertTrue(release.allowed());
            assertFalse(release.positiveDelta());
            assertTrue(index.cancel(release.reservation()));
        }
    }

    @Test
    void fullReadinessRemainsStagedUntilTheFinalFence() throws Exception {
        CompanionPopulationReconciliationCatalog catalog = sealedCatalog(
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        try (Harness harness = Harness.open(tempDir.resolve("staged-ready"), catalog)) {
            CompanionPopulationReconciliationService.Result staged =
                    harness.reconcileBeforeFinalReload(1);

            assertEquals(CompanionPopulationReconciliationService.Status.READY, staged.status());
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );

            CompanionPopulationReconciliationService.Result ready = harness.service()
                    .completeAfterCanonicalReloadAsync(staged)
                    .get(10L, TimeUnit.SECONDS);

            assertEquals(CompanionPopulationReconciliationService.Status.READY, ready.status());
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.READY
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.READY
            );
        }
    }

    @Test
    void loadedIdentityMovementDuringSavedScanCannotPublishAuthoritativeAbsence() throws Exception {
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.markInitializationComplete();
        CompanionPopulationEvidenceSource movingWorldSource = new CompanionPopulationEvidenceSource() {
            private final Descriptor descriptor = new Descriptor(
                    "moving-world",
                    CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                    "alpha",
                    "generation-moving-world",
                    1L
            );

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public CompletableFuture<Batch> scan(long offset, int maxUnits) {
                identities.recordAdded(
                        UUID.fromString("00000000-0000-0000-0000-000000000899"),
                        new LoadedNpcIdentityIndex.Location("alpha", "store-alpha")
                );
                return CompletableFuture.completedFuture(new Batch(
                        List.of(), 1L, 1L, true
                ));
            }
        };
        CompanionPopulationReconciliationCatalog catalog = new CompanionPopulationReconciliationCatalog(
                List.of(
                        source("profiles", CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE,
                                List.of()),
                        movingWorldSource,
                        source("players", CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                                List.of()),
                        source("base", CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                                List.of())
                ),
                true,
                true,
                true,
                true,
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(source(
                                "custom",
                                CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS,
                                List.of()
                        )),
                        true,
                        "all custom containers",
                        "custom-generation"
                )
        );

        try (Harness harness = Harness.open(
                tempDir.resolve("loaded-movement"), catalog, identities
        )) {
            CompanionPopulationReconciliationService.Result result = harness.reconcile(1);

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
            assertEquals("reconciliation-loaded-identity-mutated-during-scan", result.reason());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    harness.projections().snapshot().state()
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.ACTIVE,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );
        }
    }

    @Test
    void finalReloadDegradationLeavesEvidenceAndSessionUnsealed() throws Exception {
        CompanionPopulationReconciliationCatalog catalog = sealedCatalog(
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        try (Harness harness = Harness.open(tempDir.resolve("final-reload-degraded"), catalog)) {
            CompanionPopulationReconciliationService.Result staged =
                    harness.reconcileBeforeFinalReload(1);

            assertEquals(CompanionPopulationReconciliationService.Status.READY, staged.status());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                    harness.projections().snapshot().state()
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.ACTIVE,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.RECONCILING
            );

            CompanionPopulationReconciliationService.Result rejected =
                    harness.service().rejectAfterCanonicalReloadAsync(
                            staged,
                            "reconciliation-final-index-degraded"
                    ).get(10L, TimeUnit.SECONDS);

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, rejected.status());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    harness.projections().snapshot().state()
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.ACTIVE,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.GLOBAL_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.DEGRADED
            );
            assertCoverage(
                    harness,
                    CompanionPopulationReconciliationService.PER_WORLD_OWNER_COVERAGE_KEY,
                    CompanionPopulationCoverageRecord.State.DEGRADED
            );
        }
    }

    @Test
    void liveEvidenceMutationBeforeFinalSealLeavesSessionActive() throws Exception {
        CompanionPopulationReconciliationCatalog catalog = sealedCatalog(
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        try (Harness harness = Harness.open(tempDir.resolve("final-live-mutation"), catalog)) {
            CompanionPopulationReconciliationService.Result staged =
                    harness.reconcileBeforeFinalReload(1);
            assertEquals(CompanionPopulationReconciliationService.Status.READY, staged.status());

            harness.liveEvidenceRevision().advance();
            CompanionPopulationReconciliationService.Result rejected = harness.service()
                    .completeAfterCanonicalReloadAsync(staged)
                    .get(10L, TimeUnit.SECONDS);

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, rejected.status());
            assertEquals(
                    "reconciliation-live-evidence-mutated-during-final-reload",
                    rejected.reason()
            );
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    harness.projections().snapshot().state()
            );
            assertEquals(
                    CompanionPopulationScanSessionRepository.State.ACTIVE,
                    harness.persistence().getCompanionPopulationScanSessionRepository()
                            .loadCurrent().state()
            );
        }
    }

    private static OwnerPopulationTransitionRequest newProfile(
            String profileId,
            OwnerPopulationLimitScope scope
    ) {
        return new OwnerPopulationTransitionRequest(
                profileId,
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                OWNER_A,
                "alpha",
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.NEW_OWNERSHIP,
                scope,
                5,
                false
        );
    }

    private static void assertState(
            CompanionPopulationStateRecord state,
            UUID owner,
            String ownershipWorld,
            CompanionLifecycleState lifecycle,
            String physicalWorld,
            Integer chunkX,
            Integer chunkZ
    ) {
        assertEquals(owner, state.ownerUuid());
        assertEquals(ownershipWorld, state.ownershipWorldName());
        assertEquals(lifecycle.name(), state.lifecycleState());
        assertEquals(physicalWorld, state.physicalWorldName());
        assertEquals(chunkX, state.physicalChunkX());
        assertEquals(chunkZ, state.physicalChunkZ());
    }

    private static void assertCoverage(
            Harness harness,
            String key,
            CompanionPopulationCoverageRecord.State state
    ) throws Exception {
        CompanionPopulationCoverageRecord coverage = harness.persistence()
                .getCompanionPopulationCoverageRepository()
                .loadAll()
                .stream()
                .filter(record -> key.equals(record.coverageKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(state, coverage.state());
    }

    private static CompanionPopulationReconciliationCatalog sealedCatalog(
            List<CompanionPopulationEvidence> profiles,
            List<CompanionPopulationEvidence> worlds,
            List<CompanionPopulationEvidence> players,
            List<CompanionPopulationEvidence> baseContainers,
            List<CompanionPopulationEvidence> customContainers
    ) {
        List<CompanionPopulationEvidenceSource> core = List.of(
                source("profiles", CompanionPopulationCoverageRecord.Dimension.PROFILE_STATE, profiles),
                source("worlds", CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES, worlds),
                source("players", CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES, players),
                source("base", CompanionPopulationCoverageRecord.Dimension.BASE_CONTAINER_BLOCKS,
                        baseContainers)
        );
        CustomContainerReconciliationRegistry.Snapshot custom =
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(source(
                                "custom",
                                CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS,
                                customContainers
                        )),
                        true,
                        "all custom containers",
                        "custom-generation"
                );
        return new CompanionPopulationReconciliationCatalog(
                core, true, true, true, true, custom
        );
    }

    private static CompanionPopulationEvidenceSource source(
            String key,
            CompanionPopulationCoverageRecord.Dimension dimension,
            List<CompanionPopulationEvidence> evidence
    ) {
        List<CompanionPopulationEvidence> snapshot = List.copyOf(evidence);
        return new CompanionPopulationEvidenceSource() {
            private final Descriptor descriptor = new Descriptor(
                    key, dimension, null, "generation-" + key, snapshot.size()
            );

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public CompletableFuture<Batch> scan(long offset, int maxUnits) {
                int start = Math.toIntExact(offset);
                int end = Math.min(snapshot.size(), start + maxUnits);
                return CompletableFuture.completedFuture(new Batch(
                        snapshot.subList(start, end),
                        end,
                        end - start,
                        end == snapshot.size()
                ));
            }
        };
    }

    private static CompanionPopulationEvidence profile(
            String key, UUID npc, UUID owner, String world
    ) {
        return evidence(key, npc, owner, CompanionPopulationEvidence.Kind.PROFILE_RECORD,
                world, null, null, null);
    }

    private static CompanionPopulationEvidence physical(
            String key, UUID npc, UUID owner, String world, int chunkX, int chunkZ
    ) {
        return evidence(key, npc, owner, CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                world, world, chunkX, chunkZ);
    }

    private static CompanionPopulationEvidence deadPhysical(
            String key, UUID npc, UUID owner, String world, int chunkX, int chunkZ
    ) {
        return evidence(key, npc, owner, CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY,
                world, world, chunkX, chunkZ);
    }

    private static CompanionPopulationEvidence captured(
            String key, UUID npc, UUID owner, String world
    ) {
        return evidence(key, npc, owner, CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                world, null, null, null);
    }

    private static CompanionPopulationEvidence coop(
            String key, UUID npc, UUID owner, String world
    ) {
        return evidence(key, npc, owner, CompanionPopulationEvidence.Kind.COOP_SNAPSHOT,
                world, null, null, null);
    }

    private static CompanionPopulationEvidence evidence(
            String key,
            UUID npc,
            UUID owner,
            CompanionPopulationEvidence.Kind kind,
            String ownershipWorld,
            String physicalWorld,
            Integer chunkX,
            Integer chunkZ
    ) {
        return new CompanionPopulationEvidence(
                key, npc, owner, kind, ownershipWorld, physicalWorld, chunkX, chunkZ, "test"
        );
    }

    private record Projection(
            CompanionPopulationBootstrapService.BootstrapResult result,
            OwnerPopulationIndex ownerIndex,
            CompanionIdentityResolver identityResolver,
            ClaimOccupancyIndex claimIndex
    ) {
    }

    private record Harness(
            TameworkPersistenceRuntime persistence,
            CompanionPopulationReconciliationService service,
            LoadedNpcIdentityIndex identities,
            CompanionPersistedProjectionEvidenceRegistry projections,
            CompanionLiveEvidenceRevision liveEvidenceRevision
    ) implements AutoCloseable {
        static Harness open(
                Path path,
                CompanionPopulationReconciliationCatalog catalog
        ) throws Exception {
            LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
            identities.markInitializationComplete();
            return open(path, catalog, identities);
        }

        static Harness open(
                Path path,
                CompanionPopulationReconciliationCatalog catalog,
                LoadedNpcIdentityIndex identities
        ) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            CompanionPopulationScanSessionRepository sessions =
                    persistence.getCompanionPopulationScanSessionRepository();
            PersistenceWriteQueue.WriteOutcome<CompanionPopulationScanSessionRepository.Session> acquired =
                    sessions.acquireOrResumeAsync().completion().get(5L, TimeUnit.SECONDS);
            if (!acquired.isCommitted() || acquired.value() == null) {
                persistence.close();
                throw new IllegalStateException("Unable to acquire reconciliation scan session.");
            }
            CompanionPersistedProjectionEvidenceRegistry projections =
                    new CompanionPersistedProjectionEvidenceRegistry();
            projections.bindLoadedIdentityIndex(identities);
            CompanionLiveEvidenceRevision liveEvidenceRevision =
                    new CompanionLiveEvidenceRevision();
            projections.bindLiveEvidenceRevision(liveEvidenceRevision);
            projections.begin(acquired.value().epoch());
            CompanionPopulationReconciliationService service =
                    new CompanionPopulationReconciliationService(
                            catalog,
                            persistence.getCompanionPopulationReconciliationRepository(),
                            persistence.getCompanionPopulationRepository(),
                            persistence.getCompanionPopulationRepairRepository(),
                            sessions,
                            acquired.value().epoch(),
                            identities,
                            projections,
                            liveEvidenceRevision,
                            (descriptor, offset) -> {
                            }
                    );
            return new Harness(
                    persistence, service, identities, projections, liveEvidenceRevision
            );
        }

        CompanionPopulationReconciliationService.Result reconcile(int batchSize) throws Exception {
            CompanionPopulationReconciliationService.Result staged =
                    reconcileBeforeFinalReload(batchSize);
            return staged.status() == CompanionPopulationReconciliationService.Status.READY
                    ? service.completeAfterCanonicalReloadAsync(staged)
                            .get(10L, TimeUnit.SECONDS)
                    : staged;
        }

        CompanionPopulationReconciliationService.Result reconcileBeforeFinalReload(
                int batchSize
        ) throws Exception {
            return service.reconcileFullyAsync(batchSize).get(10L, TimeUnit.SECONDS);
        }

        Map<UUID, CompanionPopulationStateRecord> statesByNpc() throws Exception {
            return persistence.getCompanionPopulationRepository().loadAllStates().stream()
                    .collect(Collectors.toMap(
                            CompanionPopulationStateRecord::currentNpcUuid,
                            Function.identity()
                    ));
        }

        Projection bootstrap() {
            OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
            CompanionIdentityResolver identityResolver = new CompanionIdentityResolver();
            ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
            CompanionPopulationBootstrapService bootstrap = new CompanionPopulationBootstrapService(
                    persistence.getCompanionPopulationRepository(),
                    persistence.getCompanionPopulationCoverageRepository(),
                    persistence.getCompanionIdentityRepository(),
                    persistence.getHealthService(),
                    ownerIndex,
                    identityResolver,
                    claimIndex
            );
            return new Projection(bootstrap.load(), ownerIndex, identityResolver, claimIndex);
        }

        @Override
        public void close() {
            persistence.close();
        }
    }
}
