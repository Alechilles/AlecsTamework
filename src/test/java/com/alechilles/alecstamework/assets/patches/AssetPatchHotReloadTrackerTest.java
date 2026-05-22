package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;

final class AssetPatchHotReloadTrackerTest {
    @Test
    void recordsOnlyGeneratedPackAssets() {
        AssetPatchHotReloadTracker tracker = new AssetPatchHotReloadTracker("generated");
        long mark = tracker.mark();
        TestAssetMap<Item> map = new TestAssetMap<>(Map.of(
                "TwPatchSelfTest_CommandItem", "generated",
                "OtherItem", "source"
        ), Map.of(
                "TwPatchSelfTest_CommandItem",
                Path.of("GeneratedPatches/Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json"),
                "OtherItem",
                Path.of("AssetPatchSelfTestPack/Server/Item/Items/Tamework/SelfTest/OtherItem.json")
        ));

        tracker.recordGeneratedAssetStoreMonitor(
                Item.class,
                "generated",
                List.of(Path.of("GeneratedPatches/Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json"))
        );
        tracker.recordLoadedAssets(Item.class, map, List.of("TwPatchSelfTest_CommandItem", "OtherItem"));

        Set<String> observed = tracker.awaitHotReloadedTargets(
                List.of(
                        "Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json",
                        "Server/Item/Items/Tamework/SelfTest/OtherItem.json"
                ),
                mark,
                Duration.ZERO
        );
        assertEquals(Set.of("Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json"), observed);
    }

    @Test
    void ignoresGeneratedPackOwnershipUntilGeneratedPathIsMonitored() {
        AssetPatchHotReloadTracker tracker = new AssetPatchHotReloadTracker("generated");
        long mark = tracker.mark();

        tracker.recordLoadedAssets(
                Item.class,
                new TestAssetMap<>(
                        Map.of("TwPatchSelfTest_CommandItem", "generated"),
                        Map.of("TwPatchSelfTest_CommandItem",
                                Path.of("AssetPatchSelfTestPack/Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json"))
                ),
                List.of("TwPatchSelfTest_CommandItem")
        );

        Set<String> observed = tracker.awaitHotReloadedTargets(
                List.of("Server/Item/Items/Tamework/SelfTest/TwPatchSelfTest_CommandItem.json"),
                mark,
                Duration.ZERO
        );
        assertEquals(Set.of(), observed);
    }

    @Test
    void mapsParticleSystemTargetsByFileName() {
        AssetPatchHotReloadTracker tracker = new AssetPatchHotReloadTracker("generated");
        long mark = tracker.mark();

        tracker.recordGeneratedAssetStoreMonitor(
                ParticleSystem.class,
                "generated",
                List.of(Path.of("GeneratedPatches/Server/Particles/Tamework/TwPatchSelfTest.particlesystem"))
        );
        tracker.recordLoadedAssets(
                ParticleSystem.class,
                new TestAssetMap<>(
                        Map.of("TwPatchSelfTest", "generated"),
                        Map.of("TwPatchSelfTest", Path.of("GeneratedPatches/Server/Particles/Tamework/TwPatchSelfTest.particlesystem"))
                ),
                List.of("TwPatchSelfTest")
        );

        Set<String> observed = tracker.awaitHotReloadedTargets(
                List.of("Server/Particles/Tamework/TwPatchSelfTest.particlesystem"),
                mark,
                Duration.ZERO
        );
        assertTrue(observed.contains("Server/Particles/Tamework/TwPatchSelfTest.particlesystem"));
    }

    @Test
    void recordsCommonAssetsOnlyAfterGeneratedFileIsActive(@TempDir Path tempDir) throws Exception {
        AssetPatchHotReloadTracker tracker = new AssetPatchHotReloadTracker("generated");
        Path generated = tempDir.resolve(
                "GeneratedPatches/Common/Tamework/SelfTest/TwPatchSelfTest_Common.json"
        );
        Files.createDirectories(generated.getParent());
        byte[] bytes = "{\"PatchApplied\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(generated, bytes);
        String commonName = "Tamework/SelfTest/TwPatchSelfTest_Common.json";
        long mark = tracker.mark();

        try {
            tracker.recordGeneratedCommonAssetMonitor("generated", List.of(generated));
            CommonAssetRegistry.addCommonAsset("generated", new FileCommonAsset(generated, commonName, bytes));

            Set<String> observed = tracker.awaitHotReloadedTargets(
                    List.of("Common/Tamework/SelfTest/TwPatchSelfTest_Common.json"),
                    mark,
                    Duration.ZERO
            );

            assertEquals(Set.of("Common/Tamework/SelfTest/TwPatchSelfTest_Common.json"), observed);
        } finally {
            CommonAssetRegistry.removeCommonAssetByName("generated", commonName);
        }
    }

    @Test
    void ignoresCommonAssetsUntilRegistryActivatesGeneratedFile(@TempDir Path tempDir) throws Exception {
        AssetPatchHotReloadTracker tracker = new AssetPatchHotReloadTracker("generated");
        Path generated = tempDir.resolve(
                "GeneratedPatches/Common/Tamework/SelfTest/TwPatchSelfTest_Common.json"
        );
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "{\"PatchApplied\":true}", StandardCharsets.UTF_8);
        long mark = tracker.mark();

        tracker.recordGeneratedCommonAssetMonitor("generated", List.of(generated));

        Set<String> observed = tracker.awaitHotReloadedTargets(
                List.of("Common/Tamework/SelfTest/TwPatchSelfTest_Common.json"),
                mark,
                Duration.ZERO
        );
        assertEquals(Set.of(), observed);
    }

    private static final class TestAssetMap<T extends com.hypixel.hytale.assetstore.JsonAsset<String>>
            extends AssetMap<String, T> {
        private final Map<String, String> packs;
        private final Map<String, Path> paths;

        private TestAssetMap(Map<String, String> packs, Map<String, Path> paths) {
            this.packs = packs;
            this.paths = paths;
        }

        @Override
        public T getAsset(String key) {
            return null;
        }

        @Override
        public T getAsset(String pack, String key) {
            return null;
        }

        @Override
        public Path getPath(String key) {
            return paths.get(key);
        }

        @Override
        public String getAssetPack(String key) {
            return packs.get(key);
        }

        @Override
        public Set<String> getKeys(Path path) {
            return Set.of();
        }

        @Override
        public Set<String> getChildren(String key) {
            return Set.of();
        }

        @Override
        public int getAssetCount() {
            return packs.size();
        }

        @Override
        public Map<String, T> getAssetMap() {
            return Map.of();
        }

        @Override
        public Map<String, Path> getPathMap(String pack) {
            return Map.of();
        }

        @Override
        public Set<String> getKeysForTag(int tag) {
            return Set.of();
        }

        @Override
        public it.unimi.dsi.fastutil.ints.IntSet getTagIndexes() {
            return it.unimi.dsi.fastutil.ints.IntSets.emptySet();
        }

        @Override
        public int getTagCount() {
            return 0;
        }

        @Override
        protected void clear() {
        }

        @Override
        protected void putAll(String pack,
                              com.hypixel.hytale.assetstore.codec.AssetCodec<String, T> codec,
                              Map<String, T> assets,
                              Map<String, Path> paths,
                              Map<String, Set<String>> children) {
        }

        @Override
        protected Set<String> remove(Set<String> keys) {
            return Set.of();
        }

        @Override
        protected Set<String> remove(String pack, Set<String> keys, List<Map.Entry<String, Object>> replacements) {
            return Set.of();
        }

        @Override
        public Set<String> getKeysForPack(String pack) {
            return Set.of();
        }
    }
}
