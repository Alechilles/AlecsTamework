package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps persisted attachment selections applied to runtime NPC models across role/model changes.
 */
public final class CompanionAttachmentStateService {
    private CompanionAttachmentStateService() {
    }

    public static void seedStoredAttachments(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> type = TameworkAttachmentsComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkAttachmentsComponent persisted = store.getComponent(npcRef, type);
        if (persisted != null && !persisted.getAttachmentIds().isEmpty()) {
            return;
        }

        Map<String, String> current = resolveCurrentSupportedSelections(npcRef, store);
        if (current.isEmpty()) {
            return;
        }
        store.putComponent(
                npcRef,
                type,
                new TameworkAttachmentsComponent(persisted != null ? persisted.getConfigId() : null, current)
        );
    }

    public static void replaceStoredAttachmentsWithCurrent(@Nullable Ref<EntityStore> npcRef,
                                                           @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> type = TameworkAttachmentsComponent.getComponentType();
        if (type == null) {
            return;
        }

        Map<String, String> current = resolveCurrentSupportedSelections(npcRef, store);
        if (current.isEmpty()) {
            return;
        }
        TameworkAttachmentsComponent persisted = store.getComponent(npcRef, type);
        if (persisted != null && current.equals(persisted.getAttachmentIds())) {
            return;
        }
        store.putComponent(
                npcRef,
                type,
                new TameworkAttachmentsComponent(persisted != null ? persisted.getConfigId() : null, current)
        );
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

        Map<String, String> expected = resolveSupportedSelections(npcRef, store, persisted.getAttachmentIds());
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

    @Nonnull
    private static Map<String, String> resolveCurrentSupportedSelections(@Nullable Ref<EntityStore> npcRef,
                                                                         @Nullable Store<EntityStore> store) {
        return resolveSupportedSelections(
                npcRef,
                store,
                CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store)
        );
    }

    @Nonnull
    private static Map<String, String> resolveSupportedSelections(@Nullable Ref<EntityStore> npcRef,
                                                                  @Nullable Store<EntityStore> store,
                                                                  @Nullable Map<String, String> selections) {
        Map<String, Set<String>> attachmentOptions = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(npcRef, store)
        );
        if (attachmentOptions.isEmpty()) {
            return Map.of();
        }
        return CompanionModelAttachmentService.filterAttachmentSelections(selections, attachmentOptions);
    }
}
