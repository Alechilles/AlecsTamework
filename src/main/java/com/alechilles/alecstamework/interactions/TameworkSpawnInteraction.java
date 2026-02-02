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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public class TameworkSpawnInteraction extends SimpleInstantInteraction {

    public static final BuilderCodec<TameworkSpawnInteraction> CODEC = BuilderCodec.builder(
            TameworkSpawnInteraction.class,
            TameworkSpawnInteraction::new,
            SimpleInstantInteraction.CODEC
    )
            .documentation("Spawns a captured NPC from a Tamework spawner item.")
            .<String>appendInherited(
                    new KeyedCodec<>("SpawnerRoleId", Codec.STRING),
                    (interaction, value) -> interaction.spawnerRoleId = value,
                    interaction -> interaction.spawnerRoleId,
                    (interaction, parent) -> interaction.spawnerRoleId = parent.spawnerRoleId
            )
            .add()
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
            .<Boolean>appendInherited(
                    new KeyedCodec<>("AllowUncaptured", Codec.BOOLEAN),
                    (interaction, value) -> interaction.allowUncaptured = value,
                    interaction -> interaction.allowUncaptured,
                    (interaction, parent) -> interaction.allowUncaptured = parent.allowUncaptured
            )
            .add()
            .build();

    private String spawnerRoleId;
    private String emptyItemId;
    private boolean spawnAssignsOwner = true;
    private boolean allowUncaptured;

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
    protected void firstRun(@Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        if (commandBuffer == null || ref == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null || heldItem.isEmpty()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        if (spawnerRoleId == null || spawnerRoleId.isBlank()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Tamework plugin = Tamework.getInstance();
        SpawnerFeatureHandler handler = plugin != null ? plugin.getSpawnerFeatureHandler() : null;
        if (handler == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        commandBuffer.run(store -> handler.spawnFromItemInteraction(
                player,
                heldItem,
                spawnerRoleId,
                emptyItemId,
                spawnAssignsOwner,
                allowUncaptured
        ));
        context.setHeldItem(heldItem);
        context.getState().state = InteractionState.Finished;
    }
}
