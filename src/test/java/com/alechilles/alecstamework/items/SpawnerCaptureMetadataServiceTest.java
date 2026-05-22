package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpawnerCaptureMetadataServiceTest {

    @Test
    void captureInfoCarriesCapturedModelIdForMetadataWriters() throws Exception {
        SpawnerCaptureMetadataService.CaptureInfo captureInfo = captureInfo("Model_Cat");

        assertEquals("Model_Cat", captureInfo.modelId());
    }

    @Test
    void exactRoleIconOverrideWinsBeforeGroupOverride() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverridesByRole(Map.of(
                        "Camel",
                        List.of(override("BaseColor", "0", "exact.png"))
                ))
                .spawnerIconOverrideGroups(List.of(
                        group(List.of("Camel", "Tamed_Camel"), override("BaseColor", "0", "group.png"))
                ))
                .spawnerIconOverrides(List.of(override("BaseColor", "0", "global.png")))
                .build();

        assertEquals("exact.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"0\"}", "item", "Camel"));
    }

    @Test
    void exactRoleIconOverrideFallsThroughToGroupWhenAttachmentsDoNotMatch() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverridesByRole(Map.of(
                        "Camel",
                        List.of(override("BaseColor", "1", "exact.png"))
                ))
                .spawnerIconOverrideGroups(List.of(
                        group(List.of("Camel", "Tamed_Camel"), override("BaseColor", "0", "group.png"))
                ))
                .build();

        assertEquals("group.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"0\"}", "item", "Camel"));
    }

    @Test
    void tamedRoleUsesSharedGroupWhenNoExactRoleOverridesExist() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverrideGroups(List.of(
                        group(List.of("Camel", "Tamed_Camel"), override("Shell", "Blue", "shared.png"))
                ))
                .build();

        assertEquals("shared.png", service.resolveFullItemIcon(config, "{\"Shell\":\"Blue\"}", "item", "Tamed_Camel"));
    }

    @Test
    void firstSharedRoleGroupWinsAndLaterGroupsAreIgnoredForSameRole() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverrideGroups(List.of(
                        group(List.of("Camel"), override("BaseColor", "1", "first-group.png")),
                        group(List.of("Camel"), override("BaseColor", "0", "second-group.png"))
                ))
                .spawnerIconOverrides(List.of(override("BaseColor", "0", "global.png")))
                .build();

        assertEquals("global.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"0\"}", "item", "Camel"));
    }

    @Test
    void globalIconOverrideAndDefaultFallbackStillApply() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverrides(List.of(override("BaseColor", "0", "global.png")))
                .build();

        assertEquals("global.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"0\"}", "item", "Camel"));
        assertEquals("default.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"1\"}", "item", "Camel"));
    }

    @Test
    void sharedGroupDefaultCoversBaseOnlyRoleWithoutAttachments() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverrideGroups(List.of(
                        group(List.of("Cow", "Tamed_Cow"), List.of(), "cow-base.png")
                ))
                .build();

        assertEquals("cow-base.png", service.resolveFullItemIcon(config, null, "item", "Tamed_Cow"));
        assertEquals("cow-base.png", service.resolveFullItemIcon(config, "{}", "item", "Cow"));
    }

    @Test
    void sharedGroupDefaultWinsBeforeGlobalFallbackAfterGroupMiss() {
        SpawnerCaptureMetadataService service = new SpawnerCaptureMetadataService(null, null);
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerIconDefault("default.png")
                .spawnerIconOverrideGroups(List.of(
                        group(
                                List.of("Cow", "Tamed_Cow"),
                                List.of(override("Fleece", "White", "cow-white.png")),
                                "cow-base.png"
                        )
                ))
                .spawnerIconOverrides(List.of(override("BaseColor", "0", "global.png")))
                .build();

        assertEquals("cow-base.png", service.resolveFullItemIcon(config, "{\"BaseColor\":\"0\"}", "item", "Cow"));
        assertEquals("cow-white.png", service.resolveFullItemIcon(config, "{\"Fleece\":\"White\"}", "item", "Cow"));
    }

    private static SpawnerCaptureMetadataService.CaptureInfo captureInfo(String modelId) throws Exception {
        Constructor<SpawnerCaptureMetadataService.CaptureInfo> constructor =
                SpawnerCaptureMetadataService.CaptureInfo.class.getDeclaredConstructor(
                        String.class,
                        String.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        SpawnerCaptureMetadataService.CapturedName.class,
                        String.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(null, modelId, null, null, null, null, null, null);
    }

    private static ItemFeatureConfig.SpawnerIconOverride override(String key, String value, String icon) {
        return new ItemFeatureConfig.SpawnerIconOverride(Map.of(key, value), icon);
    }

    private static ItemFeatureConfig.SpawnerIconOverrideGroup group(
            List<String> roles,
            ItemFeatureConfig.SpawnerIconOverride override) {
        return new ItemFeatureConfig.SpawnerIconOverrideGroup(roles, List.of(override));
    }

    private static ItemFeatureConfig.SpawnerIconOverrideGroup group(
            List<String> roles,
            List<ItemFeatureConfig.SpawnerIconOverride> overrides,
            String iconDefault) {
        return new ItemFeatureConfig.SpawnerIconOverrideGroup(roles, overrides, iconDefault);
    }
}
