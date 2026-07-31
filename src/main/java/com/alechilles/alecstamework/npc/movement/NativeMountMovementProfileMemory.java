package com.alechilles.alecstamework.npc.movement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Retains each source role's movement profile while native mounting replaces its live role with Empty_Role.
 *
 * <p>The mapping is intentionally process-local: movement profiles are static role parameters and the native
 * mount component cannot carry arbitrary extension data across the role change.</p>
 */
final class NativeMountMovementProfileMemory {
    private final Map<String, String> profilesBySourceRole = new ConcurrentHashMap<>();

    void remember(@Nullable String sourceRoleId, @Nullable String movementConfigId) {
        String role = normalize(sourceRoleId);
        String profile = normalize(movementConfigId);
        if (role.isEmpty() || profile.isEmpty()) {
            return;
        }
        profilesBySourceRole.put(role, profile);
    }

    @Nonnull
    String resolve(@Nullable String sourceRoleId, @Nullable String resolvedFromScope) {
        String role = normalize(sourceRoleId);
        String remembered = role.isEmpty() ? null : profilesBySourceRole.get(role);
        if (remembered != null && !remembered.isEmpty()) {
            return remembered;
        }
        String scoped = normalize(resolvedFromScope);
        return scoped.isEmpty() ? NativeMountMovementSettingsService.DEFAULT_MOUNT_MOVEMENT_CONFIG_ID : scoped;
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
