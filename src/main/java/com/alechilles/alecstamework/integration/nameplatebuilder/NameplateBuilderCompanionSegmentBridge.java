package com.alechilles.alecstamework.integration.nameplatebuilder;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import com.frotty27.nameplatebuilder.api.NameplateAPI;
import com.frotty27.nameplatebuilder.api.SegmentBuilder;
import com.frotty27.nameplatebuilder.api.SegmentTarget;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Registers optional NameplateBuilder segments for companion progression and tranquilizer state.
 */
public final class NameplateBuilderCompanionSegmentBridge {
    private static final String HAPPINESS_SHORT_LABEL = "Hap";
    private static final String HUNGER_SHORT_LABEL = "Hun";
    private static final String THIRST_SHORT_LABEL = "Thi";
    private static final String HAPPINESS_SEGMENT_ID = "happiness";
    private static final String HUNGER_SEGMENT_ID = "hunger";
    private static final String THIRST_SEGMENT_ID = "thirst";
    private static final String TRANQUILIZER_SEGMENT_ID = "tranquilizer";
    private static final String TRAITS_SEGMENT_ID = "traits";
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final int UNRESOLVED_EFFECT_INDEX = Integer.MIN_VALUE;
    private static final int HAPPINESS_CACHE_TICKS = 20;
    private static final int NEEDS_CACHE_TICKS = 20;
    private static final int TRAITS_CACHE_TICKS = 20;
    private static volatile int tranquilizerEffectIndex = UNRESOLVED_EFFECT_INDEX;

    private NameplateBuilderCompanionSegmentBridge() {
    }

    public static void initialize(Tamework plugin) {
        registerHappinessSegment(plugin);
        registerHungerSegment(plugin);
        registerThirstSegment(plugin);
        registerTranquilizerSegment(plugin);
        registerTraitsSegment(plugin);
    }

