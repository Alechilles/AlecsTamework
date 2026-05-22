package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.assetstore.AssetPack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssetPatchReloadCoordinatorTest {

    @Test
    void reloadsNpcRoleTargetsThroughBuilderCache(@TempDir Path tempDir) {
        RecordingNpcReloadAdapter npc = new RecordingNpcReloadAdapter();
        AssetPatchReloadCoordinator coordinator = coordinator(npc);
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
    void reportsItemTargetsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter()
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
    void reportsJsonLikeParticleTargetsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Particles/Trail.particlesystem"),
                status
        );

        assertEquals(List.of("Server/Particles/Trail.particlesystem"), status.getRestartRequiredTargets());
    }

    @Test
    void reportsTameworkItemFeatureConfigsAsRestartRequired(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(
                pack(tempDir),
                List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status
        );

        assertEquals(List.of("Server/Tamework/Items/Spawners/TwSpawnerConfig_MyEgg.json"),
                status.getRestartRequiredTargets());
    }

    @Test
    void reportsRestartRequiredWithoutCallingGenericAssetStoreReload(@TempDir Path tempDir) {
        AssetPatchReloadCoordinator coordinator = coordinator(
                new RecordingNpcReloadAdapter()
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
                new RecordingNpcReloadAdapter()
        );
        AssetPatchStatus status = new AssetPatchStatus();

        coordinator.reloadPublishedTargets(pack(tempDir), List.of("Common/Models/Test.blockymodel"), status);

        assertEquals(List.of("Common/Models/Test.blockymodel"), status.getRestartRequiredTargets());
    }

    private static AssetPatchReloadCoordinator coordinator(RecordingNpcReloadAdapter npc) {
        return new AssetPatchReloadCoordinator(null, npc);
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
}
