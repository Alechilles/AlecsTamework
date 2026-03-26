package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles coop intake interception for captured-item placement when a matching TwCoopConfig exists.
 *
 * <p>Vanilla coop acceptance and resident limits are preserved by using {@link CoopBlock#tryPutResident}.
 */
public final class CoopFeatureHandler {
    private final HytaleLogger logger;
    private final CoopCapturePolicyService capturePolicyService;
    private final CoopEffectService effectService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;

    public CoopFeatureHandler(HytaleLogger logger,
                              @Nullable CommandLinkedNpcCaptureService captureService,
                              @Nullable CommandLinkedNpcCoopService coopService,
                              @Nullable CommandNpcRelocationService relocationService,
                              @Nullable CommandLinkedNpcLostService lostService) {
        this.logger = logger;
        this.capturePolicyService = new CoopCapturePolicyService();
        this.effectService = new CoopEffectService();
        this.captureService = captureService;
        this.coopService = coopService;
        this.relocationService = relocationService;
        this.lostService = lostService;
    }

    public void onPlayerInteract(@Nullable PlayerInteractEvent event) {
        if (event == null || event.isCancelled()) {
            return;
        }
        InteractionType actionType = event.getActionType();
        if (actionType != InteractionType.Use && actionType != InteractionType.Primary) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack heldItem = event.getItemInHand();
        if (heldItem == null || heldItem.isEmpty()) {
            return;
        }
        CapturedNPCMetadata metadata = CapturedNpcMetadataCompat.readMetadata(heldItem, logger);
        if (metadata == null) {
            return;
        }
        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null || world.getChunkStore() == null) {
            return;
        }
        CoopTarget coopTarget = resolveCoopTarget(world, targetBlock);
        if (coopTarget == null) {
            return;
        }
        TwCoopConfig config = TwCoopConfig.resolveForCoop(coopTarget.coopId());
        if (config == null || !config.isEnabled()) {
            return;
        }

        CoopCapturePolicyService.Decision policyDecision = capturePolicyService.evaluate(
                player,
                heldItem,
                config.getCapturePolicy()
        );
        if (!policyDecision.isAllowed()) {
            event.setCancelled(true);
            notifyDenied(player, policyDecision);
            logger.at(Level.FINE).log(
                    "Coop intake denied by policy: player=" + player.getDisplayName()
                            + " coop=" + coopTarget.coopId()
                            + " reason=" + policyDecision.getDenyReason()
            );
            return;
        }

        // Only cancel the event when Tamework successfully handled insertion.
        if (!insertResident(world, player, heldItem, metadata, coopTarget, config, policyDecision)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean insertResident(@Nonnull World world,
                                   @Nonnull Player player,
                                   @Nonnull ItemStack heldItem,
                                   @Nonnull CapturedNPCMetadata metadata,
                                   @Nonnull CoopTarget coopTarget,
                                   @Nonnull TwCoopConfig config,
                                   @Nonnull CoopCapturePolicyService.Decision policyDecision) {
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return false;
        }
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        if (worldTime == null) {
            return false;
        }
        CoopBlock coop = coopTarget.coopBlock();
        if (!coop.tryPutResident(metadata, worldTime)) {
            return false;
        }
        int residentSlot = CoopResidentSlotResolver.resolveMostRecentResidentSlot(coop);

        Vector3i block = coopTarget.blockPosition();
        world.execute(() -> coop.ensureSpawnResidentsInWorld(
                world,
                world.getEntityStore().getStore(),
                new Vector3d(block.x, block.y, block.z),
                new Vector3d().assign(Vector3d.FORWARD)
        ));
        UUID capturedNpcUuid = heldItem.getFromMetadataOrNull(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING);
        String itemRoleId = heldItem.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING);
        String itemDisplayName = firstNonBlank(
                heldItem.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME, Codec.STRING),
                heldItem.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME, Codec.STRING)
        );
        if (capturedNpcUuid != null) {
            CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                    captureService != null ? captureService.getCapturedSnapshot(capturedNpcUuid) : null;
            if (coopService != null) {
                UUID ownerUuid = capturedSnapshot != null && capturedSnapshot.ownerId() != null
                        ? capturedSnapshot.ownerId()
                        : policyDecision.getOwnerUuid();
                String[] toolIds = capturedSnapshot != null ? capturedSnapshot.toolIds() : new String[0];
                String roleId = firstNonBlank(
                        capturedSnapshot != null ? capturedSnapshot.roleId() : null,
                        itemRoleId
                );
                String displayName = firstNonBlank(
                        capturedSnapshot != null ? capturedSnapshot.displayName() : null,
                        itemDisplayName
                );
                coopService.recordCoopSnapshot(
                        new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                                capturedNpcUuid,
                                ownerUuid,
                                toolIds,
                                roleId,
                                displayName,
                                coopTarget.coopId(),
                                residentSlot,
                                System.currentTimeMillis()
                        )
                );
            }
            if (captureService != null) {
                captureService.clearCapturedSnapshot(capturedNpcUuid);
            }
            if (relocationService != null) {
                relocationService.cancelPendingRelocation(capturedNpcUuid);
            }
            if (lostService != null) {
                lostService.clearLostSnapshot(capturedNpcUuid);
            }
        }
        clearCapturedMetadataFromHeldItem(player, heldItem);
        effectService.playIntakeEffects(
                world,
                new Vector3d(block.x + 0.5, block.y + 0.8, block.z + 0.5),
                config.getCapturePolicy()
        );
        logger.at(Level.FINE).log(
                "Coop intake success: player=" + player.getDisplayName()
                        + " coop=" + coopTarget.coopId()
                        + " config=" + config.getId()
        );
        return true;
    }

    private void clearCapturedMetadataFromHeldItem(@Nonnull Player player, @Nonnull ItemStack heldItem) {
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return;
        }
        byte activeHotbarSlot = inventory.getActiveHotbarSlot();
        ItemStack noMetadata = heldItem.withMetadata(null);
        inventory.getHotbar().replaceItemStackInSlot(activeHotbarSlot, heldItem, noMetadata);
    }

    private void notifyDenied(@Nonnull Player player, @Nonnull CoopCapturePolicyService.Decision decision) {
        CoopCapturePolicyService.DenyReason reason = decision.getDenyReason();
        if (reason == null) {
            return;
        }
        switch (reason) {
            case REQUIRE_TAMED -> player.sendMessage(
                    Message.raw("This coop only accepts captured companions marked as tamed.")
            );
            case REQUIRE_OWNER -> player.sendMessage(
                    Message.raw("This coop only accepts captured companions that have an owner.")
            );
            case OWNER_RESTRICTED -> player.sendMessage(
                    Message.raw("This coop only accepts captured companions owned by you.")
            );
            default -> {
            }
        }
    }

    @Nullable
    private String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    @Nullable
    private CoopTarget resolveCoopTarget(@Nonnull World world, @Nonnull Vector3i blockPosition) {
        WorldChunk worldChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z));
        if (worldChunk == null) {
            return null;
        }
        Ref<ChunkStore> blockRef = worldChunk.getBlockComponentEntity(blockPosition.x, blockPosition.y, blockPosition.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore() != null ? world.getChunkStore().getStore() : null;
        if (chunkStore == null) {
            return null;
        }
        CoopBlock coopBlock = chunkStore.getComponent(blockRef, CoopBlock.getComponentType());
        if (coopBlock == null) {
            return null;
        }
        FarmingCoopAsset coopAsset = coopBlock.getCoopAsset();
        if (coopAsset == null || coopAsset.getId() == null || coopAsset.getId().isBlank()) {
            return null;
        }
        return new CoopTarget(coopBlock, blockPosition, coopAsset.getId());
    }

    private record CoopTarget(CoopBlock coopBlock, Vector3i blockPosition, String coopId) {
    }
}
