package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Handles command-link synchronization around capture/spawn lifecycle operations.
 *
 * <p>This service keeps snapshot publication/restore and linked-record UUID remapping out of
 * {@link SpawnerFeatureHandler} so spawning orchestration stays focused on gameplay flow.
 */
final class SpawnerLinkedNpcSyncService {
    private final CommandLinkedNpcCaptureService captureService;

    SpawnerLinkedNpcSyncService(@Nullable CommandLinkedNpcCaptureService captureService) {
        this.captureService = captureService;
    }

    @Nullable
    CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot getCapturedSnapshot(@Nullable UUID npcUuid) {
        if (captureService == null || npcUuid == null) {
            return null;
        }
        return captureService.getCapturedSnapshot(npcUuid);
    }

    @Nullable
    UUID resolveEntityUuid(@Nullable Player player, @Nullable Ref<EntityStore> targetRef) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    void publishCapturedLinkedNpcSnapshot(@Nullable Ref<EntityStore> targetRef,
                                          @Nullable World world,
                                          @Nullable UUID targetUuid,
                                          @Nullable UUID fallbackOwnerId,
                                          @Nullable String roleId,
                                          @Nullable String displayName) {
        if (captureService == null || targetUuid == null || targetRef == null || !targetRef.isValid() || world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return;
        }
        TameworkCommandLinksComponent links = store.getComponent(targetRef, linksType);
        String[] toolIds = links != null ? links.getToolIds() : null;
        if (toolIds == null || toolIds.length == 0) {
            captureService.clearCapturedSnapshot(targetUuid);
            return;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId == null) {
            ownerId = fallbackOwnerId;
        }
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        captureService.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        targetUuid,
                        ownerId,
                        toolIds,
                        roleId,
                        displayName,
                        lastKnownPosition,
                        homePosition,
                        System.currentTimeMillis()
                )
        );
    }

    void clearCapturedSnapshotIfPresent(@Nullable UUID capturedNpcUuid) {
        if (captureService == null || capturedNpcUuid == null) {
            return;
        }
        captureService.clearCapturedSnapshot(capturedNpcUuid);
    }

    void restoreCommandLinksFromCapturedSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable Store<EntityStore> store,
                                                 @Nullable UUID fallbackOwnerId,
                                                 @Nullable CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot) {
        if (npcRef == null || !npcRef.isValid() || store == null || snapshot == null) {
            return;
        }
        String[] toolIds = snapshot.toolIds();
        if (toolIds == null || toolIds.length == 0) {
            return;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return;
        }
        UUID ownerId = snapshot.ownerId() != null ? snapshot.ownerId() : fallbackOwnerId;
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(ownerId, toolIds);
        if (snapshot.homePosition() != null) {
            links.setHomePosition(snapshot.homePosition());
        }
        store.putComponent(npcRef, linksType, links);
    }

    void remapLinkedNpcRecordsAfterRespawn(@Nullable Player player, @Nullable UUID oldNpcUuid, @Nullable UUID newNpcUuid) {
        if (player == null || oldNpcUuid == null || newNpcUuid == null || oldNpcUuid.equals(newNpcUuid)) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return;
        }
        boolean changedAny = false;
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack slotStack = hotbar.getItemStack(slot);
            if (slotStack == null || slotStack.isEmpty()) {
                continue;
            }
            String encodedLinks = slotStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING);
            if (encodedLinks == null || encodedLinks.isBlank()) {
                continue;
            }
            String rewritten = rewriteLinkedNpcUuidRecords(encodedLinks, oldNpcUuid, newNpcUuid);
            if (rewritten == null || rewritten.equals(encodedLinks)) {
                continue;
            }
            hotbar.setItemStackForSlot(
                    slot,
                    slotStack.withMetadata(TameworkMetadataKeys.COMMAND_LINKED_NPCS, Codec.STRING, rewritten)
            );
            changedAny = true;
        }
        if (changedAny) {
        }
    }

    private String rewriteLinkedNpcUuidRecords(String encodedLinks, UUID oldNpcUuid, UUID newNpcUuid) {
        if (encodedLinks == null || encodedLinks.isBlank() || oldNpcUuid == null || newNpcUuid == null) {
            return encodedLinks;
        }
        String oldKey = oldNpcUuid.toString();
        String newKey = newNpcUuid.toString();
        String[] lines = encodedLinks.split("\\R");
        LinkedHashMap<String, String> dedupedLines = new LinkedHashMap<>();
        Set<String> seenUuids = new HashSet<>();
        boolean changed = false;
        int rawCounter = 0;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            int separator = trimmed.indexOf('|');
            String prefix = separator >= 0 ? trimmed.substring(0, separator) : trimmed;
            String suffix = separator >= 0 ? trimmed.substring(separator) : "";

            String rewrittenPrefix = prefix;
            if (prefix.equalsIgnoreCase(oldKey)) {
                rewrittenPrefix = newKey;
                changed = true;
            }
            String rewritten = rewrittenPrefix + suffix;

            String dedupeKey;
            try {
                dedupeKey = UUID.fromString(rewrittenPrefix).toString().toLowerCase(Locale.ROOT);
                if (!seenUuids.add(dedupeKey)) {
                    changed = true;
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
                dedupeKey = "__raw__" + (rawCounter++);
            }
            dedupedLines.putIfAbsent(dedupeKey, rewritten);
        }

        if (!changed) {
            return encodedLinks;
        }

        StringBuilder builder = new StringBuilder();
        for (String line : dedupedLines.values()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
