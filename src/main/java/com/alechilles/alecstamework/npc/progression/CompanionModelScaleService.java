package com.alechilles.alecstamework.npc.progression;

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
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Applies runtime model scaling to companions while preserving model attachments.
 */
public final class CompanionModelScaleService {
    private static final double MIN_SCALE = 0.10;

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
        Model scaled = Model.createScaledModel(modelAsset, (float) safeScale, resolveModelAttachments(current));
        store.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(scaled));
        if (npc != null && npc.getRole() != null) {
            npc.getRole().updateMotionControllers(npcRef, scaled, scaled.getBoundingBox(), store);
        }
        return true;
    }

    private static double sanitizeScale(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, value);
    }

    private static Map<String, String> resolveModelAttachments(@Nullable Model model) {
        if (model == null) {
            return Collections.emptyMap();
        }
        Map<String, String> byReflection = tryReadAttachmentsMap(model, "getAdditionalAttachments");
        if (!byReflection.isEmpty()) {
            return byReflection;
        }
        byReflection = tryReadAttachmentsMap(model, "getAttachments");
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
                HashMap<String, String> converted = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof String val) {
                        converted.put(key, val);
                    }
                }
                return converted;
            }
        } catch (ReflectiveOperationException ignored) {
            return Collections.emptyMap();
        }
        return Collections.emptyMap();
    }
}
