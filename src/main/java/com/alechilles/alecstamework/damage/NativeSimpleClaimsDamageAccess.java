package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Invokes the independently probed native SimpleClaims tamed-damage capability. */
@FunctionalInterface
interface NativeSimpleClaimsDamageAccess {
    @Nonnull
    SimpleClaimsBreedingBridge.DamageAccessResult evaluate(
            @Nullable String worldName,
            @Nullable Vector3d position,
            @Nullable UUID attackerPlayerUuid,
            @Nullable String allowDamagePermissionKey);
}
