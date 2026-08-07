package com.alechilles.alecstamework.items.scarecrow;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Places a persistent scarecrow entity after server-side surface and inventory validation. */
public final class TameworkPlaceScarecrowInteraction extends SimpleBlockInteraction {
    public static final String TYPE_ID = "TameworkPlaceScarecrow";
    public static final BuilderCodec<TameworkPlaceScarecrowInteraction> CODEC = BuilderCodec.builder(
            TameworkPlaceScarecrowInteraction.class,
            TameworkPlaceScarecrowInteraction::new,
            SimpleBlockInteraction.CODEC
    ).documentation("Places a Tamework scarecrow spawn suppressor.").build();

    protected TameworkPlaceScarecrowInteraction() {
        super();
    }

    public TameworkPlaceScarecrowInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        Ref<EntityStore> actorRef = context.getEntity();
        TransformComponent actorTransform = commandBuffer.getComponent(
                actorRef,
                TransformComponent.getComponentType()
        );
        Player player = commandBuffer.getComponent(actorRef, Player.getComponentType());
        if (!isScarecrowItem(itemInHand) || actorTransform == null || player == null) {
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.placeUnavailable");
            return;
        }
        ScarecrowPlacementService.Preparation preparation = ScarecrowPlacementService.prepare(
                world,
                targetBlock.x,
                targetBlock.y,
                targetBlock.z,
                actorTransform.getPosition()
        );
        if (!preparation.succeeded()) {
            fail(context, commandBuffer, actorRef, messageFor(preparation.status()));
            return;
        }
        placePrepared(context, commandBuffer, actorRef, actorTransform, player, preparation.holder());
    }

    private static void placePrepared(
            InteractionContext context,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> actorRef,
            TransformComponent actorTransform,
            Player player,
            Holder<EntityStore> holder
    ) {
        if (!PlayerInventoryAccess.removeActiveHotbarItem(player, ScarecrowIds.ITEM_ID, 1)) {
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.itemChanged");
            return;
        }
        try {
            Ref<EntityStore> placed = commandBuffer.addEntity(holder, AddReason.SPAWN);
            if (placed == null) {
                throw new IllegalStateException("Scarecrow holder was not accepted");
            }
        } catch (RuntimeException exception) {
            ItemUtils.interactivelyPickupItem(
                    actorRef,
                    new ItemStack(ScarecrowIds.ITEM_ID, 1),
                    actorTransform.getPosition(),
                    commandBuffer
            );
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.placeUnavailable");
        }
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
        if (context.getServerState() != null
                && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
    }

    private static boolean isScarecrowItem(@Nullable ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && ScarecrowIds.ITEM_ID.equals(stack.getItemId());
    }

    private static String messageFor(ScarecrowPlacementService.Status status) {
        return switch (status) {
            case INVALID_SURFACE -> "server.tamework.scarecrow.invalidSurface";
            case OCCUPIED -> "server.tamework.scarecrow.occupied";
            case SUCCESS, INVALID_ASSET, UNAVAILABLE -> "server.tamework.scarecrow.placeUnavailable";
        };
    }

    private static void fail(
            InteractionContext context,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> actorRef,
            String messageKey
    ) {
        context.getState().state = InteractionState.Failed;
        PlayerRef playerRef = commandBuffer.getComponent(actorRef, PlayerRef.getComponentType());
        if (playerRef != null) {
            playerRef.sendMessage(Message.translation(messageKey).color("#ff5555"));
        }
    }
}
