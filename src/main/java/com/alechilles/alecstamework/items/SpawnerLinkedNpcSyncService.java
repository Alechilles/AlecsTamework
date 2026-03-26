package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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
        CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsInHotbar(player, oldNpcUuid, newNpcUuid);
    }
}
