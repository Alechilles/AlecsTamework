package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
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
        seedStoredAttachments(npcRef, store, false);
    }

    public static void seedStoredAttachmentsOnLoad(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        seedStoredAttachments(npcRef, store, true);
    }

    private static void seedStoredAttachments(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              boolean forceApplyPersistedSelections) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> type = TameworkAttachmentsComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkAttachmentsComponent persisted = store.getComponent(npcRef, type);
        if (persisted != null && !persisted.getAttachmentIds().isEmpty()) {
            Map<String, String> expected = resolveSupportedSelections(npcRef, store, persisted.getAttachmentIds());
            if (expected.isEmpty()) {
                return;
            }
            boolean persistedChanged = !expected.equals(persisted.getAttachmentIds());
            if (persistedChanged) {
                persistAttachments(npcRef, store, type, persisted.getConfigId(), expected);
            }
            Map<String, String> current = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
            if (forceApplyPersistedSelections
                    || shouldApplyResolvedSelections(persistedChanged, expected, current)) {
                applyResolvedSelections(npcRef, store, expected);
            }
            return;
        }

        Map<String, String> rawCurrent = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        Map<String, String> current = resolveCurrentSupportedSelections(npcRef, store);
        if (current.isEmpty()) {
            return;
        }
        persistAttachments(npcRef, store, type, persisted != null ? persisted.getConfigId() : null, current);
        if (!current.equals(rawCurrent)) {
            applyResolvedSelections(npcRef, store, current);
        }
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
        persistAttachments(npcRef, store, type, persisted != null ? persisted.getConfigId() : null, current);
        applyResolvedSelections(npcRef, store, current);
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
        boolean persistedChanged = !expected.equals(persisted.getAttachmentIds());
        if (persistedChanged) {
            persistAttachments(npcRef, store, type, persisted.getConfigId(), expected);
        }
        Map<String, String> current = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        if (!shouldApplyResolvedSelections(persistedChanged, expected, current)) {
            return;
        }
        applyResolvedSelections(npcRef, store, expected);
    }

    static boolean shouldApplyResolvedSelections(boolean persistedChanged,
                                                 @Nullable Map<String, String> expected,
                                                 @Nullable Map<String, String> current) {
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        return persistedChanged || current == null || !expected.equals(current);
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
        Map<String, String> filtered = CompanionModelAttachmentService.filterAttachmentSelections(selections, attachmentOptions);
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwAttachmentMigrationConfig migrationConfig = TwAttachmentMigrationConfig.resolveForRole(roleId);
        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                migrationConfig,
                filtered,
                attachmentOptions
        );
        return CompanionModelAttachmentService.filterAttachmentSelections(migrated, attachmentOptions);
    }

    private static void persistAttachments(@Nonnull Ref<EntityStore> npcRef,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull ComponentType<EntityStore, TameworkAttachmentsComponent> type,
                                           @Nullable String configId,
                                           @Nonnull Map<String, String> attachments) {
        store.putComponent(npcRef, type, new TameworkAttachmentsComponent(configId, attachments));
    }

    private static void applyResolvedSelections(@Nonnull Ref<EntityStore> npcRef,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull Map<String, String> attachments) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        CompanionModelAttachmentService.applyAttachments(npcRef, npc, store, attachments);
    }
}
