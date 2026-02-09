package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
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

/**
 * Custom interaction handler used by spawner items.
 */
public class TameworkSpawnInteraction extends SimpleInteraction {


    public static final BuilderCodec<TameworkSpawnInteraction> CODEC = BuilderCodec.builder(
            TameworkSpawnInteraction.class,
            TameworkSpawnInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Spawns a captured NPC from a Tamework spawner item.")
            .<String>appendInherited(
                    new KeyedCodec<>("EmptyItemId", Codec.STRING),
                    (interaction, value) -> interaction.emptyItemId = value,
                    interaction -> interaction.emptyItemId,
                    (interaction, parent) -> interaction.emptyItemId = parent.emptyItemId
            )
            .add()
            .<Boolean>appendInherited(
                    new KeyedCodec<>("SpawnAssignsOwner", Codec.BOOLEAN),
                    (interaction, value) -> interaction.spawnAssignsOwner = value,
                    interaction -> interaction.spawnAssignsOwner,
                    (interaction, parent) -> interaction.spawnAssignsOwner = parent.spawnAssignsOwner
            )
            .add()
            .build();

    private String emptyItemId;
    private Boolean spawnAssignsOwner;

    protected TameworkSpawnInteraction() {
        super();
    }

    public TameworkSpawnInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    // Runs spawn logic on the store command buffer to avoid illegal store writes.
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
        Ref<EntityStore> ref = context.getEntity();
        if (commandBuffer == null || ref == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        ItemStack heldItem = context.getHeldItem();
        // Only spawn when the player is holding a valid spawner item.
        if (heldItem == null || heldItem.isEmpty()) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Tamework plugin = Tamework.getInstance();
        SpawnerFeatureHandler handler = plugin != null ? plugin.getSpawnerFeatureHandler() : null;
        if (handler == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }

        Ref<EntityStore> targetEntity = context.getTargetEntity();
        if (targetEntity != null) {
            // Capture into the held item if a target entity is present.
            boolean canCapture = handler.canCaptureInteraction(player, targetEntity, heldItem);
            if (!canCapture) {
                context.getState().state = InteractionState.Failed;
                super.tick0(true, time, type, context, cooldownHandler);
                return;
            }
            commandBuffer.run(store -> handler.captureFromItemInteraction(player, heldItem, targetEntity));
            context.setHeldItem(heldItem);
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }

        boolean canSpawn = handler.canSpawnInteraction(heldItem);
        if (!canSpawn) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }

        // Defer to store thread for safe entity creation.
        commandBuffer.run(store -> handler.spawnFromItemInteraction(
                player,
                heldItem,
                emptyItemId,
                spawnAssignsOwner
        ));
        context.setHeldItem(heldItem);
        super.tick0(true, time, type, context, cooldownHandler);
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
}
