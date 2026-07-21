package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable API view of one spawner's vessel behavior and state-item mapping. */
public record SpawnerVesselConfigView(@Nonnull String configId,
                                     long configRevision,
                                     @Nonnull BondedVesselMode mode,
                                     @Nullable String emptyItemId,
                                     @Nullable String storedItemId,
                                     @Nullable String activeItemId,
                                     @Nullable String deadItemId,
                                     @Nullable String lostItemId,
                                     @Nullable String unavailableItemId,
                                     long transitionCooldownMs,
                                     double storeMaxDistance,
                                     @Nullable String storeParticleSystem,
                                     @Nullable String storeSoundEvent,
                                     boolean requireOwner,
                                     boolean allowStoreInCombat) {
    public SpawnerVesselConfigView {
        configId = Objects.requireNonNull(configId, "configId").trim();
        mode = Objects.requireNonNull(mode, "mode");
        emptyItemId = normalizeBlank(emptyItemId);
        storedItemId = normalizeBlank(storedItemId);
        activeItemId = normalizeBlank(activeItemId);
        deadItemId = normalizeBlank(deadItemId);
        lostItemId = normalizeBlank(lostItemId);
        unavailableItemId = normalizeBlank(unavailableItemId);
        storeParticleSystem = normalizeBlank(storeParticleSystem);
        storeSoundEvent = normalizeBlank(storeSoundEvent);
        if (configId.isEmpty()) throw new IllegalArgumentException("configId is required.");
        if (configRevision < 0L || transitionCooldownMs < 0L
                || !Double.isFinite(storeMaxDistance) || storeMaxDistance < 0.0D) {
            throw new IllegalArgumentException("Vessel config revision and duration cannot be negative.");
        }
        if (mode == BondedVesselMode.BONDED && (emptyItemId == null || storedItemId == null)) {
            throw new IllegalArgumentException("Bonded vessel views require empty and stored item IDs.");
        }
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
