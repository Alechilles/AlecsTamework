package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionStorePlanner;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for coherent profile/lease reads at the public API. */
class BondedCompanionApiFacadeCoherentListTest {
    private static final UUID OWNER = UUID.fromString(
            "81000000-0000-0000-0000-000000000001");
    private static final UUID NPC_A = UUID.fromString(
            "81000000-0000-0000-0000-00000000000a");
    private static final UUID NPC_B = UUID.fromString(
            "81000000-0000-0000-0000-00000000000b");
    private static final String ROSTER = "hydragon:dragon_horn";
    private static final String PROFILE = "dragon-one";

    @Test
    void listNeverPairsCurrentProfileWithLeaseFromPriorGeneration() {
        AtomicBoolean advancedToGenerationB = new AtomicBoolean();
        BondedCompanionRecord.Profile profileA = profile(1L);
        BondedCompanionRecord.Profile profileB = profile(3L);
        BondedCompanionRecord.Lease leaseA = lease("lease-a", NPC_A);
        BondedCompanionRecord.Lease leaseB = lease("lease-b", NPC_B);
        BondedCompanionStore store = interleavingStore(
                advancedToGenerationB, profileA, profileB, leaseA, leaseB);
        BondedCompanionChangePublisher changes =
                new BondedCompanionChangePublisher(null);
        BondedCompanionApiFacade api = facade(store, changes);
        try {
            var listed = api.list(OWNER, ROSTER).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, listed.code());
            assertEquals(3L, listed.value().getFirst().revision());
            assertEquals("lease-b",
                    listed.value().getFirst().activeLease().leaseToken());
            assertEquals(NPC_B,
                    listed.value().getFirst().activeLease().liveNpcUuid());
        } finally {
            api.close();
            changes.close();
        }
    }

    private BondedCompanionStore interleavingStore(
            AtomicBoolean advanced,
            BondedCompanionRecord.Profile profileA,
            BondedCompanionRecord.Profile profileB,
            BondedCompanionRecord.Lease leaseA,
            BondedCompanionRecord.Lease leaseB
    ) {
        return (BondedCompanionStore) Proxy.newProxyInstance(
                BondedCompanionStore.class.getClassLoader(),
                new Class<?>[]{BondedCompanionStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listProfiles" -> List.of(
                            advanced.get() ? profileB : profileA);
                    case "findActiveLeases" -> {
                        boolean wasGenerationB = advanced.getAndSet(true);
                        yield List.of(wasGenerationB ? leaseB : leaseA);
                    }
                    case "listExtensionData" -> List.of();
                    case "toString" -> "InterleavingBondedCompanionStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(
                            "Unexpected store call: " + method.getName());
                });
    }

    private BondedCompanionApiFacade facade(
            BondedCompanionStore store,
            BondedCompanionChangePublisher changes
    ) {
        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        BondedCompanionTransitionService transitions =
                new BondedCompanionTransitionService(policies);
        BondedCompanionProjectionService projections = projections();
        BondedCompanionDiagnosticContributor diagnostics =
                new BondedCompanionDiagnosticContributor(
                        BondedCompanionPersistenceReadiness::ready,
                        BondedCompanionStoreDiagnostics::empty, 1);
        BondedCompanionCoreApiOperations operations =
                new BondedCompanionCoreApiOperations(
                        store, rosters, policies, transitions, projections,
                        changes, diagnostics, () -> 1_000L);
        return new BondedCompanionApiFacade(
                BondedCompanionPersistenceReadiness::ready,
                store, changes, diagnostics, operations);
    }

    private BondedCompanionProjectionService projections() {
        BondedCompanionProjectionStorePlanner planner = request ->
                BondedCompanionProjectionStorePlanner.PlanningResult.rejected(
                        BondedCompanionProjectionStorePlanner.Status
                                .PROFILE_NOT_FOUND);
        BondedCompanionProjectionService.World world =
                new BondedCompanionProjectionService.World() {
                    @Override
                    public BondedCompanionProjectionService.SpawnResult spawn(
                            BondedCompanionProjectionService.SpawnPlan plan) {
                        return BondedCompanionProjectionService.SpawnResult
                                .failed();
                    }

                    @Override
                    public BondedCompanionProjectionValidator.Projection
                            readExact(
                            BondedCompanionProjectionValidator.LeaseExpectation
                                    lease) {
                        return null;
                    }
                };
        return new BondedCompanionProjectionService(
                planner, new UnusedDurability(), world,
                new BondedCompanionProjectionCleanupService(ignored ->
                        BondedCompanionProjectionCleanupService.Outcome
                                .ALREADY_MISSING),
                () -> "unused-lease", UUID::randomUUID);
    }

    private BondedCompanionRecord.Profile profile(long revision) {
        return new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, "hydragon:full_dragons",
                "Tamed_NordicDrake", BondedCompanionState.ACTIVE, revision,
                BondedCompanionPayload.of(
                        "complete-snapshot".getBytes(StandardCharsets.UTF_8)),
                100L, 200L + revision, Map.of(), "Drake", "Dragon", "Male",
                null, 0L, 0L, null, null);
    }

    private BondedCompanionRecord.Lease lease(String token, UUID npcUuid) {
        return new BondedCompanionRecord.Lease(
                PROFILE, token, npcUuid, "world-a", 100L, 0L,
                BondedCompanionRecord.ProjectionState.LIVE);
    }

    private static final class UnusedDurability
            implements BondedCompanionProjectionService.Durability {
        @Override
        public boolean beginSummon(
                BondedCompanionProjectionService.SummonRequest request,
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionProjectionCleanupService.CleanupIntent recovery) {
            return false;
        }

        @Override
        public boolean confirmSpawn(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                UUID spawnedNpcUuid) {
            return false;
        }

        @Override
        public boolean failSpawnAndEnqueueCleanup(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                List<BondedCompanionProjectionCleanupService.CleanupIntent>
                        cleanups,
                String reason) {
            return false;
        }

        @Override
        public BondedCompanionProjectionService.StoreDurabilityResult
                findStoreResult(BondedCompanionOperation operation) {
            return new BondedCompanionProjectionService.StoreDurabilityResult(
                    BondedCompanionProjectionService.StoreDurabilityStatus
                            .ABSENT);
        }

        @Override
        public BondedCompanionProjectionService.StoreDurabilityResult
                storeAndEnqueueCleanup(
                BondedCompanionProjectionService.StoreRequest request,
                BondedCompanionProjectionStorePlanner.StorePlan plan,
                BondedCompanionProjectionCleanupService.CleanupIntent cleanup) {
            return new BondedCompanionProjectionService.StoreDurabilityResult(
                    BondedCompanionProjectionService.StoreDurabilityStatus
                            .REJECTED);
        }

        @Override
        public boolean reconcileStored(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionProjectionStorePlanner.StorePlan plan,
                List<BondedCompanionProjectionCleanupService.CleanupIntent>
                        cleanups,
                String reason) {
            return false;
        }

        @Override
        public boolean confirmDeath(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionProjectionStorePlanner.StorePlan plan,
                long diedAtMs) {
            return false;
        }
    }
}
