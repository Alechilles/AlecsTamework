package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
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
 * Compatibility facade for manual and passive pairing entrypoints.
 *
 * <p>Planning, registration, scheduling, and delayed execution are delegated to focused services;
 * this class no longer owns a delayed callback or mutable reservation approximation.
 */
final class BreedingOffspringService {
    private static final long APPROACH_DELAY_MS = 500L;
    private static final long OFFSPRING_DELAY_AFTER_HEARTS_MS = 2200L;

    private final BreedingHytalePairingService pairingService;

    BreedingOffspringService(@Nonnull BreedingPartnerService partnerService) {
        this(partnerService, TameworkBreedingServices.shared());
    }

    BreedingOffspringService(@Nonnull BreedingPartnerService partnerService,
                             @Nonnull TameworkBreedingServices services) {
        HytaleBreedingJobScheduler scheduler = new HytaleBreedingJobScheduler(services.jobRegistry());
        BreedingHytaleJobRuntime runtime = new BreedingHytaleJobRuntime(services);
        BreedingJobExecutionService<BreedingHytaleJobRuntime.Context> executionService =
                new BreedingJobExecutionService<>(
                        services,
                        runtime,
                        scheduler,
                        OFFSPRING_DELAY_AFTER_HEARTS_MS
                );
        scheduler.bind(executionService::execute, executionService::failScheduledJob);
        BreedingPairingCoordinator coordinator = new BreedingPairingCoordinator(
                services,
                scheduler,
                APPROACH_DELAY_MS
        );
        this.pairingService = new BreedingHytalePairingService(
                partnerService, coordinator, services
        );
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config) {
        return pairingService.tryPassive(sourceRef, store, sourceBreeding, config);
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> ignored) {
        return tryCompletePairing(sourceRef, store, sourceBreeding, config);
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> ignoredClaims,
                               @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> ignoredPlayers) {
        return tryCompletePairing(sourceRef, store, sourceBreeding, config);
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> ignoredClaims,
                               @Nullable Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> ignoredPlayers,
                               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return pairingService.tryPassive(sourceRef, store, sourceBreeding, config);
    }

    boolean tryCompletePairing(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreedingPopulationSweepContext populationContext) {
        return pairingService.tryPassive(
                sourceRef,
                store,
                sourceBreeding,
                config,
                populationContext
        );
    }

    boolean tryCompletePairing(@Nullable Ref<EntityStore> sourceRef,
                               @Nullable Store<EntityStore> store,
                               @Nullable TameworkBreedingComponent sourceBreeding,
                               @Nullable TwBreedingConfig config,
                               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return pairingService.tryPassive(sourceRef, store, sourceBreeding, config);
    }

    boolean tryCompleteManualPairing(@Nullable Ref<EntityStore> sourceRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable TameworkBreedingComponent sourceBreeding,
                                     @Nullable TwBreedingConfig config,
                                     @Nonnull UUID playerUuid) {
        return pairingService.tryManual(sourceRef, store, sourceBreeding, config, playerUuid);
    }

    static boolean acceptsPartnerReadiness(@Nullable BreedingReadinessPolicy readinessPolicy,
                                           @Nullable TameworkBreedingComponent breeding) {
        if (breeding == null) {
            return false;
        }
        return readinessPolicy != null ? readinessPolicy.accepts(breeding) : breeding.isReady();
    }
}
