package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.progression.CompanionModelScaleService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Applies captured model attachments from spawner item metadata onto spawned NPCs.
 */
final class SpawnerAttachmentService {
    private static final Gson GSON = new Gson();
    private static final Type ATTACHMENT_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final HytaleLogger logger;

    SpawnerAttachmentService(HytaleLogger logger) {
        this.logger = logger;
    }

    void applyAttachments(ItemStack itemStack,
                          Ref<EntityStore> npcRef,
                          NPCEntity npc,
                          Store<EntityStore> store) {
        if (itemStack == null || npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        String attachmentsJson = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.ATTACHMENTS,
                Codec.STRING
        );
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return;
        }
        Map<String, String> attachments;
        try {
            attachments = GSON.fromJson(attachmentsJson, ATTACHMENT_MAP_TYPE);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Spawner stub: failed to parse attachment metadata.");
            return;
        }
        if (attachments == null) {
            return;
        }

        ModelComponent modelComponent = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (modelComponent == null) {
            return;
        }
        Model model = modelComponent.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(model.getModelAssetId());
        if (modelAsset == null) {
            logger.at(Level.WARNING).log("Spawner stub: missing model asset for attachments.");
            return;
        }

        Map<String, String> applied = new HashMap<>(attachments);
        Model updatedModel = CompanionModelScaleService.createScaledModel(
                npcRef,
                store,
                modelAsset,
                model.getScale(),
                applied
        );
        if (updatedModel == null) {
            return;
        }
        store.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(updatedModel));
        if (npc != null && npc.getRole() != null) {
            npc.getRole().updateMotionControllers(npcRef, updatedModel, updatedModel.getBoundingBox(), store);
        }
    }
}
