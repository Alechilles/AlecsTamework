package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.assetstore.AssetPack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssetPatchReloadCoordinatorTest {

    @Test
    void reloadsNpcRoleTargetsThroughBuilderCache(@TempDir Path tempDir) {
        RecordingNpcReloadAdapter npc = new RecordingNpcReloadAdapter();
        RecordingAssetStoreReloadAdapter assetStore = new RecordingAssetStoreReloadAdapter(true);
        RecordingTameworkConfigReloadAdapter tameworkConfig = new RecordingTameworkConfigReloadAdapter();
        AssetPatchReloadCoordinator coordinator = coordinator(npc, assetStore, tameworkConfig);
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/NPC/Roles/_Core/Templates/Test.json"),
                status
        );

        assertEquals(List.of("Generated"), npc.loadedPacks);
        assertTrue(status.getHotReloadedTargets().contains("Server/NPC/Roles/*"));
    }

    @Test
    void reloadsItemTargetsThroughAssetStore(@TempDir Path tempDir) {
        RecordingAssetStoreReloadAdapter assetStore = new RecordingAssetStoreReloadAdapter(true);
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter(),
                assetStore,
                new RecordingTameworkConfigReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Item/Items/Commands/MyCommand.json"),
                status
        );

        assertEquals(List.of("Server/Item/Items/Commands/MyCommand.json"), assetStore.targets);
        assertEquals(List.of("Server/Item/Items/Commands/MyCommand.json"), status.getHotReloadedTargets());
    }

    @Test
    void reloadsJsonLikeParticleTargetsThroughAssetStore(@TempDir Path tempDir) {
        RecordingAssetStoreReloadAdapter assetStore = new RecordingAssetStoreReloadAdapter(true);
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter(),
                assetStore,
                new RecordingTameworkConfigReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Particles/Trail.particlesystem"),
                status
        );

        assertEquals(List.of("Server/Particles/Trail.particlesystem"), assetStore.targets);
        assertEquals(List.of("Server/Particles/Trail.particlesystem"), status.getHotReloadedTargets());
    }

    @Test
    void reloadsTameworkItemFeatureConfigsAfterAssetStoreUpdate(@TempDir Path tempDir) {
        RecordingAssetStoreReloadAdapter assetStore = new RecordingAssetStoreReloadAdapter(true);
        RecordingTameworkConfigReloadAdapter tameworkConfig = new RecordingTameworkConfigReloadAdapter();
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter(),
                assetStore,
                tameworkConfig
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status
        );

        assertEquals(List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"), assetStore.targets);
        assertEquals(List.of(Set.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json")),
                tameworkConfig.reloads);
        assertTrue(status.getHotReloadedTargets().contains("Server/Tamework/Items/*"));
    }

    @Test
    void reportsRestartRequiredWhenNoAssetStoreReloadRouteExists(@TempDir Path tempDir) {
        RecordingAssetStoreReloadAdapter assetStore = new RecordingAssetStoreReloadAdapter(false);
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter(),
                assetStore,
                new RecordingTameworkConfigReloadAdapter()
        );
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
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter(),
                new RecordingAssetStoreReloadAdapter(true),
                new RecordingTameworkConfigReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(pack(tempDir), List.of("Common/Models/Test.blockymodel"), status);

        assertEquals(List.of("Common/Models/Test.blockymodel"), status.getRestartRequiredTargets());
    }

    private static AssetPatchReloadCoordinator coordinator(
            RecordingNpcReloadAdapter npc,
            RecordingAssetStoreReloadAdapter assetStore,
            RecordingTameworkConfigReloadAdapter tameworkConfig) {
        return new AssetPatchReloadCoordinator(null, npc, assetStore, tameworkConfig);
    }

    private static AssetPack pack(Path tempDir) {
        return new AssetPack(tempDir, "Generated", tempDir, null, false, null);
    }

    private static final class RecordingNpcReloadAdapter implements AssetPatchReloadCoordinator.NpcBuilderReloadAdapter {
        private final List<String> loadedPacks = new ArrayList<>();

        @Override
        public void load(AssetPack generatedPack) {
            loadedPacks.add(generatedPack.getName());
        }
    }

    private static final class RecordingAssetStoreReloadAdapter
            implements AssetPatchReloadCoordinator.AssetStoreReloadAdapter {
        private final boolean reloadResult;
        private final List<String> targets = new ArrayList<>();

        private RecordingAssetStoreReloadAdapter(boolean reloadResult) {
            this.reloadResult = reloadResult;
        }

        @Override
        public boolean reload(AssetPack generatedPack, String target) {
            targets.add(target);
            return reloadResult;
        }
    }

    private static final class RecordingTameworkConfigReloadAdapter
            implements AssetPatchReloadCoordinator.TameworkConfigReloadAdapter {
        private final List<Set<String>> reloads = new ArrayList<>();

        @Override
        public boolean supportsItemFeatureConfig(String target) {
            return target.startsWith("Server/Tamework/Items/Spawners/")
                    || target.startsWith("Server/Tamework/Items/Naming/")
                    || target.startsWith("Server/Tamework/Items/Commands/");
        }

        @Override
        public boolean reloadItemFeatureConfigs(Collection<String> targets) {
            reloads.add(Set.copyOf(targets));
            return true;
        }
    }
}
