package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility facade for the released manual and passive breeding entrypoints.
 *
 * <p>Pairing now uses only live world state. It does not create durable birth jobs,
 * durable capacity reservations, replay journals, or a second population authority.
 * Passive sweeps retain only transient headroom counters for their current pass.
 */
final class BreedingOffspringService {
    private final BreedingHytalePairingService pairingService;

    BreedingOffspringService(
            @Nonnull BreedingPartnerService partnerService,
            @Nonnull BreedingPairAdmissionRegistry admissionRegistry
    ) {
        this.pairingService = new BreedingHytalePairingService(
                partnerService, admissionRegistry
        );
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nonnull Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                                       pendingClaims,
                               @Nonnull Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                                       pendingOwners,
                               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return pairingService.tryPassive(
                sourceRef,
                store,
                sourceBreeding,
                config,
                pendingClaims,
                pendingOwners,
                commandBuffer
        );
    }

    boolean tryCompleteManualPairing(@Nullable Ref<EntityStore> sourceRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable TameworkBreedingComponent sourceBreeding,
                                     @Nullable TwBreedingConfig config,
                                     @Nonnull UUID playerUuid) {
        return pairingService.tryManual(
                sourceRef,
                store,
                sourceBreeding,
                config,
                playerUuid
        );
    }

    static boolean acceptsPartnerReadiness(
            @Nullable BreedingReadinessPolicy readinessPolicy,
            @Nullable TameworkBreedingComponent breeding
    ) {
        if (breeding == null) {
            return false;
        }
        return readinessPolicy != null
                ? readinessPolicy.accepts(breeding)
                : breeding.isReady();
    }
}
