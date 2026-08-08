package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Returns a placed Tamework scarecrow to the player and unregisters its suppression. */
public final class TameworkCollectScarecrowInteraction extends SimpleInstantInteraction {
    public static final String TYPE_ID = "TameworkCollectScarecrow";
    public static final BuilderCodec<TameworkCollectScarecrowInteraction> CODEC = BuilderCodec.builder(
            TameworkCollectScarecrowInteraction.class,
            TameworkCollectScarecrowInteraction::new,
            SimpleInstantInteraction.CODEC
    ).documentation("Collects a placed Tamework scarecrow.").build();

    protected TameworkCollectScarecrowInteraction() {
        super();
    }

    public TameworkCollectScarecrowInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> actorRef = context.getEntity();
        Ref<EntityStore> targetRef = context.getTargetEntity();
        if (commandBuffer == null
                || actorRef == null
                || targetRef == null
                || !targetRef.isValid()
                || commandBuffer.getComponent(actorRef, Player.getComponentType()) == null
                || !isScarecrow(targetRef, commandBuffer)) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        TransformComponent targetTransform = commandBuffer.getComponent(
                targetRef,
                TransformComponent.getComponentType()
        );
        ItemStack returnedItem = new ItemStack(ScarecrowIds.ITEM_ID, 1);
        var transaction = Player.giveItem(returnedItem, actorRef, commandBuffer);
        if (!ItemStack.isEmpty(transaction.getRemainder())) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        commandBuffer.removeEntity(targetRef, RemoveReason.REMOVE);
        Player.notifyPickupItem(actorRef, returnedItem, targetTransform.getPosition(), commandBuffer);
    }

    private static boolean isScarecrow(
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        BlockEntity blockEntity = commandBuffer.getComponent(targetRef, BlockEntity.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(targetRef, TransformComponent.getComponentType());
        SpawnSuppressionComponent suppression = commandBuffer.getComponent(
                targetRef,
                SpawnSuppressionComponent.getComponentType()
        );
        return isScarecrow(blockEntity, transform, suppression);
    }

    static boolean isScarecrow(
            @Nullable BlockEntity blockEntity,
            @Nullable TransformComponent transform,
            @Nullable SpawnSuppressionComponent suppression
    ) {
        return blockEntity != null
                && transform != null
                && suppression != null
                && ScarecrowIds.SUPPRESSION_ID.equals(suppression.getSpawnSuppression());
    }

    @Override
    protected void simulateFirstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        if (context.getServerState() != null
                && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
    }
}
