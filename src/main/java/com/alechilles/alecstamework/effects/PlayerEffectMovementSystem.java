package com.alechilles.alecstamework.effects;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class PlayerEffectMovementSystem extends TickingSystem<EntityStore> {
    private static final float EPSILON = 0.0001F;

    private final ComponentType<EntityStore, PlayerRef> playerRefType;
    private final ComponentType<EntityStore, MovementManager> movementManagerType;
    private final ComponentType<EntityStore, EffectControllerComponent> effectControllerType;

    public PlayerEffectMovementSystem(ComponentType<EntityStore, PlayerRef> playerRefType,
                                      ComponentType<EntityStore, MovementManager> movementManagerType,
                                      ComponentType<EntityStore, EffectControllerComponent> effectControllerType) {
        this.playerRefType = playerRefType;
        this.movementManagerType = movementManagerType;
        this.effectControllerType = effectControllerType;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (playerRefType == null || movementManagerType == null || effectControllerType == null) {
            return;
        }

        store.forEachChunk(
                Query.and(playerRefType, movementManagerType, effectControllerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                        if (playerRef == null || !playerRef.isValid()) {
                            continue;
                        }

                        PlayerRef playerRefComponent = chunk.getComponent(i, playerRefType);
                        MovementManager movementManager = chunk.getComponent(i, movementManagerType);
                        EffectControllerComponent effectController = chunk.getComponent(i, effectControllerType);
                        if (playerRefComponent == null || playerRefComponent.getPacketHandler() == null || movementManager == null) {
                            continue;
                        }

                        MovementSettings defaults = movementManager.getDefaultSettings();
                        MovementSettings current = movementManager.getSettings();
                        if (defaults == null || current == null) {
                            continue;
                        }

                        float multiplier = TameworkEntityEffectService.resolveHorizontalSpeedMultiplier(effectController);
                        if (!needsUpdate(defaults, current, multiplier)) {
                            continue;
                        }

                        movementManager.applyDefaultSettings();
                        MovementSettings adjusted = movementManager.getSettings();
                        applyHorizontalSpeedMultiplier(adjusted, multiplier);
                        movementManager.update(playerRefComponent.getPacketHandler());
                        commandBuffer.putComponent(playerRef, movementManagerType, movementManager);
                    }
                }
        );
    }

    private static boolean needsUpdate(@Nonnull MovementSettings defaults,
                                       @Nonnull MovementSettings current,
                                       float multiplier) {
        return differs(current.baseSpeed, defaults.baseSpeed * multiplier)
                || differs(current.airSpeedMultiplier, defaults.airSpeedMultiplier * multiplier)
                || differs(current.horizontalFlySpeed, defaults.horizontalFlySpeed * multiplier)
                || differs(current.climbSpeedLateral, defaults.climbSpeedLateral * multiplier)
                || differs(current.forwardWalkSpeedMultiplier, defaults.forwardWalkSpeedMultiplier * multiplier)
                || differs(current.backwardWalkSpeedMultiplier, defaults.backwardWalkSpeedMultiplier * multiplier)
                || differs(current.strafeWalkSpeedMultiplier, defaults.strafeWalkSpeedMultiplier * multiplier)
                || differs(current.forwardRunSpeedMultiplier, defaults.forwardRunSpeedMultiplier * multiplier)
                || differs(current.backwardRunSpeedMultiplier, defaults.backwardRunSpeedMultiplier * multiplier)
                || differs(current.strafeRunSpeedMultiplier, defaults.strafeRunSpeedMultiplier * multiplier)
                || differs(current.forwardCrouchSpeedMultiplier, defaults.forwardCrouchSpeedMultiplier * multiplier)
                || differs(current.backwardCrouchSpeedMultiplier, defaults.backwardCrouchSpeedMultiplier * multiplier)
                || differs(current.strafeCrouchSpeedMultiplier, defaults.strafeCrouchSpeedMultiplier * multiplier)
                || differs(current.forwardSprintSpeedMultiplier, defaults.forwardSprintSpeedMultiplier * multiplier);
    }

    private static void applyHorizontalSpeedMultiplier(@Nonnull MovementSettings settings, float multiplier) {
        settings.baseSpeed *= multiplier;
        settings.airSpeedMultiplier *= multiplier;
        settings.horizontalFlySpeed *= multiplier;
        settings.climbSpeedLateral *= multiplier;
        settings.forwardWalkSpeedMultiplier *= multiplier;
        settings.backwardWalkSpeedMultiplier *= multiplier;
        settings.strafeWalkSpeedMultiplier *= multiplier;
        settings.forwardRunSpeedMultiplier *= multiplier;
        settings.backwardRunSpeedMultiplier *= multiplier;
        settings.strafeRunSpeedMultiplier *= multiplier;
        settings.forwardCrouchSpeedMultiplier *= multiplier;
        settings.backwardCrouchSpeedMultiplier *= multiplier;
        settings.strafeCrouchSpeedMultiplier *= multiplier;
        settings.forwardSprintSpeedMultiplier *= multiplier;
    }

    private static boolean differs(float current, float desired) {
        return Math.abs(current - desired) > EPSILON;
    }
}
