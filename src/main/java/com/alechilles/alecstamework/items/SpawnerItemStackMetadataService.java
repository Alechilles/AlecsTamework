package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;

/**
 * Handles item-stack metadata mutations for spawner capture/spawn flows.
 */
final class SpawnerItemStackMetadataService {
    private final ItemFeatureRegistry registry;
    private final SpawnerCaptureMetadataService captureMetadataService;
    private final SpawnerNpcProgressionMetadataService progressionMetadataService;

    SpawnerItemStackMetadataService(ItemFeatureRegistry registry,
                                    SpawnerCaptureMetadataService captureMetadataService,
                                    SpawnerNpcProgressionMetadataService progressionMetadataService) {
        this.registry = registry;
        this.captureMetadataService = captureMetadataService;
        this.progressionMetadataService = progressionMetadataService;
    }

    ItemStack applyCooldown(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return itemStack;
        }
        long until = System.currentTimeMillis() + cooldownMs;
        return itemStack.withMetadata(key, Codec.LONG, until);
    }

    boolean isAlreadyCaptured(ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        String itemId = itemStack.getItemId();
        if (itemId != null && itemId.contains("_State_")) {
            return true;
        }
        Boolean captured = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN);
        return Boolean.TRUE.equals(captured);
    }

    boolean isFilledItem(ItemStack itemStack, ItemFeatureConfig config) {
        if (itemStack == null || itemStack.getItemId() == null) {
            return false;
        }
        String itemId = itemStack.getItemId();
        if (itemId.contains("_State_")) {
            return true;
        }
        Boolean captured = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN);
        if (captured != null) {
            return captured;
        }
        return false;
    }

    ItemStack swapItemId(ItemStack stack, String itemId) {
        if (stack == null || itemId == null || itemId.isBlank()) {
            return stack;
        }
        if (itemId.equals(stack.getItemId())) {
            return stack;
        }
        return new ItemStack(
                itemId,
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                stack.getMetadata()
        );
    }

    ItemStack clearCapturedMetadata(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.CAPTURED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TARGET_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TARGET_ENTITY_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.ATTACHMENTS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.OWNER_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TAMED);
        if (progressionMetadataService != null) {
            updated = progressionMetadataService.clearProgressionMetadata(updated);
        }
        updated = captureMetadataService.clearNameMetadata(updated);
        updated = updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, null);
        return updated;
    }

    String resolveEmptyItemId(String currentItemId) {
        if (currentItemId == null || registry == null) {
            return null;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(currentItemId);
        for (Map.Entry<String, ItemFeatureConfig> entry : registry.snapshot().entrySet()) {
            ItemFeatureConfig cfg = entry.getValue();
            if (cfg == null) {
                continue;
            }
            String filledId = cfg.getSpawnerFilledItemId();
            if (filledId == null || filledId.isBlank()) {
                continue;
            }
            String normalizedFilled = ItemFeatureRegistry.normalizeStateItemId(filledId);
            if (normalized != null && normalized.equals(normalizedFilled)) {
                return entry.getKey();
            }
        }
        return null;
    }

    ItemStack applyOwnerMetadata(ItemStack updated, UUID ownerUuid) {
        if (updated == null) {
            return null;
        }
        if (ownerUuid == null) {
            return clearMetadataKey(updated, TameworkMetadataKeys.OWNER_UUID);
        }
        return updated.withMetadata(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING, ownerUuid);
    }

    ItemStack clearMetadataKey(ItemStack stack, String key) {
        if (stack == null || key == null) {
            return stack;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copy = metadata.clone();
        copy.remove(key);
        return stack.withMetadata(copy);
    }
}
