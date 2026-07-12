package com.alechilles.alecstamework.damage;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Effective owner-specific damage controls for one target.
 */
public record TamedDamageOwnerPolicy(@Nullable UUID ownerUuid,
                                     boolean blockOwnerDamage,
                                     boolean blockAllPlayerDamageIfOwned,
                                     boolean invulnerableIfOwned) {
    private static final TamedDamageOwnerPolicy UNOWNED = new TamedDamageOwnerPolicy(
            null,
            false,
            false,
            false
    );

    @Nonnull
    public static TamedDamageOwnerPolicy unowned() {
        return UNOWNED;
    }
}