    private static void registerHappinessSegment(Tamework plugin) {
        SegmentBuilder builder = NameplateAPI.define(plugin, HAPPINESS_SEGMENT_ID, "Happiness", SegmentTarget.NPCS, "87%");
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType != null) {
            builder = builder.requires(happinessType);
        }
        builder.cacheTicks(HAPPINESS_CACHE_TICKS)
                .resolver(NameplateBuilderCompanionSegmentBridge::resolveHappinessText);
        NameplateAPI.defineVariants(
                plugin,
                HAPPINESS_SEGMENT_ID,
                List.of("Percent", "Current/Max", "Short + Percent", "Short + Current/Max")
        );
    }

    private static void registerHungerSegment(Tamework plugin) {
        SegmentBuilder builder = NameplateAPI.define(plugin, HUNGER_SEGMENT_ID, "Hunger", SegmentTarget.NPCS, "72%");
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType != null) {
            builder = builder.requires(needsType);
        }
        builder.cacheTicks(NEEDS_CACHE_TICKS)
                .resolver(NameplateBuilderCompanionSegmentBridge::resolveHungerText);
        NameplateAPI.defineVariants(
                plugin,
                HUNGER_SEGMENT_ID,
                List.of("Percent", "Current/Max", "Short + Percent", "Short + Current/Max")
        );
    }

    private static void registerThirstSegment(Tamework plugin) {
        SegmentBuilder builder = NameplateAPI.define(plugin, THIRST_SEGMENT_ID, "Thirst", SegmentTarget.NPCS, "61%");
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType != null) {
            builder = builder.requires(needsType);
        }
        builder.cacheTicks(NEEDS_CACHE_TICKS)
                .resolver(NameplateBuilderCompanionSegmentBridge::resolveThirstText);
        NameplateAPI.defineVariants(
                plugin,
                THIRST_SEGMENT_ID,
                List.of("Percent", "Current/Max", "Short + Percent", "Short + Current/Max")
        );
    }

    private static void registerTranquilizerSegment(Tamework plugin) {
        SegmentBuilder builder = NameplateAPI.define(
                plugin,
                TRANQUILIZER_SEGMENT_ID,
                "Tranquilizer",
                SegmentTarget.NPCS,
                "3 (1m 45s)"
        );
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType != null) {
            builder = builder.requires(effectType);
        }
        builder.resolver(NameplateBuilderCompanionSegmentBridge::resolveTranquilizerText);
        NameplateAPI.defineVariants(plugin, TRANQUILIZER_SEGMENT_ID, List.of("Stacks + Time", "Stacks", "Time"));
    }

    private static void registerTraitsSegment(Tamework plugin) {
        SegmentBuilder builder = NameplateAPI.define(plugin, TRAITS_SEGMENT_ID, "Traits", SegmentTarget.NPCS, "Tou 80%, Agi -50%");
        ComponentType<EntityStore, TameworkTraitsComponent> traitType = TameworkTraitsComponent.getComponentType();
        if (traitType != null) {
            builder = builder.requires(traitType);
        }
        builder.cacheTicks(TRAITS_CACHE_TICKS)
                .resolver(NameplateBuilderCompanionSegmentBridge::resolveTraitsText);
        NameplateAPI.defineVariants(
                plugin,
                TRAITS_SEGMENT_ID,
                List.of(
                        "Shortened + Relative %",
                        "Shortened + Raw Value",
                        "Full + Relative %",
                        "Full + Raw Value"
                )
        );
    }

    @Nullable
    private static String resolveHappinessText(Store<EntityStore> store, Ref<EntityStore> entityRef, int variantIndex) {
        BoundedValue value = resolveHappinessValue(store, entityRef);
        return formatBoundedValue(value, variantIndex, HAPPINESS_SHORT_LABEL);
    }

    @Nullable
    private static String resolveHungerText(Store<EntityStore> store, Ref<EntityStore> entityRef, int variantIndex) {
        BoundedValue value = resolveNeedValue(store, entityRef, true);
        return formatBoundedValue(value, variantIndex, HUNGER_SHORT_LABEL);
    }

    @Nullable
    private static String resolveThirstText(Store<EntityStore> store, Ref<EntityStore> entityRef, int variantIndex) {
        BoundedValue value = resolveNeedValue(store, entityRef, false);
        return formatBoundedValue(value, variantIndex, THIRST_SHORT_LABEL);
    }

    @Nullable
    private static String resolveTranquilizerText(Store<EntityStore> store, Ref<EntityStore> entityRef, int variantIndex) {
        if (store == null || entityRef == null || !entityRef.isValid()) {
            return null;
        }
        int effectIndex = resolveTranquilizerEffectIndex();
        if (effectIndex == UNRESOLVED_EFFECT_INDEX) {
            return null;
        }
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return null;
        }
        EffectControllerComponent effectController = store.getComponent(entityRef, effectType);
        if (effectController == null) {
            return null;
        }
        Int2ObjectMap<ActiveEntityEffect> activeEffects = effectController.getActiveEffects();
        if (activeEffects == null || activeEffects.isEmpty()) {
            return null;
        }
        ActiveEntityEffect activeEffect = activeEffects.get(effectIndex);
        if (activeEffect == null) {
            return null;
        }
        double peakRemainingSeconds = resolveTrackedTranquilizerPeakSeconds(store, entityRef, activeEffect);
        if (activeEffect.isInfinite()) {
            int stacks = TranquilizerStackDisplayService.computeStacks(peakRemainingSeconds);
            return formatTranquilizerValue(stacks, "inf", variantIndex);
        }
        double remainingSeconds = activeEffect.getRemainingDuration();
        if (!Double.isFinite(remainingSeconds) || remainingSeconds <= 0.0) {
            return null;
        }
        int stacks = TranquilizerStackDisplayService.computeStacks(
                TranquilizerStackDisplayService.resolvePeakDuration(peakRemainingSeconds, remainingSeconds)
        );
        return formatTranquilizerValue(stacks, formatRemainingDuration(remainingSeconds), variantIndex);
    }

    @Nullable
    private static String resolveTraitsText(Store<EntityStore> store, Ref<EntityStore> entityRef, int variantIndex) {
        return resolveTraitText(store, entityRef, variantIndex);
    }

    @Nullable
    private static String resolveTraitText(Store<EntityStore> store,
                                           Ref<EntityStore> entityRef,
                                           int variantIndex) {
        if (store == null || entityRef == null || !entityRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkTraitsComponent> traitType = TameworkTraitsComponent.getComponentType();
        if (traitType == null) {
            return null;
        }
        TameworkTraitsComponent traits = store.getComponent(entityRef, traitType);
        if (traits == null) {
            return null;
        }
        TwTraitConfig config = resolveTraitConfig(entityRef, store, traits);
        if (config == null) {
            return null;
        }
        Map<String, Double> rolledValues = buildRolledValueMap(traits);
        if (rolledValues.isEmpty()) {
            return null;
        }
        TwTraitConfig.TraitDefinition[] definitions = config.getTraits();
        if (definitions.length == 0) {
            return null;
        }
        ArrayList<String> segments = null;
        for (TwTraitConfig.TraitDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            String traitId = normalize(definition.getId());
            if (traitId == null) {
                continue;
            }
            Double value = rolledValues.get(traitId);
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            String text = formatTraitSegment(definition, value, variantIndex);
            if (text != null && !text.isBlank()) {
                if (segments == null) {
                    segments = new ArrayList<>(Math.min(definitions.length, rolledValues.size()));
                }
                segments.add(text);
            }
        }
        return segments == null || segments.isEmpty() ? null : String.join(", ", segments);
    }

    @Nullable
    private static BoundedValue resolveHappinessValue(@Nullable Store<EntityStore> store,
                                                      @Nullable Ref<EntityStore> entityRef) {
        if (store == null || entityRef == null || !entityRef.isValid()) {
            return null;
        }
        CompanionHappinessService.HappinessSnapshot snapshot = CompanionHappinessService.resolveSnapshot(entityRef, store);
        if (snapshot == null) {
            return null;
        }
        double current = clamp(snapshot.value(), snapshot.min(), snapshot.max());
        double max = Math.max(1.0, snapshot.max());
        int roundedMax = Math.max(1, Math.round((float) max));
        int roundedCurrent = Math.max(0, Math.min(roundedMax, Math.round((float) current)));
        int percent = computePercent(current, snapshot.min(), snapshot.max());
        return new BoundedValue(roundedCurrent, roundedMax, percent);
    }

    @Nullable
    private static BoundedValue resolveNeedValue(@Nullable Store<EntityStore> store,
                                                 @Nullable Ref<EntityStore> entityRef,
                                                 boolean hunger) {
        if (store == null || entityRef == null || !entityRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        TameworkNeedsComponent needs = store.getComponent(entityRef, needsType);
        if (needs == null) {
            return null;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(entityRef, store, needs);
        if (config == null || !config.isEnabled() || config.getValues() == null) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double min = hunger ? values.getHungerMin() : values.getThirstMin();
        double max = hunger ? values.getHungerMax() : values.getThirstMax();
        double current = hunger ? needs.getHunger() : needs.getThirst();
        int roundedMax = Math.max(1, Math.round((float) max));
        int roundedCurrent = Math.max(0, Math.min(roundedMax, Math.round((float) clamp(current, min, max))));
        int percent = computePercent(current, min, max);
        return new BoundedValue(roundedCurrent, roundedMax, percent);
    }

    @Nullable
    static String formatBoundedValue(@Nullable BoundedValue value, int variantIndex, @Nullable String shortLabel) {
        if (value == null) {
            return null;
        }
        return switch (variantIndex) {
            case 1 -> value.current() + "/" + value.max();
            case 2 -> shortLabel + " " + value.percent() + "%";
            case 3 -> shortLabel + " " + value.current() + "/" + value.max();
            default -> value.percent() + "%";
        };
    }

    static int computePercent(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0;
        }
        double ratio = (clamp(value, min, max) - min) / (max - min);
        return Math.max(0, Math.min(100, Math.round((float) (ratio * 100.0))));
    }

    static String formatRemainingDuration(double remainingSeconds) {
        return TranquilizerStackDisplayService.formatRemainingDuration(remainingSeconds);
    }

    static int computeTranquilizerStacks(double initialDurationSeconds) {
        return TranquilizerStackDisplayService.computeStacks(initialDurationSeconds);
    }

    static double resolvePeakDuration(double trackedPeakSeconds, double currentRemainingSeconds) {
        return TranquilizerStackDisplayService.resolvePeakDuration(trackedPeakSeconds, currentRemainingSeconds);
    }

    @Nullable
    static String formatTranquilizerValue(int stacks, @Nullable String remainingText, int variantIndex) {
        return TranquilizerStackDisplayService.formatStackValue(stacks, remainingText, variantIndex);
    }

    @Nullable
    private static TwTraitConfig resolveTraitConfig(@Nullable Ref<EntityStore> entityRef,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable TameworkTraitsComponent traits) {
        if (traits != null) {
            String configId = traits.getConfigId();
            if (configId != null && !configId.isBlank()) {
                TwTraitConfig config = TwTraitConfig.resolveById(configId);
                if (config != null) {
                    return config;
                }
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(entityRef, store);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwTraitConfig.resolveForRole(roleId);
    }

    private static Map<String, Double> buildRolledValueMap(@Nullable TameworkTraitsComponent traits) {
        HashMap<String, Double> values = new HashMap<>();
        if (traits == null) {
            return values;
        }
        for (TameworkTraitsComponent.TraitValue traitValue : traits.getTraitValues()) {
            if (traitValue == null) {
                continue;
            }
            String traitId = normalize(traitValue.getId());
            if (traitId == null || values.containsKey(traitId)) {
                continue;
            }
            double value = traitValue.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            values.put(traitId, value);
        }
        return values;
    }

    @Nullable
    private static String formatTraitSegment(@Nullable TwTraitConfig.TraitDefinition definition,
                                             double value,
                                             int variantIndex) {
        if (definition == null || !Double.isFinite(value)) {
            return null;
        }
        String label = resolveTraitLabel(definition);
        String shortLabel = abbreviateLabel(label);
        return switch (variantIndex) {
            case 1 -> shortLabel + " " + formatDecimal(value);
            case 2 -> label + " " + formatTraitRelativePercent(
                    value,
                    Math.min(definition.getBreedingMin(), definition.getBreedingMax()),
                    definition.getDefaultValue(),
                    Math.max(definition.getBreedingMin(), definition.getBreedingMax())
            );
            case 3 -> label + " " + formatDecimal(value);
            default -> shortLabel + " " + formatTraitRelativePercent(
                    value,
                    Math.min(definition.getBreedingMin(), definition.getBreedingMax()),
                    definition.getDefaultValue(),
                    Math.max(definition.getBreedingMin(), definition.getBreedingMax())
            );
        };
    }

    private static String resolveTraitLabel(@Nullable TwTraitConfig.TraitDefinition definition) {
        if (definition == null) {
            return "Trait";
        }
        return LocalizedText.resolveConfigValue(null, definition.getDisplayName(), resolveTraitFallbackLabel(definition));
    }

    private static String resolveTraitFallbackLabel(@Nullable TwTraitConfig.TraitDefinition definition) {
        if (definition != null && definition.getId() != null && !definition.getId().isBlank()) {
            return definition.getId();
        }
        return "Trait";
    }

    static String formatTraitRelativePercent(double value, double min, double defaultValue, double max) {
        double safeMin = Double.isFinite(min) ? min : 0.0;
        double safeMax = Double.isFinite(max) ? max : 0.0;
        if (safeMax < safeMin) {
            double swap = safeMin;
            safeMin = safeMax;
            safeMax = swap;
        }
        double safeDefault = clamp(defaultValue, safeMin, safeMax);
        double safeValue = clamp(value, safeMin, safeMax);
        boolean belowDefault = safeValue < safeDefault;
        double normalized = belowDefault
                ? ratioToLowerBound(safeValue, safeMin, safeDefault)
                : ratioToUpperBound(safeValue, safeDefault, safeMax);
        return formatTraitPercent(normalized, belowDefault);
    }

    private static double ratioToUpperBound(double value, double defaultValue, double max) {
        double distance = max - defaultValue;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((value - defaultValue) / distance, 0.0, 1.0);
    }

    private static double ratioToLowerBound(double value, double min, double defaultValue) {
        double distance = defaultValue - min;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((defaultValue - value) / distance, 0.0, 1.0);
    }

    private static String formatDecimal(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String abbreviateLabel(@Nullable String label) {
        if (label == null) {
            return "?";
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        return trimmed.length() <= 3 ? trimmed : trimmed.substring(0, 3);
    }

    private static String formatTraitPercent(double ratio, boolean negativeDirection) {
        if (!Double.isFinite(ratio)) {
            return "0%";
        }
        int percent = (int) Math.round(clamp(ratio, 0.0, 1.0) * 100.0);
        if (negativeDirection && percent > 0) {
            return "-" + percent + "%";
        }
        return percent + "%";
    }

    private static double resolveTrackedTranquilizerPeakSeconds(Store<EntityStore> store,
                                                                Ref<EntityStore> entityRef,
                                                                ActiveEntityEffect activeEffect) {
        ComponentType<EntityStore, TameworkTranquilizerPeakComponent> peakType =
                TameworkTranquilizerPeakComponent.getComponentType();
        double trackedPeak = 0.0;
        if (peakType != null) {
            TameworkTranquilizerPeakComponent peakComponent = store.getComponent(entityRef, peakType);
            if (peakComponent != null) {
                trackedPeak = peakComponent.getPeakRemainingSeconds();
            }
        }
        if (activeEffect == null || activeEffect.isInfinite()) {
            return trackedPeak;
        }
        return TranquilizerStackDisplayService.resolvePeakDuration(trackedPeak, activeEffect.getRemainingDuration());
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int resolveTranquilizerEffectIndex() {
        if (tranquilizerEffectIndex != UNRESOLVED_EFFECT_INDEX) {
            return tranquilizerEffectIndex;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectMap = EntityEffect.getAssetMap();
        if (effectMap == null) {
            return UNRESOLVED_EFFECT_INDEX;
        }
        int resolved = effectMap.getIndex(TRANQUILIZER_EFFECT_ID);
        if (resolved != UNRESOLVED_EFFECT_INDEX) {
            tranquilizerEffectIndex = resolved;
        }
        return resolved;
    }

    record BoundedValue(int current, int max, int percent) {
    }
}
