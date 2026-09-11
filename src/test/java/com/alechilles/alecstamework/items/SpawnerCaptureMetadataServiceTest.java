package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnerCaptureMetadataServiceTest {
    @Test
    void reloadReplacesThenRemovesCachedCompanionIcons() throws Exception {
        try (var assets = new DynamicIconTestAssets("""
                {"RoleIds":["Sheep"],"IconDefault":"before.png"}
                """)) {
            var service = new SpawnerCaptureMetadataService(null, null);
            var item = ItemFeatureConfig.builder().spawnerIconDefault("lantern.png").build();
            assertEquals("before.png", service.resolveFullItemIcon(item, null, "lantern", "Sheep"));
            assets.replace("""
                    {"RoleIds":["Sheep"],"IconDefault":"after.png"}
                    """);
            assertEquals("after.png", service.resolveFullItemIcon(item, null, "lantern", "Sheep"));
            assets.replace("{\"RoleIds\":[]}");
            assertEquals("lantern.png", service.resolveFullItemIcon(item, null, "lantern", "Sheep"));
        }
    }

    @Test
    void captureUsesSharedVariantsAndKeepsItsOwnFallbackForUnmappedRoles() throws Exception {
        try (var assets = new DynamicIconTestAssets("""
                {"RoleIds":["Sheep","Tamed_Sheep"],"IconDefault":"sheep.png",
                 "IconOverrides":[{"Icon":"sheep-black.png","Attachments":{"Coat":"Black"}}]}
                """)) {
            var service = new SpawnerCaptureMetadataService(null, null);
            var item = ItemFeatureConfig.builder().spawnerIconDefault("lantern.png").build();
            assertEquals("sheep-black.png", service.resolveFullItemIcon(
                    item, "{\"Coat\":\"Black\"}", "lantern", "Tamed_Sheep"));
            assertEquals("sheep.png", service.resolveFullItemIcon(item, null, "lantern", "Sheep"));
            assertEquals("lantern.png", service.resolveFullItemIcon(item, null, "lantern", "Unknown"));
        }
    }
}
