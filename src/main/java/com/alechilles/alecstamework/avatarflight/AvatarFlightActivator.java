package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates command-driven avatar-flight activation and cleanup.
 */
public final class AvatarFlightActivator {
    private final AvatarFlightModelService modelService = new AvatarFlightModelService();
    private final AvatarFlightRiderVisualService riderVisualService = new AvatarFlightRiderVisualService();

    @Nonnull
    public Result enable(@Nonnull Store<EntityStore> store,
                         @Nonnull Ref<EntityStore> ref,
                         @Nonnull UUID playerUuid,
                         @Nullable String configId) {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(configId);
        if (!config.isEnabled()) {
            return Result.fail("Avatar flight config is disabled: " + safeConfigId(config));
        }
        boolean applyModel = config.getModel().isApplyModel();
        if (applyModel && !modelService.apply(store, ref, playerUuid, config)) {
            return Result.fail("Avatar flight model asset not found: " + config.getModel().getModelId());
        }
        if (applyModel) {
            riderVisualService.spawn(store, ref, playerUuid, config, modelService.savedModelCopy(playerUuid));
        }
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType = AvatarFlightInputComponent.getComponentType();
        if (flightType == null || inputType == null) {
            if (applyModel) {
                riderVisualService.remove(store, ref);
                modelService.restore(store, ref, playerUuid);
            }
            return Result.fail("Avatar flight component types are not registered.");
        }
        long enabledAtMs = System.currentTimeMillis();
        AvatarFlightComponent flight = new AvatarFlightComponent(config.getId(), enabledAtMs);
        flight.setVigourCharges(config.getVigour().getMaxCharges());
        flight.setLastVigourUpdateAtMs(enabledAtMs);
        flight.setVigourRechargeMode(AvatarFlightVigourService.RechargeMode.NONE.name());
        store.putComponent(ref, flightType, flight);
        AvatarFlightInputComponent input = store.getComponent(ref, inputType);
        if (input == null) {
            store.putComponent(ref, inputType, new AvatarFlightInputComponent());
        }
        AvatarFlightSessionRegistry.markActive(playerUuid);
        return Result.ok("Avatar flight enabled with config=" + safeConfigId(config)
                + " modelSwap=" + (applyModel ? config.getModel().getModelId() : "disabled"));
    }

    @Nonnull
    public Result disable(@Nonnull Store<EntityStore> store,
                          @Nonnull Ref<EntityStore> ref,
                          @Nonnull UUID playerUuid) {
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType = AvatarFlightInputComponent.getComponentType();
        AvatarFlightComponent flight = flightType == null ? null : store.getComponent(ref, flightType);
        boolean restoreEquipment = flight != null
                && TwAvatarFlightConfig.resolve(flight.getConfigId()).getRiderVisual().isHideOwnerEquipment();
        if (flightType != null) {
            store.tryRemoveComponent(ref, flightType);
        }
        if (inputType != null) {
            store.tryRemoveComponent(ref, inputType);
        }
        restoreClientFlyingState(store, ref);
        resetVisualPose(store, ref);
        clearForcedAnimations(store, ref);
        riderVisualService.remove(store, ref);
        if (restoreEquipment) {
            AvatarFlightEquipmentVisualSystem.restoreCurrentEquipment(ref, store);
        }
        AvatarFlightSessionRegistry.markInactive(playerUuid);
        AvatarFlightPacketInputCapture.clear(playerUuid);
        boolean hadSavedModel = modelService.hasSavedModel(playerUuid);
        boolean restored = !hadSavedModel || modelService.restore(store, ref, playerUuid);
        return restored
                ? Result.ok("Avatar flight disabled" + (hadSavedModel
                ? " and model restored." : "."))
                : Result.fail("Avatar flight disabled, but no saved model or skin fallback was available.");
    }

    private void restoreClientFlyingState(@Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> ref) {
        MovementStatesComponent component = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (component == null || component.getMovementStates() == null) {
            return;
        }
        Player.applyMovementStates(ref, new SavedMovementStates(false), component.getMovementStates(), store);
    }

    private void resetVisualPose(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null && transform.getRotation() != null) {
            transform.getRotation().setPitch(0.0f);
            transform.getRotation().setRoll(0.0f);
            store.putComponent(ref, TransformComponent.getComponentType(), transform);
        }
        HeadRotation headRotation = store.getComponent(ref, HeadRotation.getComponentType());
        if (headRotation != null && headRotation.getRotation() != null) {
            headRotation.getRotation().setPitch(0.0f);
            headRotation.getRotation().setRoll(0.0f);
            store.putComponent(ref, HeadRotation.getComponentType(), headRotation);
        }
    }

    private void clearForcedAnimations(@Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> ref) {
        AnimationUtils.stopAnimation(ref, AnimationSlot.Movement, true, store);
        AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, store);
        AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, store);
        AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, store);
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null || event.getPlayerRef().getUuid() == null) {
            return;
        }
        UUID playerUuid = event.getPlayerRef().getUuid();
        AvatarFlightClientFlightProbe.clear(playerUuid);
        AvatarFlightSessionRegistry.markDisconnecting(playerUuid);
        AvatarFlightPacketInputCapture.clear(playerUuid);
        modelService.clearSavedModel(playerUuid);
    }

    @Nonnull
    public Status status(@Nonnull Store<EntityStore> store,
                         @Nonnull Ref<EntityStore> ref,
                         @Nonnull UUID playerUuid) {
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType = AvatarFlightInputComponent.getComponentType();
        AvatarFlightComponent flight = flightType == null ? null : store.getComponent(ref, flightType);
        AvatarFlightInputComponent input = inputType == null ? null : store.getComponent(ref, inputType);
        return new Status(
                flight != null,
                flight == null ? "" : flight.getConfigId(),
                flight == null ? AvatarFlightMode.GROUNDED : flight.getMode(),
                flight == null ? 0.0 : flight.getVelocityX(),
                flight == null ? 0.0 : flight.getVelocityY(),
                flight == null ? 0.0 : flight.getVelocityZ(),
                input == null ? 0L : input.getLastInputAtMs(),
                modelService.savedModelId(playerUuid)
        );
    }

    private static String safeConfigId(@Nonnull TwAvatarFlightConfig config) {
        return config.getId() == null || config.getId().isBlank() ? "<default>" : config.getId();
    }

    public record Result(boolean ok, @Nonnull String message) {
        @Nonnull
        public static Result ok(@Nonnull String message) {
            return new Result(true, message);
        }

        @Nonnull
        public static Result fail(@Nonnull String message) {
            return new Result(false, message);
        }
    }

    public record Status(boolean active,
                         @Nonnull String configId,
                         @Nonnull AvatarFlightMode mode,
                         double velocityX,
                         double velocityY,
                         double velocityZ,
                         long lastInputAtMs,
                         @Nullable String savedModelId) {
    }
}
