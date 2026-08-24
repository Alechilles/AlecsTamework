package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.items.TameworkNpcCullService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Culls one authorized NPC target from an item interaction. */
public final class TameworkCullNpcInteraction extends SimpleInteraction {
    public static final String TYPE_ID = "TameworkCullNpc";
    public static final BuilderCodec<TameworkCullNpcInteraction> CODEC = BuilderCodec.builder(
            TameworkCullNpcInteraction.class,
            TameworkCullNpcInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation(
                    "Culls an authorized NPC target and applies managed domestic rewards when configured."
            )
            .<Boolean>appendInherited(
                    new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
                    (interaction, value) -> interaction.requireOwner = value == null || value,
                    interaction -> interaction.requireOwner,
                    (interaction, parent) -> interaction.requireOwner = parent.requireOwner
            )
            .documentation("Requires the user to own the target NPC. Defaults to true.")
            .add()
            .<Boolean>appendInherited(
                    new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
                    (interaction, value) -> interaction.requireTamed = value == null || value,
                    interaction -> interaction.requireTamed,
                    (interaction, parent) -> interaction.requireTamed = parent.requireTamed
            )
            .documentation("Requires the target NPC to be tamed. Defaults to true.")
            .add()
            .build();

    private boolean requireOwner = true;
    private boolean requireTamed = true;

    protected TameworkCullNpcInteraction() {
        super();
    }

    public TameworkCullNpcInteraction(String id) {
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
        if (!firstRun) {
            super.tick0(false, time, type, context, cooldownHandler);
            return;
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        Ref<EntityStore> target = context.getTargetEntity();
        ItemStack heldItem = context.getHeldItem();
        if (commandBuffer == null || playerRef == null || target == null
                || !target.isValid() || heldItem == null || heldItem.isEmpty()) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        Player player = commandBuffer.getComponent(playerRef, Player.getComponentType());
        if (player == null || !TameworkNpcCullService.canCullFromItemInteraction(
                player, target, commandBuffer, requireOwner, requireTamed)) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        commandBuffer.run(store -> TameworkNpcCullService.cullFromItemInteraction(
                player, target, store, requireOwner, requireTamed));
        context.setHeldItem(heldItem);
        super.tick0(true, time, type, context, cooldownHandler);
    }

    @Override
    protected void simulateTick0(boolean firstRun,
                                 float time,
                                 @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context,
                                 @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null
                && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.simulateTick0(firstRun, time, type, context, cooldownHandler);
    }

    boolean requiresOwner() {
        return requireOwner;
    }

    boolean requiresTamed() {
        return requireTamed;
    }

    private void fail(InteractionContext context,
                      float time,
                      InteractionType type,
                      CooldownHandler cooldownHandler) {
        context.getState().state = InteractionState.Failed;
        super.tick0(true, time, type, context, cooldownHandler);
    }
}
