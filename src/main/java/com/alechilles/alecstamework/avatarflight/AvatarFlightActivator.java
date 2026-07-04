package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates command-driven avatar-flight activation and cleanup.
 */
public final class AvatarFlightActivator {
    private final AvatarFlightModelService modelService = new AvatarFlightModelService();

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
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType = AvatarFlightInputComponent.getComponentType();
        if (flightType == null || inputType == null) {
            if (applyModel) {
                modelService.restore(store, ref, playerUuid);
            }
            return Result.fail("Avatar flight component types are not registered.");
        }
        store.putComponent(ref, flightType, new AvatarFlightComponent(config.getId(), System.currentTimeMillis()));
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
        if (flightType != null) {
            store.tryRemoveComponent(ref, flightType);
        }
        if (inputType != null) {
            store.tryRemoveComponent(ref, inputType);
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
