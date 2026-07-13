package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
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

    @Nullable
    CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot prepareCapturedLinkedNpcSnapshot(
            @Nullable Ref<EntityStore> targetRef,
            @Nullable World world,
            @Nullable UUID targetUuid,
            @Nullable UUID fallbackOwnerId,
            @Nullable String roleId,
            @Nullable String displayName) {
        if (captureService == null || targetUuid == null || targetRef == null || !targetRef.isValid() || world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(targetRef, linksType);
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? transform.getPosition() : null;
        return buildPreparedSnapshot(
                targetUuid,
                fallbackOwnerId,
                links,
                roleId,
                displayName,
                lastKnownPosition,
                System.currentTimeMillis()
        );
    }

    void publishPreparedCapturedLinkedNpcSnapshot(
            @Nullable CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot prepared,
            @Nullable UUID liveNpcUuid) {
        if (captureService == null || liveNpcUuid == null) {
            return;
        }
        if (prepared == null) {
            captureService.clearCapturedSnapshot(liveNpcUuid);
            return;
        }
        captureService.recordCapturedSnapshot(withNpcUuid(prepared, liveNpcUuid));
    }

    /**
     * Freezes command-link state before an ownership-clearing capture removes live authorization.
     */
    @Nullable
    static CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot buildPreparedSnapshot(
            @Nullable UUID npcUuid,
            @Nullable UUID fallbackOwnerId,
            @Nullable TameworkCommandLinksComponent links,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable Vector3d lastKnownPosition,
            long capturedAtMs) {
        String[] toolIds = links != null ? links.getToolIds() : null;
        if (npcUuid == null || toolIds == null || toolIds.length == 0) {
            return null;
        }
        UUID ownerId = links.getOwnerId() != null ? links.getOwnerId() : fallbackOwnerId;
        Vector3d lastKnown = lastKnownPosition != null ? new Vector3d(lastKnownPosition) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        return new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                npcUuid,
                ownerId,
                toolIds.clone(),
                roleId,
                displayName,
                lastKnown,
                homePosition,
                capturedAtMs
        );
    }

    private static CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot withNpcUuid(
            CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot prepared,
            UUID liveNpcUuid) {
        return new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                liveNpcUuid,
                prepared.ownerId(),
                prepared.toolIds(),
                prepared.roleId(),
                prepared.displayName(),
                prepared.lastKnownPosition(),
                prepared.homePosition(),
                prepared.capturedAtMs()
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
