package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import javax.annotation.Nullable;

/** Prevents null or already-cancelled damage events from entering Tamework policy evaluation. */
final class DamagePolicyEventGate {
    private DamagePolicyEventGate() {
    }

    static boolean shouldSkip(@Nullable Damage damage) {
        return damage == null || damage.isCancelled();
    }
}
