package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwDynamicIconConfig;
import java.util.Map;
import javax.annotation.Nullable;

/** Applies the shared companion icon before the filled item's own fallback. */
final class SpawnerIconResolver {
    private SpawnerIconResolver() {
    }

    @Nullable
    static String resolveFullItemIcon(@Nullable ItemFeatureConfig config,
                                      @Nullable Map<String, String> attachments,
                                      @Nullable String roleId) {
        String icon = TwDynamicIconConfig.resolveIcon(roleId, attachments);
        return icon != null ? icon : config == null ? null : config.getSpawnerIconDefault();
    }
}
