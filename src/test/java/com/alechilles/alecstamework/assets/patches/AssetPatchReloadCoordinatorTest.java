package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.hypixel.hytale.assetstore.AssetPack;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssetPatchReloadCoordinatorTest {

    @Test
    void leavesNpcRoleTargetsForHytaleWatcher(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator();
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/NPC/Roles/_Core/Templates/Test.json"),
                status
        );

        assertTrue(status.getHotReloadedTargets().isEmpty());
        assertTrue(status.getRestartRequiredTargets().isEmpty());
        assertTrue(status.getFailed().isEmpty());
    }

    @Test
    void reportsItemTargetsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator();
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Item/Items/Commands/MyCommand.json"),
                status
        );

        assertEquals(List.of("Server/Item/Items/Commands/MyCommand.json"), status.getRestartRequiredTargets());
    }

    @Test
    void reportsJsonLikeParticleTargetsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator();
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Particles/Trail.particlesystem"),
                status
        );

        assertEquals(List.of("Server/Particles/Trail.particlesystem"), status.getRestartRequiredTargets());
    }

    @Test
    void loadsTameworkItemFeatureConfigsAndReloadsRegistries(@TempDir Path tempDir) {
        AtomicInteger loaderCalls = new AtomicInteger();
        AtomicInteger registryReloads = new AtomicInteger();
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator((pack, family) -> {
            assertEquals(TwConfigFamily.SPAWNER, family);
            loaderCalls.incrementAndGet();
        }, registryReloads::incrementAndGet);
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status
        );

        assertEquals(1, loaderCalls.get());
        assertEquals(1, registryReloads.get());
        assertEquals(
                List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json", "Server/Tamework/Items/*"),
                status.getHotReloadedTargets()
        );
        assertTrue(status.getRestartRequiredTargets().isEmpty());
        assertTrue(status.getFailed().isEmpty());
    }

    @Test
    void reportsTameworkConfigLoadFailureAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator((pack, family) -> {
            throw new java.io.IOException("boom");
        }, () -> {
        });
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status
        );

        assertEquals(List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status.getRestartRequiredTargets());
        assertEquals(1, status.getFailed().size());
    }

    @Test
    void reportsRestartRequiredWithoutCallingGenericAssetStoreReload(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator();
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Item/Items/Commands/MyCommand.json"),
                status
        );

        assertEquals(List.of("Server/Item/Items/Commands/MyCommand.json"), status.getRestartRequiredTargets());
    }

    @Test
    void reportsCommonTargetsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = new AssetPatchReloadCoordinator();
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(pack(tempDir), List.of("Common/Models/Test.blockymodel"), status);

        assertEquals(List.of("Common/Models/Test.blockymodel"), status.getRestartRequiredTargets());
    }

    private static AssetPack pack(Path tempDir) {
        return new AssetPack(tempDir, "Generated", tempDir, null, false, null, AssetPack.PackSource.RUNTIME);
    }
}
