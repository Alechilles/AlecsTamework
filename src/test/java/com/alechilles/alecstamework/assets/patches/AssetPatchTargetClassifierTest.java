package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class AssetPatchTargetClassifierTest {

    @Test
    void classifiesNpcRoleTargetsAsNpcBuilders() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Server/NPC/Roles/Creature/Mammal/MyMob.json");

        assertEquals(AssetPatchTargetKind.NPC_BUILDER, result.kind());
        assertEquals(AssetPatchReloadMode.NPC_BUILDERS, result.reloadMode());
    }

    @Test
    void classifiesItemTargetsAsRestartRequired() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Server/Item/Items/Commands/MyCommand.json");

        assertEquals(AssetPatchTargetKind.HYTALE_ASSET_STORE, result.kind());
        assertEquals(AssetPatchReloadMode.RESTART_REQUIRED, result.reloadMode());
    }

    @Test
    void classifiesTameworkConfigTargetsAsRestartRequired() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Server/Tamework/Items/Commands/MyCommandConfig.json");

        assertEquals(AssetPatchTargetKind.TAMEWORK_CONFIG, result.kind());
        assertEquals(AssetPatchReloadMode.RESTART_REQUIRED, result.reloadMode());
    }

    @Test
    void classifiesJsonLikeParticleFilesAsRestartRequired() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Server/Particles/MyParticle.particlesystem");

        assertEquals(AssetPatchTargetKind.HYTALE_ASSET_STORE, result.kind());
        assertEquals(AssetPatchReloadMode.RESTART_REQUIRED, result.reloadMode());
    }

    @Test
    void classifiesCommonAssetsAsRestartRequired() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Common/Blocks/MyModel.blockymodel");

        assertEquals(AssetPatchTargetKind.COMMON_ASSET, result.kind());
        assertEquals(AssetPatchReloadMode.RESTART_REQUIRED, result.reloadMode());
    }

    @Test
    void classifiesUnknownJsonTargetsAsRestartRequired() {
        AssetPatchTargetClassification result =
                AssetPatchTargetClassifier.classify("Tools/Generated/Thing.json");

        assertEquals(AssetPatchTargetKind.UNKNOWN_JSON, result.kind());
        assertEquals(AssetPatchReloadMode.RESTART_REQUIRED, result.reloadMode());
    }
}
