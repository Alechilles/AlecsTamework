package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Resolves and applies model attachment-set selections for companion appearance flows.
 */
public final class CompanionModelAttachmentService {
    private CompanionModelAttachmentService() {
    }

    @Nullable
    public static ModelAsset resolveModelAsset(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ModelComponent component = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return null;
        }
        return ModelAsset.getAssetMap().getAsset(component.getModel().getModelAssetId());
    }

    public static Map<String, Set<String>> resolveAttachmentOptionIds(@Nullable ModelAsset modelAsset) {
        if (modelAsset == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, ModelAttachment>> sets = modelAsset.getRandomAttachmentSets();
        if (sets == null || sets.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, Set<String>> options = new HashMap<>();
        for (Map.Entry<String, Map<String, ModelAttachment>> setEntry : sets.entrySet()) {
            if (setEntry == null) {
                continue;
            }
            String setId = setEntry.getKey();
            if (setId == null || setId.isBlank()) {
                continue;
            }
            Map<String, ModelAttachment> values = setEntry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            HashSet<String> validValueIds = new HashSet<>();
            for (Map.Entry<String, ModelAttachment> valueEntry : values.entrySet()) {
                if (valueEntry == null) {
                    continue;
                }
                String valueId = valueEntry.getKey();
                ModelAttachment attachment = valueEntry.getValue();
                if (valueId == null || valueId.isBlank() || attachment == null) {
                    continue;
                }
                if (attachment.getModel() == null || attachment.getTexture() == null) {
                    continue;
                }
                validValueIds.add(valueId);
            }
            if (!validValueIds.isEmpty()) {
                options.put(setId, Collections.unmodifiableSet(validValueIds));
            }
        }
        return options.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(options);
    }

    public static Map<String, String> resolveCurrentAttachments(@Nullable Ref<EntityStore> npcRef,
                                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return Collections.emptyMap();
        }
        ModelComponent component = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return Collections.emptyMap();
        }
        return sanitizeAttachmentSelections(component.getModel().getRandomAttachmentIds());
    }

    public static Map<String, String> sanitizeAttachmentSelections(@Nullable Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> cleaned = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry == null) {
                continue;
            }
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                continue;
            }
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            cleaned.put(key, value);
        }
        return cleaned.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(cleaned);
    }

    public static Map<String, String> filterAttachmentSelections(@Nullable Map<String, String> rawSelections,
                                                                 @Nullable Map<String, Set<String>> attachmentOptions) {
        if (rawSelections == null || rawSelections.isEmpty() || attachmentOptions == null || attachmentOptions.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> filtered = new HashMap<>();
        for (Map.Entry<String, String> entry : rawSelections.entrySet()) {
            if (entry == null) {
                continue;
            }
            String setId = entry.getKey();
            String valueId = entry.getValue();
            if (setId == null || setId.isBlank() || valueId == null || valueId.isBlank()) {
                continue;
            }
            Set<String> options = attachmentOptions.get(setId);
            if (options == null || !options.contains(valueId)) {
                continue;
            }
            filtered.put(setId, valueId);
        }
        return filtered.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(filtered);
    }

    public static boolean applyAttachments(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable NPCEntity npc,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable Map<String, String> attachmentSelections) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ModelComponent component = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return false;
        }
        Model model = component.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(model.getModelAssetId());
        if (modelAsset == null) {
            return false;
        }
        Map<String, Set<String>> options = resolveAttachmentOptionIds(modelAsset);
        Map<String, String> filteredSelections = filterAttachmentSelections(attachmentSelections, options);
        Model updated = Model.createScaledModel(modelAsset, model.getScale(), filteredSelections);
        store.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(updated));
        if (npc != null && npc.getRole() != null) {
            npc.getRole().updateMotionControllers(npcRef, updated, updated.getBoundingBox(), store);
        }
        return true;
    }
}
