package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan;
import com.alechilles.alecstamework.npc.breeding.BreedingFertilitySnapshot;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.alechilles.alecstamework.ownership.BreedingPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.TestTwGlobalAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end regression coverage for the shared manual/passive breeding admission authority. */
@ResourceLock("TwGlobalConfig-static-state")
class BreedingPopulationHeadroomIntegrationTest {
    private static final String WORLD = "breeding-headroom-world";
    private static final String POPULATION_TYPE = "family:test";
    private static final int LITTER_SIZE = 4;
    private static final Vector3d CENTER = new Vector3d(8.0, 64.0, 8.0);
    private static final ClaimChunkCoordinate DESTINATION = new ClaimChunkCoordinate(WORLD, 0, 0);
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CLAIM_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final ClaimPopulationKey CLAIM_KEY = ClaimPopulationKey.simpleClaims(
            WORLD, UUID.fromString("00000000-0000-0000-0000-000000000201")
    );

    @TempDir
    Path tempDir;

    /** Regression: the shared authority must admit min(nearby, owner, claim), not three independent answers. */
    @Test
    void manualAndPassiveAdmissionUseTheSmallestExactHeadroom() throws Exception {
        assertHeadroom(new Scenario("nearby", 4, 0, 3, 0, 5, 3, 2, false, null));
        assertHeadroom(new Scenario("owner", 4, 1, 4, 0, 5, 1, 3, true, "owner-cap-reached"));
        assertHeadroom(new Scenario("claim", 3, 0, 3, 2, 5, 1, 1, false, "claim-cap-reached"));
    }

