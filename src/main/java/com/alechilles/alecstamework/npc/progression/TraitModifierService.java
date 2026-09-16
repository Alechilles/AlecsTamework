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
    private static final String SIZE_MULTIPLIER_EFFECT_KEY = "SizeMultiplier";
    private static final double SIZE_MEAT_HIDE_YIELD_LIMIT = 0.25;

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

    /** Resolves Size's bounded meat and hide yield delta without changing visual scale. */
    public static double resolveSizeMeatHideYieldBonus(@Nullable Ref<EntityStore> npcRef,
                                                        @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return 0.0;
        }
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent component = type == null ? null : store.getComponent(npcRef, type);
        return resolveSizeMeatHideYieldBonus(component, resolveTraitConfig(component, npcRef, store));
    }

    static double resolveSizeMeatHideYieldBonus(@Nullable TameworkTraitsComponent component,
                                                @Nullable TwTraitConfig config) {
        if (component == null || config == null) {
            return 0.0;
        }
        Map<String, TwTraitConfig.TraitDefinition> definitions = buildDefinitionMap(config);
        for (TameworkTraitsComponent.TraitValue value : component.getTraitValues()) {
            if (value == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = definitions.get(normalize(value.getId()));
            if (definition == null || !SIZE_MULTIPLIER_EFFECT_KEY.equalsIgnoreCase(definition.getEffectKey())) {
                continue;
            }
            return resolveSizeMeatHideYieldBonus(definition, value.getValue());
        }
        return 0.0;
    }

    /** Resolves one Size definition and stored value to its bounded meat/hide yield delta. */
    public static double resolveSizeMeatHideYieldBonus(@Nullable TwTraitConfig.TraitDefinition definition,
                                                        double value) {
        if (definition == null || !SIZE_MULTIPLIER_EFFECT_KEY.equalsIgnoreCase(definition.getEffectKey())) {
            return 0.0;
        }
        return resolveSignedMerit(definition, value) * SIZE_MEAT_HIDE_YIELD_LIMIT;
    }

    /** Returns a display-safe signed merit normalized against a trait's breeding endpoints. */
    public static double resolveSignedMerit(@Nullable TwTraitConfig.TraitDefinition definition, double value) {
        if (definition == null || !Double.isFinite(value)) {
            return 0.0;
        }
        int direction = resolveMeritDirection(definition.getEffectKey());
        if (direction == 0) {
            return 0.0;
        }
        double defaultValue = definition.getDefaultValue();
        double endpoint = value >= defaultValue ? definition.getBreedingMax() : definition.getBreedingMin();
        double denominator = Math.abs(endpoint - defaultValue);
        if (!Double.isFinite(defaultValue) || !Double.isFinite(endpoint) || denominator <= 0.0) {
            return 0.0;
        }
        return clamp((value - defaultValue) / denominator, -1.0, 1.0) * direction;
    }

    /** Identifies whether a productive trait is better when its value is higher or lower. */
    public static int resolveMeritDirection(@Nullable String effectKey) {
        if (effectKey == null || effectKey.isBlank()) {
            return 0;
        }
        return switch (effectKey.trim().toLowerCase(Locale.ROOT)) {
            case "happinessgainmultiplier", "fertilitymultiplier", "maxhealthmultiplier",
                    "fleecefiberyieldmultiplier", "animalproductyieldmultiplier",
                    "harvestrecoveryspeedmultiplier", "sizemultiplier" -> 1;
            case "needsdecaymultiplier", "needshungerdecaymultiplier", "needsthirstdecaymultiplier" -> -1;
            default -> 0;
        };
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
