package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves trait-driven numeric multipliers for gameplay systems.
 */
public final class TraitModifierService {
    private TraitModifierService() {
    }

    public static double resolveMultiplier(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable String effectKey,
                                           double defaultMultiplier) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return defaultMultiplier;
        }
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        if (type == null) {
            return defaultMultiplier;
        }
        TameworkTraitsComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return defaultMultiplier;
        }
        TwTraitConfig config = resolveTraitConfig(component, npcRef, store);
        return resolveMultiplier(component, config, effectKey, defaultMultiplier);
    }

    public static double resolveMultiplier(@Nullable TameworkTraitsComponent component,
                                           @Nullable TwTraitConfig config,
                                           @Nullable String effectKey,
                                           double defaultMultiplier) {
        if (component == null || config == null || effectKey == null || effectKey.isBlank()) {
            return defaultMultiplier;
        }
        Map<String, TwTraitConfig.TraitDefinition> definitionById = buildDefinitionMap(config);
        if (definitionById.isEmpty()) {
            return defaultMultiplier;
        }
        double multiplier = defaultMultiplier;
        boolean matched = false;
        for (TameworkTraitsComponent.TraitValue value : component.getTraitValues()) {
            if (value == null) {
                continue;
            }
            String normalizedId = normalize(value.getId());
            if (normalizedId == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = definitionById.get(normalizedId);
            if (definition == null) {
                continue;
            }
            String definitionEffect = definition.getEffectKey();
            if (definitionEffect == null || !definitionEffect.equalsIgnoreCase(effectKey)) {
                continue;
            }
            double traitValue = value.getValue();
            if (!Double.isFinite(traitValue)) {
                continue;
            }
            matched = true;
            multiplier *= traitValue;
        }
        return matched ? multiplier : defaultMultiplier;
    }

    @Nullable
    private static TwTraitConfig resolveTraitConfig(@Nullable TameworkTraitsComponent component,
                                                    @Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId != null && !roleId.isBlank()) {
            TwTraitConfig byRole = TwTraitConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }
        if (component != null) {
            String configId = component.getConfigId();
            if (configId != null && !configId.isBlank()) {
                TwTraitConfig config = TwTraitConfig.resolveById(configId);
                if (config != null) {
                    return config;
                }
            }
        }
        return null;
    }

    private static Map<String, TwTraitConfig.TraitDefinition> buildDefinitionMap(TwTraitConfig config) {
        HashMap<String, TwTraitConfig.TraitDefinition> map = new HashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null) {
                continue;
            }
            String normalized = normalize(definition.getId());
            if (normalized == null || map.containsKey(normalized)) {
                continue;
            }
            map.put(normalized, definition);
        }
        return map;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
