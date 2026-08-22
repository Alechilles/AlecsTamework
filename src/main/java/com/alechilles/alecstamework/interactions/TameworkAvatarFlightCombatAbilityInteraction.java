package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.avatarflight.AvatarFlightCombatAbilityResolver;
import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/** Delegates a native item ability slot to the active avatar-flight config's root interaction. */
public final class TameworkAvatarFlightCombatAbilityInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkAvatarFlightCombatAbilityInteraction> CODEC = BuilderCodec.builder(
            TameworkAvatarFlightCombatAbilityInteraction.class,
            TameworkAvatarFlightCombatAbilityInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Runs the active avatar-flight combat ability for Slot Ability2 or Ability3.")
            .<String>appendInherited(
                    new KeyedCodec<>("Slot", Codec.STRING),
                    TameworkAvatarFlightCombatAbilityInteraction::setSlot,
                    interaction -> interaction.slot.getSerializedKey(),
                    (interaction, parent) -> interaction.slot = parent.slot
            )
            .add()
            .build();

    private final AvatarFlightCombatAbilityResolver resolver = new AvatarFlightCombatAbilityResolver();
    private AvatarFlightCombatAbilitySlot slot = AvatarFlightCombatAbilitySlot.ABILITY_2;

    protected TameworkAvatarFlightCombatAbilityInteraction() {
        super();
    }

    public TameworkAvatarFlightCombatAbilityInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void tick0(boolean firstRun,
                         float time,
                         @Nonnull InteractionType type,
                         @Nonnull InteractionContext context,
                         @Nonnull CooldownHandler cooldownHandler) {
        if (firstRun) executeConfiguredAbility(context);
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    @Override
    protected void simulateTick0(boolean firstRun,
                                 float time,
                                 @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context,
                                 @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private void executeConfiguredAbility(@Nonnull InteractionContext context) {
        AvatarFlightCombatAbilityResolver.Resolution resolution = resolver.resolve(context, slot);
        if (!delegateAccepted(
                tryStartCooldown(context, resolution),
                resolution,
                rootId -> context.execute(
                        RootInteraction.getRootInteractionOrUnknown(rootId)),
                rootId -> publishAcceptedAbility(context, rootId))) {
            context.getState().state = InteractionState.Failed;
        }
    }

    private boolean tryStartCooldown(@Nonnull InteractionContext context,
                                     @Nonnull AvatarFlightCombatAbilityResolver.Resolution resolution) {
        if (!resolution.isAvailable()) return false;
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        if (commandBuffer == null || playerRef == null || flightType == null) return false;

        AvatarFlightComponent flight = commandBuffer.getComponent(playerRef, flightType);
        return flight != null && flight.tryStartCombatAbilityCooldown(
                slot, System.currentTimeMillis(), resolution.cooldownSeconds());
    }

    static boolean delegate(@Nonnull AvatarFlightCombatAbilityResolver.Resolution resolution,
                            @Nonnull Consumer<String> rootExecutor) {
        return delegateAccepted(
                true, resolution, rootExecutor, ignored -> { });
    }

    static boolean delegateAccepted(
            boolean cooldownAccepted,
            @Nonnull AvatarFlightCombatAbilityResolver.Resolution resolution,
            @Nonnull Consumer<String> rootExecutor,
            @Nonnull Consumer<String> acceptedObserver
    ) {
        if (!cooldownAccepted || !resolution.isAvailable()) return false;
        rootExecutor.accept(resolution.rootInteractionId());
        acceptedObserver.accept(resolution.rootInteractionId());
        return true;
    }

    private void publishAcceptedAbility(
            @Nonnull InteractionContext context,
            @Nonnull String rootInteractionId
    ) {
        if (!ActivityRuntime.hasAvatarFlightInterest(
                ActivityIds.FLIGHT_COMBAT_ABILITY)) {
            return;
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        ComponentType<EntityStore, AvatarFlightComponent> flightType =
                AvatarFlightComponent.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        if (commandBuffer == null || playerRef == null
                || flightType == null || uuidType == null) {
            return;
        }
        AvatarFlightComponent flight = commandBuffer.getComponent(
                playerRef, flightType);
        UUIDComponent identity = commandBuffer.getComponent(
                playerRef, uuidType);
        publishAcceptedAbility(
                identity == null ? null : identity.getUuid(),
                flight == null ? null : flight.getConfigId(),
                slot,
                rootInteractionId);
    }

    static void publishAcceptedAbility(
            @Nullable UUID playerId,
            @Nullable String flightConfigId,
            @Nonnull AvatarFlightCombatAbilitySlot slot,
            @Nullable String rootInteractionId
    ) {
        ActivityRuntime.publishAvatarFlight(
                ActivityIds.FLIGHT_COMBAT_ABILITY,
                playerId,
                flightConfigId,
                slot.getSerializedKey(),
                rootInteractionId);
    }

    private void setSlot(@Nullable String serializedSlot) {
        AvatarFlightCombatAbilitySlot resolved = AvatarFlightCombatAbilitySlot.fromSerializedKey(serializedSlot);
        if (resolved == null) {
            throw new IllegalArgumentException("TameworkAvatarFlightCombatAbility Slot must be Ability2 or Ability3.");
        }
        this.slot = resolved;
    }
}
