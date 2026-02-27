package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Keeps persisted attachment selections applied to runtime NPC models across role/model changes.
 */
public final class CompanionAttachmentStateService {
    private CompanionAttachmentStateService() {
    }

    public static void syncStoredAttachments(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> type = TameworkAttachmentsComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkAttachmentsComponent persisted = store.getComponent(npcRef, type);
        if (persisted == null || persisted.getAttachmentIds().isEmpty()) {
            return;
        }

        Map<String, Set<String>> attachmentOptions = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(npcRef, store)
        );
        if (attachmentOptions.isEmpty()) {
            return;
        }
        Map<String, String> expected = CompanionModelAttachmentService.filterAttachmentSelections(
                persisted.getAttachmentIds(),
                attachmentOptions
        );
        if (expected.isEmpty()) {
            return;
        }
        Map<String, String> current = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        if (current.equals(expected)) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        CompanionModelAttachmentService.applyAttachments(npcRef, npc, store, expected);
    }
}
