package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySettings;
import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves a configured avatar-flight combat root without executing gameplay effects. */
public final class AvatarFlightCombatAbilityResolver {
    /** Resolves the active interaction entity's configured combat ability on the main interaction thread. */
    @Nonnull
    public Resolution resolve(@Nonnull InteractionContext context, @Nullable AvatarFlightCombatAbilitySlot slot) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        if (commandBuffer == null || playerRef == null || flightType == null) return Resolution.unavailable();

        AvatarFlightComponent flight = commandBuffer.getComponent(playerRef, flightType);
        if (flight == null) return Resolution.unavailable();
        return resolve(flight, TwAvatarFlightConfig.resolve(flight.getConfigId()), slot);
    }

    /** Resolves a supplied live flight state and its effective config for focused unit tests. */
    @Nonnull
    public Resolution resolve(@Nullable AvatarFlightComponent flight,
                              @Nullable TwAvatarFlightConfig config,
                              @Nullable AvatarFlightCombatAbilitySlot slot) {
        if (flight == null || config == null || slot == null) return Resolution.unavailable();
        AvatarFlightCombatAbilitySettings ability = config.getCombatAbility(slot);
        return ability == null ? Resolution.unavailable() : Resolution.available(ability.getRootInteraction());
    }

    /** Immutable outcome that intentionally contains no executable interaction object. */
    public record Resolution(@Nonnull String rootInteractionId) {
        private static final Resolution UNAVAILABLE = new Resolution("");

        @Nonnull
        public static Resolution unavailable() {
            return UNAVAILABLE;
        }

        @Nonnull
        public static Resolution available(@Nullable String rootInteractionId) {
            if (rootInteractionId == null || rootInteractionId.isBlank()) return unavailable();
            return new Resolution(rootInteractionId.trim());
        }

        public boolean isAvailable() {
            return !rootInteractionId.isEmpty();
        }
    }
}
