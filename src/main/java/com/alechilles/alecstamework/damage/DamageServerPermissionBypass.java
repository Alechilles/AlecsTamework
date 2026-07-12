package com.alechilles.alecstamework.damage;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Checks the configured server-wide permission bypass for an attributed attacker. */
@FunctionalInterface
interface DamageServerPermissionBypass {
    boolean isGranted(@Nonnull UUID attackerPlayerUuid, @Nonnull String permissionKey);
}
