package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnerIconResolverTest {

    @Test
    void portraitUsesAttachmentSpecificRoleIconWithNormalizedRoleId() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("GenericCaptureItem", ItemFeatureConfig.builder()
                .spawnerIconDefault("generic-lantern.png")
                .spawnerIconOverrideGroups(List.of(new ItemFeatureConfig.SpawnerIconOverrideGroup(
                        List.of("Tamed_Sheep"), List.of(), "sheep-base.png"
                )))
                .build());
        registry.register("SheepCaptureItem", ItemFeatureConfig.builder()
                .spawnerIconOverridesByRole(Map.of(
                        "Tamed_Sheep",
                        List.of(override("Coat", "Black", "sheep-black.png"))
                ))
                .build());

        assertEquals(
                "sheep-black.png",
                CommandNpcPortraitResolver.resolve(
                        registry, "tamed_sheep", Map.of("Coat", "Black")
                )
        );
    }

    @Test
    void portraitNeverUsesGenericCaptureIconWhenNoRoleVariantMatches() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("GenericCaptureItem", ItemFeatureConfig.builder()
                .spawnerIconDefault("generic-lantern.png")
                .spawnerIconOverrides(List.of(override("Coat", "Black", "generic-black.png")))
                .build());

        assertNull(CommandNpcPortraitResolver.resolve(
                registry, "tamed_sheep", Map.of("Coat", "Black")
        ));
    }

    @Test
    void portraitUsesRoleGroupDefaultWhenNoAttachmentVariantExists() {
        ItemFeatureRegistry registry = new ItemFeatureRegistry();
        registry.register("SheepCaptureItem", ItemFeatureConfig.builder()
                .spawnerIconOverrideGroups(List.of(new ItemFeatureConfig.SpawnerIconOverrideGroup(
                        List.of("Tamed_Sheep"),
                        List.of(),
                        "sheep-base.png"
                )))
                .build());

        assertEquals("sheep-base.png", CommandNpcPortraitResolver.resolve(
                registry, "tamed_sheep", Map.of()
        ));
    }

    @Test
    void captureKeepsExactRoleOverridePrecedence() {
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverridesByRole(Map.of(
                        "Sheep", List.of(override("Coat", "Black", "role.png"))
                ))
                .spawnerIconOverrideGroups(List.of(new ItemFeatureConfig.SpawnerIconOverrideGroup(
                        List.of("Sheep"),
                        List.of(override("Coat", "Black", "group.png")),
                        "group-default.png"
                )))
                .spawnerIconOverrides(List.of(override("Coat", "Black", "global.png")))
                .build();

        assertEquals("role.png", new SpawnerCaptureMetadataService(null, null)
                .resolveFullItemIcon(config, "{\"Coat\":\"Black\"}", "capture-item", "Sheep"));
    }

    private static ItemFeatureConfig.SpawnerIconOverride override(String key, String value, String icon) {
        return new ItemFeatureConfig.SpawnerIconOverride(Map.of(key, value), icon);
    }
}
