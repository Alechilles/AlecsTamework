package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Effective, bounded source-companion modifiers for avatar flight.
 */
public record AvatarFlightProgressionTuning(double vigourCapacityMultiplier,
                                            double vigourRechargeRateMultiplier,
                                            double forwardBoostCostMultiplier,
                                            double forwardBoostImpulseMultiplier,
                                            double glideSinkMultiplier,
                                            double climbLiftMultiplier) {
    public static final String VIGOUR_CAPACITY_EFFECT = "AvatarFlightVigourCapacityMultiplier";
    public static final String VIGOUR_RECHARGE_RATE_EFFECT = "AvatarFlightVigourRechargeRateMultiplier";
    public static final String FORWARD_BOOST_COST_EFFECT = "AvatarFlightForwardBoostCostMultiplier";
    public static final String FORWARD_BOOST_IMPULSE_EFFECT = "AvatarFlightForwardBoostImpulseMultiplier";
    public static final String GLIDE_SINK_EFFECT = "AvatarFlightGlideSinkMultiplier";
    public static final String CLIMB_LIFT_EFFECT = "AvatarFlightClimbLiftMultiplier";

    public AvatarFlightProgressionTuning {
        vigourCapacityMultiplier = clamp(vigourCapacityMultiplier, 1.0, 1.35);
        vigourRechargeRateMultiplier = clamp(vigourRechargeRateMultiplier, 1.0, 1.35);
        forwardBoostCostMultiplier = clamp(forwardBoostCostMultiplier, 0.70, 1.0);
        forwardBoostImpulseMultiplier = clamp(forwardBoostImpulseMultiplier, 1.0, 1.25);
        glideSinkMultiplier = clamp(glideSinkMultiplier, 0.70, 1.0);
        climbLiftMultiplier = clamp(climbLiftMultiplier, 1.0, 1.25);
    }

    public static AvatarFlightProgressionTuning neutral() {
        return new AvatarFlightProgressionTuning(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }

    public static AvatarFlightProgressionTuning resolve(@Nullable Ref<EntityStore> sourceRef,
                                                         @Nullable Store<EntityStore> store) {
        return new AvatarFlightProgressionTuning(
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, VIGOUR_CAPACITY_EFFECT, 1.0d),
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, VIGOUR_RECHARGE_RATE_EFFECT, 1.0d),
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, FORWARD_BOOST_COST_EFFECT, 1.0d),
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, FORWARD_BOOST_IMPULSE_EFFECT, 1.0d),
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, GLIDE_SINK_EFFECT, 1.0d),
                CompanionProgressionModifierService.resolveMultiplier(
                        sourceRef, store, CLIMB_LIFT_EFFECT, 1.0d)
        );
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
