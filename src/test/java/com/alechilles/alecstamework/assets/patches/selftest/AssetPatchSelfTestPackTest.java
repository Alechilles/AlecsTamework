package com.alechilles.alecstamework.assets.patches.selftest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;

final class AssetPatchSelfTestPackTest {

    @Test
    void rootResolvesUnderDataDirectory(@TempDir Path tempDir) {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir, null, null);

        assertTrue(pack.root().startsWith(tempDir.toAbsolutePath().normalize()));
        assertTrue(pack.root().endsWith("AssetPatchSelfTestPack"));
    }

    @Test
    void packIdUsesParseablePluginIdentifier(@TempDir Path tempDir) {
        AssetPatchSelfTestPack defaultPack = new AssetPatchSelfTestPack(tempDir.resolve("default"), null, null);
        PluginIdentifier.fromString(defaultPack.packId());

        PluginManifest manifest = new PluginManifest();
        manifest.setGroup("Alechilles");
        manifest.setName("Alec's Tamework!");
        AssetPatchSelfTestPack manifestPack = new AssetPatchSelfTestPack(tempDir.resolve("manifest"), manifest, null);

        PluginIdentifier identifier = PluginIdentifier.fromString(manifestPack.packId());
        assertEquals("Alechilles", identifier.getGroup());
        assertEquals("Alec's Tamework!_AssetPatchSelfTest", identifier.getName());
    }

    @Test
    void refusesEscapedRelativePaths(@TempDir Path tempDir) {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir, null, null);

        assertThrows(Exception.class, () -> pack.resolveRelative("../escape.json"));
    }

    @Test
    void writesAndCleansFixtures(@TempDir Path tempDir) throws Exception {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir, null, null);

        pack.writeRunFixtures("run-1", AssetPatchSelfTestCase.defaultCases());
        for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
            assertTrue(Files.exists(pack.resolveRelative(selfTestCase.sourcePath())));
            assertTrue(Files.exists(pack.resolveRelative(selfTestCase.patchPath())));
        }

        pack.cleanupFixtures(AssetPatchSelfTestCase.defaultCases());
        for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
            assertFalse(Files.exists(pack.resolveRelative(selfTestCase.sourcePath())));
            assertFalse(Files.exists(pack.resolveRelative(selfTestCase.patchPath())));
        }
    }
}
