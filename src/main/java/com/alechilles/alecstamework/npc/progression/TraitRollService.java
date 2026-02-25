package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Deterministic trait rolling for companion progression.
 */
public final class TraitRollService {
    private static final TameworkTraitsComponent.TraitValue[] EMPTY_VALUES =
            new TameworkTraitsComponent.TraitValue[0];

    private TraitRollService() {
    }

    public static TameworkTraitsComponent.TraitValue[] rollTraits(@Nullable TwTraitConfig config, long seed) {
        if (config == null || !config.isEnabled()) {
            return EMPTY_VALUES;
        }
        TwTraitConfig.TraitDefinition[] definitions = config.getTraits();
        if (definitions == null || definitions.length == 0) {
            return EMPTY_VALUES;
        }
        TwTraitConfig.SelectionSettings selection = config.getSelection();
        Random random = selection.isUseSeededRandom()
                ? new Random(seed)
                : new Random();
        int maxTraits = Math.max(0, config.getStacking().getMaxTraitsPerNpc());
        int rolls = resolveConfiguredRollCount(selection, random);
        if (maxTraits == 0 || rolls == 0) {
            return EMPTY_VALUES;
        }
        int targetCount = Math.min(maxTraits, rolls);
        ArrayList<TwTraitConfig.TraitDefinition> pool = new ArrayList<>(definitions.length);
        HashMap<String, TwTraitConfig.TraitDefinition> byId = new HashMap<>(definitions.length);
        for (TwTraitConfig.TraitDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            String normalizedId = normalize(definition.getId());
            if (normalizedId == null || byId.containsKey(normalizedId)) {
                continue;
            }
            byId.put(normalizedId, definition);
            pool.add(definition);
        }
        if (pool.isEmpty()) {
            return EMPTY_VALUES;
        }
        boolean allowDuplicates = config.getStacking().isAllowDuplicateTraits();
        boolean rerollDuplicates = selection.isRerollDuplicates();

        ArrayList<TameworkTraitsComponent.TraitValue> selected = new ArrayList<>(targetCount);
        int attempts = Math.max(pool.size() * 4, targetCount * 4);
        while (selected.size() < targetCount && !pool.isEmpty() && attempts-- > 0) {
            TwTraitConfig.TraitDefinition candidate = pickWeighted(pool, random);
            if (candidate == null) {
                break;
            }
            String candidateId = normalize(candidate.getId());
            if (candidateId == null) {
                removeFromPool(pool, candidateId);
                continue;
            }
            if (!allowDuplicates && containsTrait(selected, candidateId)) {
                if (rerollDuplicates) {
                    removeFromPool(pool, candidateId);
                    continue;
                }
                break;
            }
            if (conflictsWithSelected(candidate, selected, byId)) {
                removeFromPool(pool, candidateId);
                continue;
            }
            double value = rollValue(candidate, random);
            selected.add(new TameworkTraitsComponent.TraitValue(candidate.getId(), value));
            if (!allowDuplicates) {
                removeFromPool(pool, candidateId);
            }
            removeConflictingFromPool(pool, candidate, byId, !allowDuplicates);
        }
        return selected.isEmpty()
                ? EMPTY_VALUES
                : selected.toArray(new TameworkTraitsComponent.TraitValue[0]);
    }

    static int resolveMaxConfiguredRollCount(@Nullable TwTraitConfig.SelectionSettings selection) {
        if (selection == null) {
            return 0;
        }
        int fallback = Math.max(0, selection.getRollsPerSpawn());
        TwTraitConfig.RollCountWeights weights = selection.getRollCountWeights();
        if (weights == null) {
            return fallback;
        }
        if (sanitizeRollCountWeight(weights.getCount4()) > 0.0) {
            return 4;
        }
        if (sanitizeRollCountWeight(weights.getCount3()) > 0.0) {
            return 3;
        }
        if (sanitizeRollCountWeight(weights.getCount2()) > 0.0) {
            return 2;
        }
        if (sanitizeRollCountWeight(weights.getCount1()) > 0.0) {
            return 1;
        }
        if (sanitizeRollCountWeight(weights.getCount0()) > 0.0) {
            return 0;
        }
        return fallback;
    }

    static int resolveConfiguredRollCount(@Nullable TwTraitConfig.SelectionSettings selection,
                                          @Nullable Random random) {
        if (selection == null) {
            return 0;
        }
        int fallback = Math.max(0, selection.getRollsPerSpawn());
        TwTraitConfig.RollCountWeights weights = selection.getRollCountWeights();
        if (weights == null || random == null) {
            return fallback;
        }
        double weight0 = sanitizeRollCountWeight(weights.getCount0());
        double weight1 = sanitizeRollCountWeight(weights.getCount1());
        double weight2 = sanitizeRollCountWeight(weights.getCount2());
        double weight3 = sanitizeRollCountWeight(weights.getCount3());
        double weight4 = sanitizeRollCountWeight(weights.getCount4());
        double total = weight0 + weight1 + weight2 + weight3 + weight4;
        if (!(total > 0.0)) {
            return fallback;
        }
        double roll = random.nextDouble() * total;
        double cumulative = weight0;
        if (roll <= cumulative) {
            return 0;
        }
        cumulative += weight1;
        if (roll <= cumulative) {
            return 1;
        }
        cumulative += weight2;
        if (roll <= cumulative) {
            return 2;
        }
        cumulative += weight3;
        if (roll <= cumulative) {
            return 3;
        }
        return 4;
    }

