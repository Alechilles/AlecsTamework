package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.avatarflight.AvatarFlightCombatAbilityResolver;
import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
        if (!resolution.isAvailable()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        String rootId = resolution.rootInteractionId();
        context.execute(RootInteraction.getRootInteractionOrUnknown(rootId));
    }

    private void setSlot(@Nullable String serializedSlot) {
        AvatarFlightCombatAbilitySlot resolved = AvatarFlightCombatAbilitySlot.fromSerializedKey(serializedSlot);
        if (resolved == null) {
            throw new IllegalArgumentException("TameworkAvatarFlightCombatAbility Slot must be Ability2 or Ability3.");
        }
        this.slot = resolved;
    }
}
