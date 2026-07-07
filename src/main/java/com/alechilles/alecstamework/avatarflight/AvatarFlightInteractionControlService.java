package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Applies item-interaction flight control pulses to transformed player avatars.
 */
public final class AvatarFlightInteractionControlService {
    private AvatarFlightInteractionControlService() {
    }

    public static boolean queueFlap(@Nonnull InteractionContext context, long nowMs) {
        return apply(context, nowMs, input -> input.queueReinsFlap(nowMs));
    }

    public static boolean activateAirbrake(@Nonnull InteractionContext context, long nowMs, long durationMs) {
        return apply(context, nowMs, input -> input.activateReinsAirbrake(nowMs, durationMs));
    }

    public static boolean queueBoost(@Nonnull InteractionContext context, long nowMs) {
        return apply(context, nowMs, input -> input.queueReinsBoost(nowMs));
    }

    private static boolean apply(@Nonnull InteractionContext context,
                                 long nowMs,
                                 @Nonnull FlightInputMutation mutation) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        if (commandBuffer == null || playerRef == null) {
            return false;
        }
        Tamework plugin = Tamework.getInstance();
        ComponentType<EntityStore, AvatarFlightComponent> flightType =
                plugin == null ? null : plugin.getAvatarFlightComponentType();
        ComponentType<EntityStore, AvatarFlightInputComponent> inputType =
                plugin == null ? null : plugin.getAvatarFlightInputComponentType();
        if (flightType == null || inputType == null || commandBuffer.getComponent(playerRef, flightType) == null) {
            return false;
        }
        AvatarFlightInputComponent input = commandBuffer.getComponent(playerRef, inputType);
        if (input == null) {
            input = new AvatarFlightInputComponent();
        }
        mutation.apply(input);
        commandBuffer.putComponent(playerRef, inputType, input);
        return true;
    }

    private interface FlightInputMutation {
        void apply(@Nonnull AvatarFlightInputComponent input);
    }
}