    private static boolean containsTrait(List<TameworkTraitsComponent.TraitValue> values, String traitId) {
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (value == null) {
                continue;
            }
            String valueId = normalize(value.getId());
            if (valueId != null && valueId.equals(traitId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean conflictsWithSelected(TwTraitConfig.TraitDefinition candidate,
                                                 List<TameworkTraitsComponent.TraitValue> selected,
                                                 Map<String, TwTraitConfig.TraitDefinition> byId) {
        for (TameworkTraitsComponent.TraitValue value : selected) {
            if (value == null) {
                continue;
            }
            String selectedId = normalize(value.getId());
            if (selectedId == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition selectedDefinition = byId.get(selectedId);
            if (selectedDefinition == null) {
                continue;
            }
            if (isConflict(candidate, selectedDefinition) || isConflict(selectedDefinition, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConflict(TwTraitConfig.TraitDefinition source,
                                      TwTraitConfig.TraitDefinition target) {
        if (source == null || target == null) {
            return false;
        }
        String targetId = normalize(target.getId());
        if (targetId == null) {
            return false;
        }
        for (String conflictId : source.getConflictsWith()) {
            String normalized = normalize(conflictId);
            if (normalized != null && normalized.equals(targetId)) {
                return true;
            }
        }
        return false;
    }

    private static void removeFromPool(List<TwTraitConfig.TraitDefinition> pool, @Nullable String normalizedId) {
        if (pool == null || pool.isEmpty() || normalizedId == null) {
            return;
        }
        pool.removeIf(candidate -> normalizedId.equals(normalize(candidate.getId())));
    }

    private static void removeConflictingFromPool(List<TwTraitConfig.TraitDefinition> pool,
                                                  TwTraitConfig.TraitDefinition selected,
                                                  Map<String, TwTraitConfig.TraitDefinition> byId,
                                                  boolean removeSelectedId) {
        if (pool == null || pool.isEmpty() || selected == null || byId == null || byId.isEmpty()) {
            return;
        }
        Set<String> blocked = new HashSet<>();
        String selectedId = normalize(selected.getId());
        if (removeSelectedId && selectedId != null) {
            blocked.add(selectedId);
        }
        for (String conflictId : selected.getConflictsWith()) {
            String normalized = normalize(conflictId);
            if (normalized != null) {
                blocked.add(normalized);
            }
        }
        for (Map.Entry<String, TwTraitConfig.TraitDefinition> entry : byId.entrySet()) {
            String id = entry.getKey();
            TwTraitConfig.TraitDefinition definition = entry.getValue();
            if (id == null || definition == null) {
                continue;
            }
            if (isConflict(definition, selected)) {
                blocked.add(id);
            }
        }
        if (!blocked.isEmpty()) {
            pool.removeIf(candidate -> blocked.contains(normalize(candidate.getId())));
        }
    }

    @Nullable
    private static TwTraitConfig.TraitDefinition pickWeighted(List<TwTraitConfig.TraitDefinition> pool, Random random) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        double totalWeight = 0.0;
        for (TwTraitConfig.TraitDefinition definition : pool) {
            totalWeight += sanitizeWeight(definition.getWeight());
        }
        if (!(totalWeight > 0.0)) {
            return pool.get(random.nextInt(pool.size()));
        }
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (TwTraitConfig.TraitDefinition definition : pool) {
            cumulative += sanitizeWeight(definition.getWeight());
            if (roll <= cumulative) {
                return definition;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static double rollValue(TwTraitConfig.TraitDefinition definition, Random random) {
        double min = sanitizeRangeBound(definition.getMin(), definition.getDefaultValue());
        double max = sanitizeRangeBound(definition.getMax(), definition.getDefaultValue());
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (Math.abs(max - min) <= 0.000001) {
            return clamp(sanitizeRangeBound(definition.getDefaultValue(), min), min, max);
        }
        double value = min + (random.nextDouble() * (max - min));
        return clamp(value, min, max);
    }

    private static double sanitizeRangeBound(double value, double fallback) {
        if (Double.isFinite(value)) {
            return value;
        }
        return Double.isFinite(fallback) ? fallback : 0.0;
    }

    private static double sanitizeWeight(double weight) {
        return Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
    }

    private static double sanitizeRollCountWeight(double weight) {
        return Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
