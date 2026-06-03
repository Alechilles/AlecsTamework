package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TwConfigOverrideManagerPathTest {

    @Test
    void resolveRelativeServerPathUsesInnermostServerSegment() {
        Path sourcePath = Path.of(
                "C:/Users/22ale/AppData/Roaming/Hytale/install/pre-release/package/game/latest/Server/mods/"
                        + "Alec's Animal Husbandry!/Server/Tamework/Global/TwGlobalConfig_AnimalHusbandry.json"
        );

        Path resolved = TwConfigOverrideManager.resolveRelativeServerPath(
                sourcePath,
                "Tamework/Global",
                "TwGlobalConfig_AnimalHusbandry"
        );

        assertEquals(
                Path.of("Server/Tamework/Global/TwGlobalConfig_AnimalHusbandry.json"),
                resolved
        );
    }

    @Test
    void resolveRelativeServerPathFallsBackWhenSourcePathMissing() {
        Path resolved = TwConfigOverrideManager.resolveRelativeServerPath(
                null,
                "Tamework/Needs",
                "TwNeedsConfig_Default"
        );

        assertEquals(
                Path.of("Server/Tamework/Needs/Config_TwNeedsConfig_Default.json"),
                resolved
        );
    }

    @Test
    void resolveUniverseRootUsesSharedOverrideScopeForInstanceWorlds() {
        Path defaultWorldSavePath = Path.of(
                "C:/Users/22ale/AppData/Roaming/Hytale/Saves/MyUniverse/universe/worlds/default"
        );
        Path instanceWorldSavePath = Path.of(
                "C:/Users/22ale/AppData/Roaming/Hytale/Saves/MyUniverse/universe/worlds/"
                        + "instance-Portals_Hedera-eccce206-7b1b-4f9f-b047-506895b403e1"
        );

        Path defaultOverrideRoot = TwConfigOverrideManager.resolveUniverseRoot(defaultWorldSavePath)
                .resolve(TwConfigOverrideManager.OVERRIDE_ROOT_RELATIVE)
                .normalize();
        Path instanceOverrideRoot = TwConfigOverrideManager.resolveUniverseRoot(instanceWorldSavePath)
                .resolve(TwConfigOverrideManager.OVERRIDE_ROOT_RELATIVE)
                .normalize();

        assertEquals(defaultOverrideRoot, instanceOverrideRoot);
        assertEquals(
                TwConfigOverrideManager.normalizeScopeKey(defaultOverrideRoot),
                TwConfigOverrideManager.normalizeScopeKey(instanceOverrideRoot)
        );
    }

    @Test
    void resolveGeneratedPatchPathUsesTameworkConfigTargetPath(@TempDir Path tempDir) {
        TwConfigAssetDescriptor descriptor = descriptor(
                "AHSpawnSoulLantern",
                Path.of("Server/Tamework/Items/Spawners/AHSpawnSoulLantern.json")
        );

        assertEquals(
                tempDir.resolve("Server/Tamework/Items/Spawners/AHSpawnSoulLantern.json").normalize(),
                TwConfigOverrideManager.resolveGeneratedPatchPath(tempDir, descriptor)
        );
    }

    @Test
    void resolveGeneratedPatchPathIgnoresPatchDefinitions(@TempDir Path tempDir) {
        TwConfigAssetDescriptor descriptor = descriptor(
                "Tamework_Chocobo_Patch",
                Path.of("Server/Tamework/Patches/Tamework_Chocobo_Patch.json")
        );

        assertNull(TwConfigOverrideManager.resolveGeneratedPatchPath(tempDir, descriptor));
    }

    private static TwConfigAssetDescriptor descriptor(String assetId, Path relativeServerPath) {
        return new TwConfigAssetDescriptor(
                TwConfigFamily.SPAWNER,
                TwConfigFamily.SPAWNER.getId(),
                TwConfigFamily.SPAWNER.getDisplayName(),
                TwConfigFamily.SPAWNER.getStorePath(),
                assetId,
                "Alechilles:Alec's Animal Husbandry!",
                null,
                Path.of("C:/source").resolve(relativeServerPath),
                relativeServerPath,
                true,
                true,
                false
        );
    }
}
