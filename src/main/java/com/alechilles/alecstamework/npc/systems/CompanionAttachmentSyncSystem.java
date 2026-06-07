package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentStateService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Periodically reapplies persisted companion attachments after model/role changes.
 */
public final class CompanionAttachmentSyncSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 750L;

    private long nextSweepAtMs;
    private final Map<UUID, AttachmentSyncFingerprint> lastSyncedFingerprintByNpc = new ConcurrentHashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (npcType == null || attachmentsType == null) {
            return;
        }

        List<Ref<EntityStore>> candidates = new ArrayList<>();
        HashSet<UUID> activeNpcIds = new HashSet<>();
        store.forEachChunk(
                Query.and(npcType, attachmentsType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        NPCEntity npc = chunk.getComponent(i, npcType);
                        TameworkAttachmentsComponent attachments = chunk.getComponent(i, attachmentsType);
                        if (ref == null || !ref.isValid() || attachments == null || attachments.getAttachmentIds().isEmpty()) {
                            continue;
                        }
                        if (npc != null && npc.getUuid() != null) {
                            activeNpcIds.add(npc.getUuid());
                        }
                        candidates.add(ref);
                    }
                }
        );
        pruneInactiveKeys(lastSyncedFingerprintByNpc, activeNpcIds);
        for (Ref<EntityStore> ref : candidates) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(ref, npcType);
            if (CompanionAttachmentSyncGuards.shouldDeferForHarvestCooldown(ref, npc, store)) {
                continue;
            }
            TameworkAttachmentsComponent attachments = store.getComponent(ref, attachmentsType);
            AttachmentSyncFingerprint beforeSync = buildFingerprint(ref, store, npc, attachments);
            if (beforeSync != null && beforeSync.equals(lastSyncedFingerprintByNpc.get(beforeSync.npcUuid()))) {
                continue;
            }
            CompanionAttachmentStateService.syncStoredAttachments(ref, store);
            AttachmentSyncFingerprint afterSync = buildFingerprint(
                    ref,
                    store,
                    store.getComponent(ref, npcType),
                    store.getComponent(ref, attachmentsType)
            );
            if (afterSync != null) {
                lastSyncedFingerprintByNpc.put(afterSync.npcUuid(), afterSync);
            }
        }
    }

    @Nullable
    private static AttachmentSyncFingerprint buildFingerprint(@Nonnull Ref<EntityStore> ref,
                                                             @Nonnull Store<EntityStore> store,
                                                             @Nullable NPCEntity npc,
                                                             @Nullable TameworkAttachmentsComponent attachments) {
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null || attachments == null || attachments.getAttachmentIds().isEmpty()) {
            return null;
        }
        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        String modelAssetId = model != null ? model.getModelAssetId() : null;
        Map<String, String> currentAttachmentIds = model != null ? model.getRandomAttachmentIds() : null;
        return new AttachmentSyncFingerprint(
                npcUuid,
                normalize(modelAssetId),
                normalize(attachments.getConfigId()),
                stableMapHash(attachments.getAttachmentIds()),
                attachments.getAttachmentIds().size(),
                stableMapHash(currentAttachmentIds),
                currentAttachmentIds != null ? currentAttachmentIds.size() : 0
        );
    }

    private static int stableMapHash(@Nullable Map<?, ?> values) {
        return values == null || values.isEmpty() ? 0 : values.hashCode();
    }

    static <T> void pruneInactiveKeys(@Nonnull Map<UUID, T> valuesByNpc,
                                      @Nonnull HashSet<UUID> activeNpcIds) {
        if (valuesByNpc.isEmpty()) {
            return;
        }
        if (activeNpcIds.isEmpty()) {
            valuesByNpc.clear();
            return;
        }
        for (UUID npcUuid : new ArrayList<>(valuesByNpc.keySet())) {
            if (npcUuid == null || activeNpcIds.contains(npcUuid)) {
                continue;
            }
            valuesByNpc.remove(npcUuid);
        }
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private record AttachmentSyncFingerprint(@Nonnull UUID npcUuid,
                                             @Nullable String modelAssetId,
                                             @Nullable String configId,
                                             int persistedAttachmentsHash,
                                             int persistedAttachmentCount,
                                             int currentAttachmentsHash,
                                             int currentAttachmentCount) {
    }
}
