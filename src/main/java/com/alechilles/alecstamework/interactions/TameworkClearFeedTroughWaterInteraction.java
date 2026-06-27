package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.items.FeedTroughWaterStateService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Clears stored water-charge metadata from feed trough water states.
 */
public class TameworkClearFeedTroughWaterInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkClearFeedTroughWaterInteraction> CODEC = BuilderCodec.builder(
            TameworkClearFeedTroughWaterInteraction.class,
            TameworkClearFeedTroughWaterInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Clears stored water trough charges on the targeted water trough block.")
            .<Boolean>appendInherited(
                    new KeyedCodec<>("RequireEmptyHand", Codec.BOOLEAN),
                    (interaction, value) -> interaction.requireEmptyHand = value,
                    interaction -> interaction.requireEmptyHand,
                    (interaction, parent) -> interaction.requireEmptyHand = parent.requireEmptyHand
            )
            .add()
            .build();

    private Boolean requireEmptyHand;

    protected TameworkClearFeedTroughWaterInteraction() {
        super();
    }

    public TameworkClearFeedTroughWaterInteraction(String id) {
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
        if (time < getRunTime()) {
            super.tick0(firstRun, time, type, context, cooldownHandler);
            return;
        }
        ItemStack heldItem = context.getHeldItem();
        if (Boolean.TRUE.equals(requireEmptyHand)) {
            if (heldItem != null && !heldItem.isEmpty()) {
                fail(context, time, type, cooldownHandler);
                return;
            }
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        BlockPosition targetBlock = context.getTargetBlock();
        if (commandBuffer == null || ref == null || targetBlock == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        World world = player.getWorld();
        if (world == null || world.getChunkStore() == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        if (chunkStore == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z));
        if (chunk == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        if (!FeedTroughWaterStateService.clearStoredCharges(
                chunk,
                chunkStore,
                targetBlock.x,
                targetBlock.y,
                targetBlock.z
        )) {
            fail(context, time, type, cooldownHandler);
            return;
        }
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

    private void fail(@Nonnull InteractionContext context,
                      float time,
                      @Nonnull InteractionType type,
                      @Nonnull CooldownHandler cooldownHandler) {
        context.getState().state = InteractionState.Failed;
        super.tick0(true, time, type, context, cooldownHandler);
    }
}
