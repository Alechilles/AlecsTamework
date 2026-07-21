package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.ui.TameworkAvatarFlightHud;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
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
    private final AvatarFlightInventoryGuardService inventoryGuard = new AvatarFlightInventoryGuardService();

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
        inventoryGuard.engage(store, ref, flight);
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
        Player player = store.getComponent(ref, Player.getComponentType());
        TwAvatarFlightConfig config = flight == null ? null : TwAvatarFlightConfig.resolve(flight.getConfigId());
        boolean restoreEquipment = config != null && config.getRiderVisual().isHideOwnerEquipment();
        boolean restoreModel = config != null && config.getModel().isApplyModel();
        AvatarFlightTrailService.stopFastGlideTrail(flight, ref, store);
        inventoryGuard.disengage(store, ref, flight);
        if (flightType != null) {
            store.tryRemoveComponent(ref, flightType);
        }
        TameworkAvatarFlightHud.removeFrom(player);
        if (inputType != null) {
            store.tryRemoveComponent(ref, inputType);
        }
        restoreClientFlyingState(store, ref);
        resetVisualPose(store, ref);
        clearForcedAnimations(store, ref);
        riderVisualService.remove(store, ref);
        AvatarFlightSessionRegistry.markInactive(playerUuid);
        AvatarFlightPacketInputCapture.clear(playerUuid);
        boolean restored = !restoreModel || modelService.restore(store, ref, playerUuid);
        if (restoreEquipment) {
            markEquipmentPresentationOutdated(store, ref);
        }
        return restored
                ? Result.ok("Avatar flight disabled" + (restoreModel ? " and model restored." : "."))
                : Result.fail("Avatar flight disabled, but no saved model or skin fallback was available.");
    }

    /**
     * Lets the base equipment tracker replay armor after the restored player skin reaches the client.
     */
    private static void markEquipmentPresentationOutdated(@Nonnull Store<EntityStore> store,
                                                           @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor != null) {
            armor.setOutdatedEquipment(true);
        }
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null) {
            hotbar.setOutdatedEquipment(true);
        }
        InventoryComponent.Utility utility = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utility != null) {
            utility.setOutdatedEquipment(true);
        }
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

    void preparePlayerDisconnect(@Nonnull UUID playerUuid) {
        AvatarFlightClientFlightProbe.clear(playerUuid);
        AvatarFlightSessionRegistry.markDisconnecting(playerUuid);
        AvatarFlightPacketInputCapture.clear(playerUuid);
    }

    void finishPlayerDisconnect(@Nonnull UUID playerUuid) {
        modelService.clearSavedModel(playerUuid);
        AvatarFlightSessionRegistry.markInactive(playerUuid);
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