    /** Regression: InheritOwner=false cannot consume or be denied by either parent's full owner bucket. */
    @Test
    void unownedChildrenIgnoreFullParentOwnerCapacityButStillUseNearbyHeadroom() throws Exception {
        try (GlobalConfigScope ignored = GlobalConfigScope.install(2, 1, true);
             Harness harness = Harness.open(tempDir.resolve("unowned"), 2, 1)) {
            BreedingBirthPlan plan = birthPlan();
            int nearbyHeadroom = nearbyHeadroom(5, 2);
            PreparedBreedingPopulationBatch prepared = null;
            try {
                BreedingPopulationPreparationResult result = harness.runtime()
                        .breedingAdmissionService()
                        .prepareAsync(
                                request(plan, null, nearbyHeadroom, "unowned"),
                                harness.runtime().breedingAdmissionService().openPreparationContext()
                        ).get(5, TimeUnit.SECONDS);

                assertTrue(result.allowed());
                assertEquals(3, nearbyHeadroom);
                assertEquals(3, result.admittedCount());
                prepared = assertPrepared(result);
                assertTrue(prepared.children().stream().allMatch(child -> child.ownerId() == null));
                assertEquals(0L, harness.runtime().index().counts(OWNER, WORLD).globalPending());
                assertEquals(0L, harness.runtime().claimAdmissionService().pendingForClaim(CLAIM_KEY));
                assertEquals(3, harness.runtime().claimAdmissionService().pendingReservationCount());
                assertEquals(1, harness.bridge().calls(), "The passive sweep context should share one claim lookup.");

                assertEquals(3, harness.runtime().breedingAdmissionService()
                        .cancelRemainingAsync(prepared, "test-cleanup")
                        .get(5, TimeUnit.SECONDS));
                prepared = null;
            } finally {
                if (prepared != null) {
                    harness.runtime().breedingAdmissionService()
                            .cancelRemainingAsync(prepared, "test-finally-cleanup")
                            .get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** A prepared child must not survive a provider reload/generation change before live spawn. */
    @Test
    void providerGenerationChangeBetweenPreparationAndSpawnClosesAdmission() throws Exception {
        try (GlobalConfigScope ignored = GlobalConfigScope.install(10, 10, true);
             Harness harness = Harness.open(tempDir.resolve("provider-switch"), 0, 0)) {
            BreedingBirthPlan plan = birthPlan();
            PreparedBreedingPopulationBatch prepared = null;
            try {
                BreedingPopulationPreparationResult result = harness.runtime()
                        .breedingAdmissionService()
                        .prepareAsync(request(plan, OWNER, nearbyHeadroom(1, 0), "provider-switch"))
                        .get(5, TimeUnit.SECONDS);
                assertTrue(result.allowed());
                assertEquals(1, result.admittedCount());
                prepared = assertPrepared(result);
                assertEquals(1L, harness.runtime().index().counts(OWNER, WORLD).globalPending());
                assertEquals(1L, harness.runtime().claimAdmissionService().pendingForClaim(CLAIM_KEY));

                installSimpleClaimsProbe(
                        harness.runtime().claimProviderRegistry(), harness.bridge(), 2L
                );

                assertFalse(harness.runtime().breedingAdmissionService().claimForSpawn(prepared, 0));
                awaitNoPending(harness);
                assertEquals(0L, harness.runtime().index().counts(OWNER, WORLD).globalPending());
                assertEquals(0L, harness.runtime().claimAdmissionService().pendingForClaim(CLAIM_KEY));
                assertEquals(0, harness.runtime().claimAdmissionService().pendingReservationCount());
            } finally {
                if (prepared != null) {
                    harness.runtime().breedingAdmissionService()
                            .cancelRemainingAsync(prepared, "test-finally-cleanup")
                            .get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static void awaitNoPending(Harness harness) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (harness.runtime().index().counts(OWNER, WORLD).globalPending() != 0L
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }

    private void assertHeadroom(Scenario scenario) throws Exception {
        try (GlobalConfigScope ignored = GlobalConfigScope.install(
                scenario.ownerLimit(), scenario.claimLimit(), true
        ); Harness harness = Harness.open(
                tempDir.resolve(scenario.name()), scenario.ownerExisting(), scenario.claimExisting()
        )) {
            BreedingBirthPlan plan = birthPlan();
            int nearbyHeadroom = nearbyHeadroom(
                    scenario.nearbyLimit(), scenario.nearbyExisting()
            );
            PreparedBreedingPopulationBatch prepared = null;
            try {
                BreedingPopulationPreparationResult result = scenario.passive()
                        ? harness.runtime().breedingAdmissionService().prepareAsync(
                                request(plan, OWNER, nearbyHeadroom, scenario.name()),
                                harness.runtime().breedingAdmissionService().openPreparationContext()
                        ).get(5, TimeUnit.SECONDS)
                        : harness.runtime().breedingAdmissionService().prepareAsync(
                                request(plan, OWNER, nearbyHeadroom, scenario.name())
                        ).get(5, TimeUnit.SECONDS);

                assertTrue(result.allowed(), scenario.name());
                assertEquals(scenario.expected(), result.admittedCount(), scenario.name());
                assertEquals("breeding-population-clamped", result.reason(), scenario.name());
                prepared = assertPrepared(result);
                assertTrue(prepared.children().stream().allMatch(child -> OWNER.equals(child.ownerId())));
                assertEquals(scenario.expected(),
                        harness.runtime().index().counts(OWNER, WORLD).globalPending(), scenario.name());
                assertEquals(scenario.expected(),
                        harness.runtime().claimAdmissionService().pendingForClaim(CLAIM_KEY), scenario.name());
                assertEquals(1, harness.bridge().calls(), scenario.name());
                if (scenario.limitingReason() == null) {
                    assertNull(result.populationResult().limitingDecision(), scenario.name());
                } else {
                    assertNotNull(result.populationResult().limitingDecision(), scenario.name());
                    assertEquals(scenario.limitingReason(),
                            result.populationResult().limitingDecision().reason(), scenario.name());
                }

                assertEquals(scenario.expected(), harness.runtime().breedingAdmissionService()
                        .cancelRemainingAsync(prepared, "test-cleanup")
                        .get(5, TimeUnit.SECONDS), scenario.name());
                prepared = null;
                assertEquals(0L, harness.runtime().index().counts(OWNER, WORLD).globalPending());
                assertEquals(0L, harness.runtime().claimAdmissionService().pendingForClaim(CLAIM_KEY));
            } finally {
                if (prepared != null) {
                    harness.runtime().breedingAdmissionService()
                            .cancelRemainingAsync(prepared, "test-finally-cleanup")
                            .get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static PreparedBreedingPopulationBatch assertPrepared(
            BreedingPopulationPreparationResult result
    ) {
        assertNotNull(result.populationResult());
        assertNotNull(result.preparedBatch());
        return result.preparedBatch();
    }

    private static BreedingPopulationAdmissionRequest request(
            BreedingBirthPlan plan,
            UUID ownerId,
            int maximumAdmittedCount,
            String attempt
    ) {
        BreedingBirthPlanSnapshot snapshot = snapshot(plan, ownerId);
        UUID jobId = UUID.nameUUIDFromBytes(
                ("headroom:" + attempt).getBytes(StandardCharsets.UTF_8)
        );
        return BreedingPopulationAdmissionRequestFactory.create(
                WORLD,
                CENTER,
                snapshot,
                snapshot.children(),
                maximumAdmittedCount,
                jobId,
                attempt + ":parent-a-profile",
                attempt + ":parent-b-profile"
        );
    }

    private static BreedingBirthPlan birthPlan() {
        List<PlannedChild> children = new ArrayList<>();
        for (int index = 0; index < LITTER_SIZE; index++) {
            children.add(new PlannedChild(
                    "Test_Child",
                    null,
                    null,
                    null,
                    POPULATION_TYPE
            ));
        }
        return new BreedingBirthPlan(
                new BreedingFertilitySnapshot(1.0, 1.0, LITTER_SIZE, 0.0, LITTER_SIZE),
                children
        );
    }

    private static BreedingBirthPlanSnapshot snapshot(BreedingBirthPlan plan, UUID ownerId) {
        List<BreedingBirthPlanSnapshot.PlannedChild> children = new ArrayList<>();
        for (int index = 0; index < plan.children().size(); index++) {
            PlannedChild child = plan.children().get(index);
            children.add(new BreedingBirthPlanSnapshot.PlannedChild(
                    BreedingJobPlanSnapshotMapper.childKey(index),
                    child.roleId(),
                    -1,
                    child.adultRoleId(),
                    child.gender(),
                    false,
                    null,
                    null,
                    ownerId,
                    ownerId == null ? null : "Test Owner",
                    child.populationType()
            ));
        }
        BreedingFertilitySnapshot fertility = plan.fertilitySnapshot();
        return new BreedingBirthPlanSnapshot(
                fertility.parentAMultiplier(),
                fertility.parentBMultiplier(),
                fertility.expectedOffspring(),
                fertility.rolledChildCount(),
                children
        );
    }

    private static int nearbyHeadroom(int limit, int existing) {
        return Math.max(0, Math.min(LITTER_SIZE, limit - existing));
    }

    private record Scenario(String name,
                            int ownerLimit,
                            int ownerExisting,
                            int claimLimit,
                            int claimExisting,
                            int nearbyLimit,
                            int nearbyExisting,
                            int expected,
                            boolean passive,
                            String limitingReason) {
    }

    private record Harness(TameworkPersistenceRuntime persistence,
                           OwnerPopulationRuntime runtime,
                           FixedClaimBridge bridge) implements AutoCloseable {
        static Harness open(Path path, int ownerExisting, int claimExisting) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
            FixedClaimBridge bridge = new FixedClaimBridge();
            installSimpleClaimsProbe(runtime.claimProviderRegistry(), bridge);
            Seed seed = seed(ownerExisting, claimExisting);
            runtime.index().replaceCommittedEntries(seed.ownerEntries(), OwnerPopulationReadiness.READY);
            runtime.claimOccupancyIndex().replaceCommittedEntries(
                    seed.claimEntries(), ClaimOccupancyReadiness.READY
            );
            return new Harness(persistence, runtime, bridge);
        }

        @Override
        public void close() {
            runtime.close();
            persistence.close();
        }
    }

    private static Seed seed(int ownerExisting, int claimExisting) {
        List<OwnerPopulationEntry> ownerEntries = new ArrayList<>();
        List<ClaimOccupancyEntry> claimEntries = new ArrayList<>();
        for (int index = 0; index < ownerExisting; index++) {
            String profile = "owner-existing-" + index;
            ClaimChunkCoordinate outside = new ClaimChunkCoordinate(WORLD, 10 + index, 0);
            ownerEntries.add(new OwnerPopulationEntry(
                    profile, OWNER, WORLD, CompanionLifecycleState.ACTIVE, 1L
            ));
            claimEntries.add(new ClaimOccupancyEntry(
                    profile, OWNER, CompanionLifecycleState.ACTIVE, outside, 1L
            ));
        }
        for (int index = 0; index < claimExisting; index++) {
            String profile = "claim-existing-" + index;
            ownerEntries.add(new OwnerPopulationEntry(
                    profile, CLAIM_OWNER, WORLD, CompanionLifecycleState.ACTIVE, 1L
            ));
            claimEntries.add(new ClaimOccupancyEntry(
                    profile, CLAIM_OWNER, CompanionLifecycleState.ACTIVE, DESTINATION, 1L
            ));
        }
        return new Seed(List.copyOf(ownerEntries), List.copyOf(claimEntries));
    }

    private record Seed(List<OwnerPopulationEntry> ownerEntries,
                        List<ClaimOccupancyEntry> claimEntries) {
    }

    private static void installSimpleClaimsProbe(
            ClaimProviderRegistry registry,
            FixedClaimBridge bridge
    ) throws Exception {
        installSimpleClaimsProbe(registry, bridge, 1L);
    }

    private static void installSimpleClaimsProbe(
            ClaimProviderRegistry registry,
            FixedClaimBridge bridge,
            long generationEpoch
    ) throws Exception {
        Field field = ClaimProviderRegistry.class.getDeclaredField("simpleClaimsProbe");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<ClaimProviderProbe> probe =
                (AtomicReference<ClaimProviderProbe>) field.get(registry);
        probe.set(new FixedProbe(ClaimProviderProbeResult.ready(
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                bridge.providerId(),
                "test",
                new ClaimProviderGeneration("breeding-headroom", "test-loader", generationEpoch),
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                bridge
        )));
    }

    private record FixedProbe(ClaimProviderProbeResult result) implements ClaimProviderProbe {
        @Override
        public ClaimIntegrationProvider provider() {
            return result.provider();
        }

        @Override
        public ClaimProviderProbeResult probe() {
            return result;
        }
    }

    private static final class FixedClaimBridge implements ClaimIntegrationBridge {
        private final AtomicInteger calls = new AtomicInteger();
        private final ClaimResolution resolution = ClaimResolution.found(
                CLAIM_KEY, new ClaimFootprint(List.of(DESTINATION))
        );

        int calls() {
            return calls.get();
        }

        @Override
        public String providerId() {
            return "simpleclaims";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getUnavailableReason() {
            return null;
        }

        @Override
        public ClaimResolution resolveClaim(String worldName, double blockX, double blockZ) {
            calls.incrementAndGet();
            return resolution;
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return resolution.toLookupResult();
        }
    }

    /** Installs a real in-memory global asset so the production policy resolver remains in use. */
    private static final class GlobalConfigScope implements AutoCloseable {
        private final Object oldStore;
        private final Object oldActive;
        private final boolean oldCacheDirty;
        private final boolean oldInheritanceDirty;

        private GlobalConfigScope(Object oldStore,
                                  Object oldActive,
                                  boolean oldCacheDirty,
                                  boolean oldInheritanceDirty) {
            this.oldStore = oldStore;
            this.oldActive = oldActive;
            this.oldCacheDirty = oldCacheDirty;
            this.oldInheritanceDirty = oldInheritanceDirty;
        }

        static GlobalConfigScope install(int ownerLimit,
                                         int claimLimit,
                                         boolean requireClaim) throws Exception {
            Object oldStore = staticField("ASSET_STORE").get(null);
            Object oldActive = staticField("ACTIVE_CONFIG").get(null);
            boolean oldCacheDirty = staticField("CACHE_DIRTY").getBoolean(null);
            boolean oldInheritanceDirty = staticField("INHERITANCE_CACHE_DIRTY").getBoolean(null);

            TwGlobalConfig config = TwGlobalConfig.defaultConfig();
            set(config, "id", "Breeding_Headroom_Test");
            set(config, "priority", Integer.MAX_VALUE);
            set(config, "populationLimitPerPlayerOwnedTotal", ownerLimit);
            set(config, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.GLOBAL);
            set(config, "simpleClaimsProvider", ClaimIntegrationProvider.SIMPLE_CLAIMS);
            set(config, "simpleClaimsEnabled", true);
            set(config, "simpleClaimsBreedingLimitPerClaimChunk", claimLimit);
            set(config, "simpleClaimsBreedingLimitPerClaimTotal", 0);
            set(config, "simpleClaimsBreedingRequiresClaim", requireClaim);
            set(config, "simpleClaimsSectionDefined", true);

            DefaultAssetMap<String, TwGlobalConfig> map = new DefaultAssetMap<>(
                    Map.of(config.getId(), config)
            );
            AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> store =
                    new TestTwGlobalAssetStore(map);
            staticField("ASSET_STORE").set(null, store);
            staticField("ACTIVE_CONFIG").set(null, null);
            staticField("CACHE_DIRTY").setBoolean(null, true);
            staticField("INHERITANCE_CACHE_DIRTY").setBoolean(null, true);
            return new GlobalConfigScope(
                    oldStore, oldActive, oldCacheDirty, oldInheritanceDirty
            );
        }

        @Override
        public void close() throws Exception {
            staticField("ASSET_STORE").set(null, oldStore);
            staticField("ACTIVE_CONFIG").set(null, oldActive);
            staticField("CACHE_DIRTY").setBoolean(null, oldCacheDirty);
            staticField("INHERITANCE_CACHE_DIRTY").setBoolean(null, oldInheritanceDirty);
        }

        private static void set(Object target, String name, Object value) throws Exception {
            Field field = TwGlobalConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        private static Field staticField(String name) throws Exception {
            Field field = TwGlobalConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

}
