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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Periodically reapplies persisted companion attachments after model/role changes.
 */
public final class CompanionAttachmentSyncSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 750L;

    private long nextSweepAtMs;

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
        store.forEachChunk(
                Query.and(npcType, attachmentsType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        TameworkAttachmentsComponent attachments = chunk.getComponent(i, attachmentsType);
                        if (ref == null || !ref.isValid() || attachments == null || attachments.getAttachmentIds().isEmpty()) {
                            continue;
                        }
                        candidates.add(ref);
                    }
                }
        );
        for (Ref<EntityStore> ref : candidates) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            CompanionAttachmentStateService.syncStoredAttachments(ref, store);
        }
    }
}
