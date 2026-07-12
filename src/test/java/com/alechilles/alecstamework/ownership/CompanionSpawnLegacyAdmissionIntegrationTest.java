package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.TestTwGlobalAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end legacy filled-item admission and provisional-identity lifecycle coverage. */
@ResourceLock("TwGlobalConfig-static-state")
class CompanionSpawnLegacyAdmissionIntegrationTest {
    private static final UUID OWNER = UUID.fromString(
            "00000000-0000-0000-0000-000000000301"
    );
    private static final String WORLD = "legacy-spawn-world";

    @TempDir
    Path tempDir;

    @Test
    void legacyFilledItemAtOwnerCapIsDeniedWithoutLeakingItsProvisionalAlias() throws Exception {
        try (GlobalConfigScope ignored = GlobalConfigScope.install(1);
             Harness harness = Harness.open(tempDir.resolve("at-cap"), List.of(
                     new OwnerPopulationEntry(
                             "existing", OWNER, WORLD, CompanionLifecycleState.ACTIVE, 1L
                     )
             ))) {
            CompanionSpawnPreparationResult result = harness.runtime()
                    .companionSpawnAdmissionService()
                    .prepareAsync(legacyRequest("at-cap", UUID.randomUUID()))
                    .get(5, TimeUnit.SECONDS);

            assertFalse(result.allowed());
            assertEquals("owner-cap-reached", result.reason());
            assertNotNull(result.limitingDecision());
            assertEquals("owner-cap-reached", result.limitingDecision().ownerDecision().reason());
            assertEquals(0, harness.runtime().identityResolver().aliasCount());
            assertEquals(0L, harness.runtime().index().counts(OWNER, WORLD).globalPending());
        }
    }

    @Test
    void canceledLegacyPreparationReleasesItsProvisionalAliasExactlyOnce() throws Exception {
        try (GlobalConfigScope ignored = GlobalConfigScope.install(2);
             Harness harness = Harness.open(tempDir.resolve("cancel"), List.of())) {
            CompanionSpawnPreparationResult result = harness.runtime()
                    .companionSpawnAdmissionService()
                    .prepareAsync(legacyRequest("cancel", UUID.randomUUID()))
                    .get(5, TimeUnit.SECONDS);

            assertTrue(result.allowed());
            assertNotNull(result.preparedBatch());
            assertEquals(1, harness.runtime().identityResolver().aliasCount());
            assertTrue(harness.runtime().companionSpawnAdmissionService()
                    .cancelAsync(result.preparedBatch(), 0, "test-cancel")
                    .get(5, TimeUnit.SECONDS));
            assertEquals(0, harness.runtime().identityResolver().aliasCount());
            assertTrue(harness.runtime().companionSpawnAdmissionService()
                    .cancelAsync(result.preparedBatch(), 0, "test-cancel-retry")
                    .get(5, TimeUnit.SECONDS));
            assertEquals(0, harness.runtime().identityResolver().aliasCount());
        }
    }

    private static CompanionSpawnAdmissionRequest legacyRequest(String key, UUID previousUuid) {
        return new CompanionSpawnAdmissionRequest(
                null,
                previousUuid,
                CompanionLifecycleState.CAPTURED,
                true,
                OWNER,
                "Legacy Owner",
                WORLD,
                0,
                0,
                OwnerPopulationOperation.RESTORE,
                "spawner_release",
                "legacy-filled-item:" + key,
                false
        );
    }

    private record Harness(
            TameworkPersistenceRuntime persistence,
            OwnerPopulationRuntime runtime
    ) implements AutoCloseable {
        static Harness open(Path path, List<OwnerPopulationEntry> entries) throws Exception {
            TameworkPersistenceRuntime persistence = TameworkPersistenceRuntime.initialize(path, null);
            OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
            runtime.index().replaceCommittedEntries(entries, OwnerPopulationReadiness.READY);
            runtime.claimOccupancyIndex().replaceCommittedEntries(
                    List.of(), ClaimOccupancyReadiness.READY
            );
            return new Harness(persistence, runtime);
        }

        @Override
        public void close() {
            runtime.close();
            persistence.close();
        }
    }

    /** Installs a real in-memory global asset so production owner policy remains in use. */
    private static final class GlobalConfigScope implements AutoCloseable {
        private final Object oldStore;
        private final Object oldActive;
        private final boolean oldCacheDirty;
        private final boolean oldInheritanceDirty;

        private GlobalConfigScope(
                Object oldStore,
                Object oldActive,
                boolean oldCacheDirty,
                boolean oldInheritanceDirty
        ) {
            this.oldStore = oldStore;
            this.oldActive = oldActive;
            this.oldCacheDirty = oldCacheDirty;
            this.oldInheritanceDirty = oldInheritanceDirty;
        }

        static GlobalConfigScope install(int ownerLimit) throws Exception {
            Object oldStore = staticField("ASSET_STORE").get(null);
            Object oldActive = staticField("ACTIVE_CONFIG").get(null);
            boolean oldCacheDirty = staticField("CACHE_DIRTY").getBoolean(null);
            boolean oldInheritanceDirty = staticField("INHERITANCE_CACHE_DIRTY").getBoolean(null);

            TwGlobalConfig config = TwGlobalConfig.defaultConfig();
            set(config, "id", "Legacy_Spawn_Admission_Test");
            set(config, "priority", Integer.MAX_VALUE);
            set(config, "populationLimitPerPlayerOwnedTotal", ownerLimit);
            set(config, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.GLOBAL);
            set(config, "simpleClaimsProvider", ClaimIntegrationProvider.OFF);
            set(config, "simpleClaimsEnabled", false);
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
