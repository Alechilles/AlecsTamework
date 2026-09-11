package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import java.util.Map;
import javax.annotation.Nullable;

/** Resolves a static NPC portrait from registered capture-icon role variants. */
final class CommandNpcPortraitResolver {
    private CommandNpcPortraitResolver() {
    }

    @Nullable
    static String resolve(@Nullable ItemFeatureRegistry registry,
                          @Nullable String roleId,
                          @Nullable Map<String, String> attachments) {
        if (registry == null || roleId == null || roleId.isBlank()) {
            return null;
        }

        Map<String, ItemFeatureConfig> configs = registry.snapshot();
        for (ItemFeatureConfig config : configs.values()) {
            String icon = SpawnerIconResolver.resolveRolePortraitVariantIcon(config, roleId, attachments);
            if (icon != null && !icon.isBlank()) {
                return icon;
            }
        }
        for (ItemFeatureConfig config : configs.values()) {
            String icon = SpawnerIconResolver.resolveRolePortraitDefaultIcon(config, roleId);
            if (icon != null && !icon.isBlank()) {
                return icon;
            }
        }
        return null;
    }
}
