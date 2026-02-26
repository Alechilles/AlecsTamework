package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves movement targets for thirst/hunger needs and triggers hook-driven resource seeking behavior.
 */
final class CompanionNeedsResourceSeekService {
    private static final String SEEK_WATER_HOOK_ID = "Tamework.Needs.SeekWater.Start";
    private static final String SEEK_FOOD_HOOK_ID = "Tamework.Needs.SeekFood.Start";
    private static final String SEEK_WATER_STATE = "NeedsSeekWater";
    private static final String SEEK_FOOD_STATE = "NeedsSeekFood";
    private static final double TARGET_MATCH_EPSILON_SQ = 0.0625;

    private final CompanionNeedsEnvironmentService environmentService;

    CompanionNeedsResourceSeekService(@Nonnull CompanionNeedsEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    boolean tryScheduleSeek(@Nullable Ref<EntityStore> npcRef,
                            @Nullable Store<EntityStore> store,
                            @Nonnull TwNeedsConfig config,
                            double hunger,
                            double thirst) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        if (!passiveRefill.isResourceSeekEnabled()) {
            return false;
        }

        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        Vector3d npcPosition = resolvePosition(npcRef, store);

        NeedSeekCandidate waterCandidate = resolveWaterCandidate(
                npcRef,
                store,
                config,
                passiveRefill,
                npc,
                npcPosition,
                thirst
        );
        NeedSeekCandidate foodCandidate = resolveFoodCandidate(
                npcRef,
                store,
                config,
                passiveRefill,
                npc,
                npcPosition,
                hunger
        );
        NeedSeekCandidate selected = chooseCandidate(waterCandidate, foodCandidate);
        if (selected == null) {
            return false;
        }
        return applySeekDirective(npcRef, store, npc, selected);
    }

    @Nullable
    private NeedSeekCandidate resolveWaterCandidate(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nonnull TwNeedsConfig config,
                                                    @Nonnull TwNeedsConfig.PassiveRefillSettings passiveRefill,
                                                    @Nonnull NPCEntity npc,
                                                    @Nullable Vector3d npcPosition,
                                                    double thirst) {
        if (!passiveRefill.isNearbyWaterDrinkEnabled()) {
            return null;
        }
        double thirstRatio = resolveNeedRatio(
                thirst,
                config.getValues().getThirstMin(),
                config.getValues().getThirstMax()
        );
        if (thirstRatio > passiveRefill.getSeekWhenThirstBelowRatio()) {
            return null;
        }
        if (environmentService.isNearWater(npcRef, store, config)) {
            return null;
        }
        Vector3d target = environmentService.findNearestWaterDrinkingPosition(npcRef, store, config);
        if (target == null) {
            return null;
        }
        if (npcPosition != null && distanceSquared(npcPosition, target) <= TARGET_MATCH_EPSILON_SQ) {
            return null;
        }
        return new NeedSeekCandidate(
                SEEK_WATER_HOOK_ID,
                target,
                supportsState(npc, SEEK_WATER_STATE),
                1.0 - thirstRatio
        );
    }

    @Nullable
    private NeedSeekCandidate resolveFoodCandidate(@Nonnull Ref<EntityStore> npcRef,
                                                   @Nonnull Store<EntityStore> store,
                                                   @Nonnull TwNeedsConfig config,
                                                   @Nonnull TwNeedsConfig.PassiveRefillSettings passiveRefill,
                                                   @Nonnull NPCEntity npc,
                                                   @Nullable Vector3d npcPosition,
                                                   double hunger) {
        if (!passiveRefill.isNearbyContainerFeedEnabled()) {
            return null;
        }
        double hungerRatio = resolveNeedRatio(
                hunger,
                config.getValues().getHungerMin(),
                config.getValues().getHungerMax()
        );
        if (hungerRatio > passiveRefill.getSeekWhenHungerBelowRatio()) {
            return null;
        }
        Vector3d target = environmentService.findNearestFoodContainerPosition(npcRef, store, config);
        if (target == null) {
            return null;
        }
        if (npcPosition != null && distanceSquared(npcPosition, target) <= TARGET_MATCH_EPSILON_SQ) {
            return null;
        }
        return new NeedSeekCandidate(
                SEEK_FOOD_HOOK_ID,
                target,
                supportsState(npc, SEEK_FOOD_STATE),
                1.0 - hungerRatio
        );
    }

    @Nullable
    private static NeedSeekCandidate chooseCandidate(@Nullable NeedSeekCandidate waterCandidate,
                                                     @Nullable NeedSeekCandidate foodCandidate) {
        if (waterCandidate == null) {
            return foodCandidate;
        }
        if (foodCandidate == null) {
            return waterCandidate;
        }
        if (waterCandidate.deficitWeight() >= foodCandidate.deficitWeight()) {
            return waterCandidate;
        }
        return foodCandidate;
    }

    private static boolean applySeekDirective(@Nonnull Ref<EntityStore> npcRef,
                                              @Nonnull Store<EntityStore> store,
                                              @Nonnull NPCEntity npc,
                                              @Nonnull NeedSeekCandidate candidate) {
        if (candidate.supportsHookState()) {
            ComponentType<EntityStore, TameworkHookComponent> hookType = TameworkHookComponent.getComponentType();
            if (hookType != null) {
                TameworkHookComponent existing = store.getComponent(npcRef, hookType);
                if (matchesExistingTarget(existing, candidate.hookId(), candidate.target())) {
                    return true;
                }
                store.putComponent(
                        npcRef,
                        hookType,
                        new TameworkHookComponent(
                                candidate.hookId(),
                                null,
                                null,
                                null,
                                System.currentTimeMillis(),
                                true,
                                candidate.target()
                        )
                );
                return true;
            }
        }
        npc.moveTo(npcRef, candidate.target().x, candidate.target().y, candidate.target().z, store);
        return true;
    }

    private static boolean matchesExistingTarget(@Nullable TameworkHookComponent component,
                                                 @Nonnull String expectedHookId,
                                                 @Nonnull Vector3d expectedTarget) {
        if (component == null || !component.matchesHook(expectedHookId) || !component.hasTargetPosition()) {
            return false;
        }
        Vector3d existingTarget = component.getTargetPosition();
        if (existingTarget == null) {
            return false;
        }
        return distanceSquared(existingTarget, expectedTarget) <= TARGET_MATCH_EPSILON_SQ;
    }

    private static boolean supportsState(@Nonnull NPCEntity npc, @Nonnull String stateName) {
        Role role = npc.getRole();
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return false;
        }
        int stateIndex = role.getStateSupport().getStateHelper().getStateIndex(stateName);
        return stateIndex != StateSupport.NO_STATE;
    }

    @Nullable
    private static Vector3d resolvePosition(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return new Vector3d(transform.getPosition());
    }

    private static double resolveNeedRatio(double value, double min, double max) {
        double range = max - min;
        if (!Double.isFinite(range) || range <= 0.0) {
            return 1.0;
        }
        double clamped = clamp(value, min, max);
        return clamp((clamped - min) / range, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double distanceSquared(@Nonnull Vector3d left, @Nonnull Vector3d right) {
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        double dz = left.z - right.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private record NeedSeekCandidate(String hookId,
                                     Vector3d target,
                                     boolean supportsHookState,
                                     double deficitWeight) {
    }
}
