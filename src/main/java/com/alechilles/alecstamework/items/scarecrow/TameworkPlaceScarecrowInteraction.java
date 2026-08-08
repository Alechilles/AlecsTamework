package com.alechilles.alecstamework.items.scarecrow;

import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Places a persistent scarecrow entity using Hytale's exact-angle deployable preview. */
public final class TameworkPlaceScarecrowInteraction extends SimpleInstantInteraction {
    private static final float MAX_PLACEMENT_DISTANCE = (float) InteractionManager.MAX_REACH_DISTANCE;
    private static final float TOP_FACE_TOLERANCE = 0.01f;

    public static final String TYPE_ID = "TameworkPlaceScarecrow";
    public static final BuilderCodec<TameworkPlaceScarecrowInteraction> CODEC = BuilderCodec.builder(
            TameworkPlaceScarecrowInteraction.class,
            TameworkPlaceScarecrowInteraction::new,
            SimpleInstantInteraction.CODEC
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
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> actorRef = context.getEntity();
        if (actorRef == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        TransformComponent actorTransform = commandBuffer.getComponent(
                actorRef,
                TransformComponent.getComponentType()
        );
        Player player = commandBuffer.getComponent(actorRef, Player.getComponentType());
        ItemStack itemInHand = context.getHeldItem();
        var clientState = context.getClientState();
        if (!isScarecrowItem(itemInHand)
                || actorTransform == null
                || player == null
                || clientState == null
                || clientState.raycastHit == null
                || clientState.raycastNormal == null
                || clientState.raycastDistance <= 0
                || clientState.raycastDistance > MAX_PLACEMENT_DISTANCE
                || !isTopSurface(
                        clientState.raycastNormal.x(),
                        clientState.raycastNormal.y(),
                        clientState.raycastNormal.z()
                )) {
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.placeUnavailable");
            return;
        }
        Vector3d previewPosition = new Vector3d(
                clientState.raycastHit.x,
                clientState.raycastHit.y,
                clientState.raycastHit.z
        );
        float previewYaw = clientState.attackerRot != null ? clientState.attackerRot.yaw : 0.0f;
        World world = commandBuffer.getExternalData().getWorld();
        ScarecrowPlacementService.Preparation preparation = ScarecrowPlacementService.prepare(
                world,
                previewPosition,
                previewYaw
        );
        if (!preparation.succeeded()) {
            fail(context, commandBuffer, actorRef, messageFor(preparation.status()));
            return;
        }
        placePrepared(context, commandBuffer, actorRef, player, preparation.holder());
    }

    private static boolean isTopSurface(float normalX, float normalY, float normalZ) {
        return Math.abs(normalX) <= TOP_FACE_TOLERANCE
                && normalY >= 1.0f - TOP_FACE_TOLERANCE
                && Math.abs(normalZ) <= TOP_FACE_TOLERANCE;
    }

    private static void placePrepared(
            InteractionContext context,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> actorRef,
            Player player,
            Holder<EntityStore> holder
    ) {
        boolean consumed = player.getGameMode() != GameMode.Creative;
        if (consumed && !PlayerInventoryAccess.removeActiveHotbarItem(player, ScarecrowIds.ITEM_ID, 1)) {
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.itemChanged");
            return;
        }
        try {
            Ref<EntityStore> placed = commandBuffer.addEntity(holder, AddReason.SPAWN);
            if (placed == null) {
                throw new IllegalStateException("Scarecrow holder was not accepted");
            }
        } catch (RuntimeException exception) {
            if (consumed) {
                returnConsumedItem(actorRef, commandBuffer);
            }
            fail(context, commandBuffer, actorRef, "server.tamework.scarecrow.placeUnavailable");
        }
    }

    private static void returnConsumedItem(
            Ref<EntityStore> actorRef,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        ItemStack returnedItem = new ItemStack(ScarecrowIds.ITEM_ID, 1);
        var transaction = Player.giveItem(returnedItem, actorRef, commandBuffer);
        ItemStack remainder = transaction.getRemainder();
        if (!ItemStack.isEmpty(remainder)) {
            ItemUtils.dropItem(actorRef, remainder, commandBuffer);
        }
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

    @Override
    public boolean needsRemoteSync() {
        return true;
    }

    @Nonnull
    @Override
    protected com.hypixel.hytale.protocol.Interaction generatePacket() {
        return new com.hypixel.hytale.protocol.SpawnDeployableFromRaycastInteraction();
    }

    @Override
    protected void configurePacket(com.hypixel.hytale.protocol.Interaction packet) {
        super.configurePacket(packet);
        var deployablePacket = (com.hypixel.hytale.protocol.SpawnDeployableFromRaycastInteraction) packet;
        Model model = ScarecrowPlacementService.createModel();
        if (model != null) {
            var previewConfig = new com.hypixel.hytale.protocol.DeployableConfig();
            previewConfig.model = model.toPacket();
            previewConfig.modelPreview = model.toPacket();
            previewConfig.allowPlaceOnWalls = false;
            deployablePacket.deployableConfig = previewConfig;
        }
        deployablePacket.maxDistance = MAX_PLACEMENT_DISTANCE;
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
