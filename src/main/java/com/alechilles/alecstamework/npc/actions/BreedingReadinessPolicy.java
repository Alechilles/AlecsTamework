package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Defines which readiness marker is accepted while resolving a breeding partner.
 */
final class BreedingReadinessPolicy {
    private final boolean manual;
    private final UUID manualPlayerUuid;
    private final long nowMs;

    private BreedingReadinessPolicy(boolean manual, UUID manualPlayerUuid, long nowMs) {
        this.manual = manual;
        this.manualPlayerUuid = manualPlayerUuid;
        this.nowMs = nowMs;
    }

    static BreedingReadinessPolicy passive(long nowMs) {
        return new BreedingReadinessPolicy(false, null, nowMs);
    }

    static BreedingReadinessPolicy manual(UUID playerUuid, long nowMs) {
        return new BreedingReadinessPolicy(true, playerUuid, nowMs);
    }

    long nowMs() {
        return nowMs;
    }

    boolean requiresBreedingEnabled() {
        return !manual;
    }

    @Nullable
    UUID manualPlayerUuid() {
        return manualPlayerUuid;
    }

    boolean accepts(TameworkBreedingComponent breeding) {
        if (breeding == null) {
            return false;
        }
        if (manual) {
            return breeding.isManualBreedingReadyFor(manualPlayerUuid, nowMs);
        }
        return breeding.isReady();
    }
}
