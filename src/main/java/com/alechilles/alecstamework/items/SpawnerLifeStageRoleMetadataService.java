package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Preserves exact lifecycle role lineage across handheld capture and release. */
final class SpawnerLifeStageRoleMetadataService {
    private SpawnerLifeStageRoleMetadataService() {
    }

    static ItemStack capture(ItemStack stack, TameworkLifeStageComponent component) {
        Map<String, String> captured = captureRoleIds(component);
        ItemStack updated = applyOptionalString(
                stack, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID,
                captured.get(TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID)
        );
        updated = applyOptionalString(
                updated, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID,
                captured.get(TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID)
        );
        return applyOptionalString(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID,
                captured.get(TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID));
    }

    static Map<String, String> captureRoleIds(TameworkLifeStageComponent component) {
        Map<String, String> captured = new LinkedHashMap<>();
        putIfPresent(captured, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID, component.getAdultRoleId());
        putIfPresent(captured, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID, component.getBabyRoleId());
        putIfPresent(
                captured, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID, component.getAdolescentRoleId()
        );
        return captured;
    }

    static ItemStack clear(ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID);
        return clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID);
    }

    static boolean hasSavedRoleIds(ItemStack stack) {
        return hasText(read(stack, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID))
                || hasText(read(stack, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID))
                || hasText(read(stack, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID));
    }

    static void restore(ItemStack stack,
                        TameworkLifeStageComponent restored,
                        @Nullable TameworkLifeStageComponent existing) {
        restore(readRoleIds(stack), read(stack, TameworkMetadataKeys.CAPTURE_ROLE_ID), restored, existing);
    }

    static void restore(Map<String, String> savedRoleIds,
                        @Nullable String capturedRoleId,
                        TameworkLifeStageComponent restored,
                        @Nullable TameworkLifeStageComponent existing) {
        String stage = restored.getStage();
        restored.setAdultRoleId(resolveRoleId(
                savedRoleIds, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID,
                existing != null ? existing.getAdultRoleId() : null,
                capturedRoleId, "Adult".equalsIgnoreCase(stage)
        ));
        restored.setBabyRoleId(resolveRoleId(
                savedRoleIds, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID,
                existing != null ? existing.getBabyRoleId() : null,
                capturedRoleId, "Baby".equalsIgnoreCase(stage)
        ));
        restored.setAdolescentRoleId(resolveRoleId(
                savedRoleIds, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID,
                existing != null ? existing.getAdolescentRoleId() : null,
                capturedRoleId, "Adolescent".equalsIgnoreCase(stage)
        ));
    }

    @Nullable
    private static String resolveRoleId(Map<String, String> savedRoleIds,
                                        String key,
                                        @Nullable String existingRoleId,
                                        @Nullable String capturedRoleId,
                                        boolean capturedAtThisStage) {
        String savedRoleId = savedRoleIds.get(key);
        if (hasText(savedRoleId)) {
            return savedRoleId;
        }
        if (hasText(existingRoleId)) {
            return existingRoleId;
        }
        return capturedAtThisStage && hasText(capturedRoleId) ? capturedRoleId : null;
    }

    @Nullable
    private static String read(ItemStack stack, String key) {
        return stack.getFromMetadataOrNull(key, Codec.STRING);
    }

    private static Map<String, String> readRoleIds(ItemStack stack) {
        Map<String, String> saved = new LinkedHashMap<>();
        putIfPresent(saved, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID,
                read(stack, TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID));
        putIfPresent(saved, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID,
                read(stack, TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID));
        putIfPresent(saved, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID,
                read(stack, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID));
        return saved;
    }

    private static void putIfPresent(Map<String, String> target, String key, @Nullable String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private static ItemStack applyOptionalString(ItemStack stack, String key, @Nullable String value) {
        return hasText(value) ? stack.withMetadata(key, Codec.STRING, value) : clearMetadataKey(stack, key);
    }

    private static ItemStack clearMetadataKey(ItemStack stack, String key) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copy = metadata.clone();
        copy.remove(key);
        return stack.withMetadata(copy);
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
