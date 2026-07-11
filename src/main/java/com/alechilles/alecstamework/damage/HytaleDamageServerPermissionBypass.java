package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Registers and checks Tamework's configurable damage bypass as a Hytale server permission.
 */
final class HytaleDamageServerPermissionBypass implements DamageServerPermissionBypass {
    private final Set<String> registeredPermissionKeys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isGranted(@Nonnull UUID attackerPlayerUuid, @Nonnull String permissionKey) {
        String normalized = permissionKey.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (registeredPermissionKeys.add(normalized)) {
            PermissionsModule.registerPermission(normalized);
        }
        PermissionsModule permissions = PermissionsModule.get();
        return permissions != null && permissions.hasPermission(attackerPlayerUuid, normalized);
    }
}
