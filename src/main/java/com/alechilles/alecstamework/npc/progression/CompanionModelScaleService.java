package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Applies runtime model scaling to companions while preserving model attachments.
 */
public final class CompanionModelScaleService {
    private static final double MIN_SCALE = 0.10;
    private static final double MIN_JUVENILE_COLLISION_SCALE = 0.70;

    private CompanionModelScaleService() {
    }

    public static double resolveCurrentScale(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             double fallback) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return sanitizeScale(fallback);
        }
        ModelComponent component = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return sanitizeScale(fallback);
        }
        return sanitizeScale(component.getModel().getScale());
    }

    public static boolean applyScale(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable NPCEntity npc,
                                     @Nullable Store<EntityStore> store,
                                     double targetScale) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ModelComponent component = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return false;
        }
        Model current = component.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(current.getModelAssetId());
        if (modelAsset == null) {
            return false;
        }
        double safeScale = sanitizeScale(targetScale);
        if (Math.abs(current.getScale() - safeScale) < 0.0001) {
            return false;
        }
        Model scaled = createScaledModel(
                npcRef,
                store,
                modelAsset,
                safeScale,
                resolveModelAttachments(current)
        );
        if (scaled == null) {
            return false;
        }
        store.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(scaled));
        if (npc != null && npc.getRole() != null) {
            npc.getRole().updateMotionControllers(npcRef, scaled, scaled.getBoundingBox(), store);
        }
        return true;
    }

    @Nullable
    public static Model createScaledModel(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable ModelAsset modelAsset,
                                          double targetScale,
                                          @Nullable Map<String, String> attachments) {
        if (modelAsset == null) {
            return null;
        }
        double safeScale = sanitizeScale(targetScale);
        Map<String, String> safeAttachments = attachments == null ? Collections.emptyMap() : attachments;
        if (!isJuvenileLifeStage(npcRef, store)) {
            return Model.createScaledModel(modelAsset, (float) safeScale, safeAttachments);
        }
        double collisionScale = Math.max(safeScale, MIN_JUVENILE_COLLISION_SCALE);
        if (Math.abs(collisionScale - safeScale) < 0.0001) {
            return Model.createScaledModel(modelAsset, (float) safeScale, safeAttachments);
        }
        Model collisionProbe = Model.createScaledModel(modelAsset, (float) collisionScale, safeAttachments);
        if (collisionProbe == null || collisionProbe.getBoundingBox() == null) {
            return Model.createScaledModel(modelAsset, (float) safeScale, safeAttachments);
        }
        return Model.createScaledModel(modelAsset, (float) safeScale, safeAttachments, collisionProbe.getBoundingBox());
    }

    private static double sanitizeScale(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, value);
    }

    private static boolean isJuvenileLifeStage(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
        if (stage == null) {
            return false;
        }
        String normalized = normalizeStage(stage.getStage());
        return CompanionLifeStageService.STAGE_BABY.equals(normalized)
                || CompanionLifeStageService.STAGE_ADOLESCENT.equals(normalized);
    }

    @Nullable
    private static String normalizeStage(@Nullable String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if ("baby".equals(normalized)) {
            return CompanionLifeStageService.STAGE_BABY;
        }
        if ("adolescent".equals(normalized) || "juvenile".equals(normalized)) {
            return CompanionLifeStageService.STAGE_ADOLESCENT;
        }
        if ("adult".equals(normalized)) {
            return CompanionLifeStageService.STAGE_ADULT;
        }
        return null;
    }

    private static Map<String, String> resolveModelAttachments(@Nullable Model model) {
        if (model == null) {
            return Collections.emptyMap();
        }
        Map<String, String> direct = sanitizeAttachmentMap(model.getRandomAttachmentIds());
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, String> byReflection = tryReadAttachmentsMap(model, "getAdditionalAttachments");
        if (!byReflection.isEmpty()) {
            return byReflection;
        }
        byReflection = tryReadAttachmentsMap(model, "getRandomAttachmentIds");
        if (!byReflection.isEmpty()) {
            return byReflection;
        }
        return Collections.emptyMap();
    }

    private static Map<String, String> tryReadAttachmentsMap(Model model, String methodName) {
        try {
            Method method = model.getClass().getMethod(methodName);
            Object value = method.invoke(model);
            if (value instanceof Map<?, ?> map) {
                return sanitizeAttachmentMap(map);
            }
        } catch (ReflectiveOperationException ignored) {
            return Collections.emptyMap();
        }
        return Collections.emptyMap();
    }

    private static Map<String, String> sanitizeAttachmentMap(@Nullable Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> converted = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof String val) {
                converted.put(key, val);
            }
        }
        return converted;
    }
}
