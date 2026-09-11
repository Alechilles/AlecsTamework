package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves configured spawner icons from an NPC role and random attachments.
 *
 * <p>The full-item route preserves the capture item's legacy role, group, global, then
 * default precedence. The portrait route deliberately accepts only role-scoped entries so a
 * generic capture-item icon is never presented as an NPC portrait.</p>
 */
final class SpawnerIconResolver {
    private SpawnerIconResolver() {
    }

    @Nullable
    static String resolveFullItemIcon(@Nullable ItemFeatureConfig config,
                                      @Nullable Map<String, String> attachments,
                                      @Nullable String roleId) {
        if (config == null) {
            return null;
        }
        String defaultIcon = config.getSpawnerIconDefault();
        List<ItemFeatureConfig.SpawnerIconOverride> roleOverrides = roleOverrides(config, roleId);
        ItemFeatureConfig.SpawnerIconOverrideGroup roleGroup = firstGroupForRole(
                config.getSpawnerIconOverrideGroups(), roleId, false
        );
        List<ItemFeatureConfig.SpawnerIconOverride> groupOverrides =
                roleGroup != null ? roleGroup.getOverrides() : null;
        String groupDefaultIcon = roleGroup != null ? roleGroup.getIconDefault() : null;
        List<ItemFeatureConfig.SpawnerIconOverride> globalOverrides = config.getSpawnerIconOverrides();
        boolean hasRoleOverrides = roleOverrides != null && !roleOverrides.isEmpty();
        boolean hasGroupOverrides = groupOverrides != null && !groupOverrides.isEmpty();
        boolean hasGlobalOverrides = globalOverrides != null && !globalOverrides.isEmpty();
        boolean hasGroupDefaultIcon = hasText(groupDefaultIcon);

        if (!hasRoleOverrides && !hasGroupOverrides && !hasGlobalOverrides) {
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }
        if (attachments == null || attachments.isEmpty()) {
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }

        ItemFeatureConfig.SpawnerIconOverride roleOverride = firstMatchingOverride(roleOverrides, attachments);
        if (roleOverride != null) {
            return roleOverride.getIcon();
        }
        ItemFeatureConfig.SpawnerIconOverride groupOverride = firstMatchingOverride(groupOverrides, attachments);
        if (groupOverride != null) {
            return groupOverride.getIcon();
        }
        if (hasGroupDefaultIcon) {
            return groupDefaultIcon;
        }
        ItemFeatureConfig.SpawnerIconOverride globalOverride = firstMatchingOverride(globalOverrides, attachments);
        return globalOverride != null ? globalOverride.getIcon() : defaultIcon;
    }

    /**
     * Resolves only a role-specific portrait icon. Generic spawner defaults and global
     * overrides belong to the capture item, rather than to the NPC represented by it.
     */
    @Nullable
    static String resolveRolePortraitIcon(@Nullable ItemFeatureConfig config,
                                          @Nullable String roleId,
                                          @Nullable Map<String, String> attachments) {
        if (config == null || !hasText(roleId)) {
            return null;
        }
        String variantIcon = resolveRolePortraitVariantIcon(config, roleId, attachments);
        return variantIcon != null ? variantIcon : resolveRolePortraitDefaultIcon(config, roleId);
    }

    @Nullable
    static String resolveRolePortraitVariantIcon(@Nullable ItemFeatureConfig config,
                                                 @Nullable String roleId,
                                                 @Nullable Map<String, String> attachments) {
        if (config == null || !hasText(roleId) || attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<ItemFeatureConfig.SpawnerIconOverride> roleOverrides = roleOverridesIgnoreCase(config, roleId);
        ItemFeatureConfig.SpawnerIconOverrideGroup roleGroup = firstGroupForRole(
                config.getSpawnerIconOverrideGroups(), roleId, true
        );
        List<ItemFeatureConfig.SpawnerIconOverride> groupOverrides =
                roleGroup != null ? roleGroup.getOverrides() : null;
        ItemFeatureConfig.SpawnerIconOverride roleOverride = firstMatchingOverride(roleOverrides, attachments);
        if (roleOverride != null && hasText(roleOverride.getIcon())) {
            return roleOverride.getIcon();
        }
        ItemFeatureConfig.SpawnerIconOverride groupOverride = firstMatchingOverride(groupOverrides, attachments);
        return groupOverride != null && hasText(groupOverride.getIcon()) ? groupOverride.getIcon() : null;
    }

    @Nullable
    static String resolveRolePortraitDefaultIcon(@Nullable ItemFeatureConfig config,
                                                 @Nullable String roleId) {
        if (config == null || !hasText(roleId)) {
            return null;
        }
        ItemFeatureConfig.SpawnerIconOverrideGroup roleGroup = firstGroupForRole(
                config.getSpawnerIconOverrideGroups(), roleId, true
        );
        String groupDefaultIcon = roleGroup != null ? roleGroup.getIconDefault() : null;
        return hasText(groupDefaultIcon) ? groupDefaultIcon : null;
    }

    @Nullable
    private static List<ItemFeatureConfig.SpawnerIconOverride> roleOverrides(
            ItemFeatureConfig config,
            @Nullable String roleId) {
        if (!hasText(roleId)) {
            return null;
        }
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> overridesByRole =
                config.getSpawnerIconOverridesByRole();
        return overridesByRole == null || overridesByRole.isEmpty() ? null : overridesByRole.get(roleId);
    }

    @Nullable
    private static List<ItemFeatureConfig.SpawnerIconOverride> roleOverridesIgnoreCase(
            ItemFeatureConfig config,
            String roleId) {
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> overridesByRole =
                config.getSpawnerIconOverridesByRole();
        if (overridesByRole == null || overridesByRole.isEmpty()) {
            return null;
        }
        String normalizedRole = normalizeRole(roleId);
        for (Map.Entry<String, List<ItemFeatureConfig.SpawnerIconOverride>> entry : overridesByRole.entrySet()) {
            if (normalizedRole.equals(normalizeRole(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nullable
    private static ItemFeatureConfig.SpawnerIconOverrideGroup firstGroupForRole(
            @Nullable List<ItemFeatureConfig.SpawnerIconOverrideGroup> groups,
            @Nullable String roleId,
            boolean ignoreCase) {
        if (!hasText(roleId) || groups == null || groups.isEmpty()) {
            return null;
        }
        String normalizedRole = ignoreCase ? normalizeRole(roleId) : roleId;
        for (ItemFeatureConfig.SpawnerIconOverrideGroup group : groups) {
            if (group == null || group.getRoles().isEmpty()) {
                continue;
            }
            for (String groupRole : group.getRoles()) {
                if (ignoreCase ? normalizedRole.equals(normalizeRole(groupRole)) : roleId.equals(groupRole)) {
                    return group;
                }
            }
        }
        return null;
    }

    @Nullable
    private static ItemFeatureConfig.SpawnerIconOverride firstMatchingOverride(
            @Nullable List<ItemFeatureConfig.SpawnerIconOverride> overrides,
            Map<String, String> attachments) {
        if (overrides == null || overrides.isEmpty()) {
            return null;
        }
        for (ItemFeatureConfig.SpawnerIconOverride override : overrides) {
            if (override != null && matchesAttachments(override.getAttachments(), attachments)) {
                return override;
            }
        }
        return null;
    }

    private static boolean matchesAttachments(@Nullable Map<String, String> required,
                                              @Nullable Map<String, String> actual) {
        if (required == null || required.isEmpty() || actual == null || actual.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            String value = actual.get(entry.getKey());
            if (value == null || !value.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeRole(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim().toLowerCase(Locale.ROOT);
    }
}
