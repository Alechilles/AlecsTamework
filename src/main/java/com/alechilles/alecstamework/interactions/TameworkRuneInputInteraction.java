package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.compat.runes.RuneInputRuntime;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import javax.annotation.Nonnull;

/** Routes a temporary rune cast to the selected item's original E/R interaction. */
public final class TameworkRuneInputInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkRuneInputInteraction> CODEC = BuilderCodec.builder(
            TameworkRuneInputInteraction.class, TameworkRuneInputInteraction::new, SimpleInteraction.CODEC)
            .appendInherited(new KeyedCodec<>("Slot", Codec.STRING),
                    (interaction, value) -> interaction.slot = parseSlot(value),
                    interaction -> interaction.slot.name(),
                    (interaction, parent) -> interaction.slot = parent.slot)
            .add()
            .build();

    private InteractionType slot = InteractionType.Ability2;

    protected TameworkRuneInputInteraction() { }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type,
                         @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (firstRun && !route(context)) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private boolean route(InteractionContext context) {
        var buffer = context.getCommandBuffer();
        var playerRef = context.getEntity();
        var rune = context.getHeldItem();
        String expectedRune = slot == InteractionType.Ability2
                ? "Tamework_Input_Rune_E" : "Tamework_Input_Rune_R";
        if (buffer == null || playerRef == null
                || rune == null || !expectedRune.equals(rune.getItemId())
                || !RuneInputRuntime.isActiveLease(buffer, playerRef, slot)) {
            return false;
        }
        // Ability1 still resolves the selected hotbar item on Update 7. A separate
        // context keeps command-item metadata writes out of the temporary rune slot.
        var heldContext = InteractionContext.forInteraction(context.getInteractionManager(),
                playerRef, InteractionType.Ability1, buffer);
        String rootId = heldContext.getRootInteractionId(slot);
        if (rootId == null || rootId.isBlank()) {
            return false;
        }
        copyTarget(context, heldContext);
        context.fork(InteractionType.Ability1, heldContext,
                RootInteraction.getRootInteractionOrUnknown(rootId), false);
        return true;
    }

    @Override
    protected void simulateTick0(boolean firstRun, float time, @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private static void copyTarget(InteractionContext source, InteractionContext target) {
        // Carry aim information, without importing the player's rune modifiers.
        var from = source.getMetaStore();
        var to = target.getMetaStore();
        var entity = from.getIfPresentMetaObject(Interaction.TARGET_ENTITY);
        if (entity != null) to.putMetaObject(Interaction.TARGET_ENTITY, entity);
        var block = from.getIfPresentMetaObject(Interaction.TARGET_BLOCK_RAW);
        if (block != null) to.putMetaObject(Interaction.TARGET_BLOCK_RAW, block);
        var location = from.getIfPresentMetaObject(Interaction.HIT_LOCATION);
        if (location != null) to.putMetaObject(Interaction.HIT_LOCATION, location);
        var detail = from.getIfPresentMetaObject(Interaction.HIT_DETAIL);
        if (detail != null) to.putMetaObject(Interaction.HIT_DETAIL, detail);
    }

    private static InteractionType parseSlot(String value) {
        if ("Ability2".equals(value)) return InteractionType.Ability2;
        if ("Ability3".equals(value)) return InteractionType.Ability3;
        throw new IllegalArgumentException("TameworkRuneInput Slot must be Ability2 or Ability3.");
    }
}
