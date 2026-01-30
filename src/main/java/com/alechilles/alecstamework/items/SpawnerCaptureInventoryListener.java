package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.SpawnerCaptureTracker.PendingCapture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import org.bson.BsonDocument;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class SpawnerCaptureInventoryListener {
    private final ItemFeatureRegistry registry;
    private final SpawnerCaptureTracker captureTracker;
    private final HytaleLogger logger;

    public SpawnerCaptureInventoryListener(ItemFeatureRegistry registry,
                                           SpawnerCaptureTracker captureTracker,
                                           HytaleLogger logger) {
        this.registry = registry;
        this.captureTracker = captureTracker;
        this.logger = logger;
    }

    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        if (event == null || captureTracker == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        Player player = (Player) entity;
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        PendingCapture pending = captureTracker.get(playerUuid);
        if (pending == null) {
            return;
        }
        if (captureTracker.isExpired(pending)) {
            captureTracker.clear(playerUuid);
            return;
        }

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null || event.getItemContainer() != hotbar) {
            return;
        }

        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof ItemStackTransaction)) {
            return;
        }
        ItemStackTransaction itemTx = (ItemStackTransaction) transaction;
        List<ItemStackSlotTransaction> slotTransactions = itemTx.getSlotTransactions();
        if (slotTransactions == null || slotTransactions.isEmpty()) {
            return;
        }

        short chosenSlot = -1;
        ItemStack chosenAfter = null;
        boolean foundStateItem = false;
        short pendingSlot = (short) pending.getHotbarSlot();
        boolean pendingSlotValid = pendingSlot >= 0;

        for (ItemStackSlotTransaction slotTx : slotTransactions) {
            if (slotTx == null) {
                continue;
            }
            ItemStack after = slotTx.getSlotAfter();
            if (after == null || after.isEmpty()) {
                continue;
            }
            String normalized = ItemFeatureRegistry.normalizeStateItemId(after.getItemId());
            if (normalized == null || !normalized.equals(pending.getItemId())) {
                continue;
            }
            if (registry.get(after.getItemId()) == null) {
                continue;
            }

            short slot = slotTx.getSlot();
            boolean isStateItem = after.getItemId() != null && after.getItemId().contains("_State_");
            if (pendingSlotValid && slot == pendingSlot) {
                chosenSlot = slot;
                chosenAfter = after;
                foundStateItem = isStateItem;
                break;
            }
            if (isStateItem && !foundStateItem) {
                chosenSlot = slot;
                chosenAfter = after;
                foundStateItem = true;
            } else if (chosenAfter == null) {
                chosenSlot = slot;
                chosenAfter = after;
            }
        }

        if (chosenAfter == null && pendingSlotValid) {
            chosenSlot = pendingSlot;
        }
        if (chosenSlot < 0) {
            return;
        }

        final short preferredSlot = chosenSlot;
        final UUID targetUuid = pending.getTargetUuid();
        final Integer targetEntityId = pending.getTargetEntityId();
        player.getWorld().execute(() -> {
            Inventory liveInventory = player.getInventory();
            if (liveInventory == null) {
                return;
            }
            ItemContainer liveHotbar = liveInventory.getHotbar();
            if (liveHotbar == null) {
                return;
            }

            short slotToUpdate = preferredSlot;
            ItemStack liveStack = liveHotbar.getItemStack(slotToUpdate);
            if (!matchesPending(liveStack, pending)) {
                if (pendingSlotValid) {
                    logger.at(Level.INFO).log(
                            "Spawner capture metadata skipped: slot mismatch player=" + player.getDisplayName()
                                    + " slot=" + slotToUpdate
                                    + " expectedItem=" + pending.getItemId()
                    );
                    return;
                }
                short capacity = liveHotbar.getCapacity();
                for (short i = 0; i < capacity; i++) {
                    ItemStack candidate = liveHotbar.getItemStack(i);
                    if (matchesPending(candidate, pending)) {
                        slotToUpdate = i;
                        liveStack = candidate;
                        break;
                    }
                }
            }

            if (!matchesPending(liveStack, pending)) {
                return;
            }

            ItemStack updated = liveStack.withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true);
            if (targetEntityId != null) {
                updated = updated.withMetadata(
                        TameworkMetadataKeys.TARGET_ENTITY_ID,
                        Codec.INTEGER,
                        targetEntityId
                );
            }
            if (targetUuid != null) {
                updated = updated.withMetadata(
                        TameworkMetadataKeys.TARGET_UUID,
                        Codec.UUID_STRING,
                        targetUuid
                );
            }

            String attachmentsJson = pending.getAttachmentsJson();
            if (attachmentsJson != null) {
                updated = updated.withMetadata(
                        TameworkMetadataKeys.ATTACHMENTS,
                        Codec.STRING,
                        attachmentsJson
                );
            }

            UUID ownerUuid = pending.getOwnerUuid();
            if (ownerUuid != null) {
                updated = updated.withMetadata(
                        TameworkMetadataKeys.OWNER_UUID,
                        Codec.UUID_STRING,
                        ownerUuid
                );
            } else {
                updated = clearMetadataKey(updated, TameworkMetadataKeys.OWNER_UUID);
            }

            Integer roleIndex = pending.getRoleIndex();
            if (roleIndex != null && roleIndex >= 0) {
                CapturedNPCMetadata meta = new CapturedNPCMetadata();
                meta.setRoleIndex(roleIndex);
                String npcNameKey = pending.getNpcNameKey();
                if (npcNameKey != null && !npcNameKey.isBlank()) {
                    meta.setNpcNameKey(npcNameKey);
                }
                String fullItemIcon = pending.getFullItemIcon();
                if (fullItemIcon != null && !fullItemIcon.isBlank()) {
                    meta.setFullItemIcon(fullItemIcon);
                    meta.setIconPath(fullItemIcon);
                } else {
                    String iconPath = pending.getIconPath();
                    if (iconPath != null && !iconPath.isBlank()) {
                        meta.setIconPath(iconPath);
                    }
                }
                updated = updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, meta);
            }

            liveHotbar.setItemStackForSlot(slotToUpdate, updated);
            liveInventory.markChanged();
            player.sendInventory();
            captureTracker.consume(playerUuid);

            logger.at(Level.INFO).log(
                    "Spawner capture metadata applied: player=" + player.getDisplayName()
                            + " slot=" + slotToUpdate
                            + " item=" + updated.getItemId()
            );
        });
    }

    private ItemStack clearMetadataKey(ItemStack stack, String key) {
        if (stack == null || key == null) {
            return stack;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copy = metadata.clone();
        copy.remove(key);
        return stack.withMetadata(copy);
    }

    private boolean matchesPending(ItemStack stack, PendingCapture pending) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(stack.getItemId());
        return normalized != null && normalized.equals(pending.getItemId());
    }
}
