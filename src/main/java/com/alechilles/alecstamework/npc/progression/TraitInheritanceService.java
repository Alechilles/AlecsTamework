package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nullable;

/**
 * Builds offspring trait values by blending parent traits with deterministic roll fallback.
 */
public final class TraitInheritanceService {
    private static final TameworkTraitsComponent.TraitValue[] EMPTY_VALUES =
            new TameworkTraitsComponent.TraitValue[0];
    private static final double EPSILON = 0.000001;

    private TraitInheritanceService() {
    }

    public static TameworkTraitsComponent.TraitValue[] inheritTraits(@Nullable TwTraitConfig config,
                                                                     @Nullable TameworkTraitsComponent parentA,
                                                                     @Nullable TameworkTraitsComponent parentB,
                                                                     long seed) {
        TameworkTraitsComponent.TraitValue[] fallback = TraitRollService.rollTraits(config, seed);
        if (config == null || !config.isEnabled()) {
            return fallback;
        }
        TwTraitConfig.InheritanceSettings inheritance = config.getInheritance();
        if (!inheritance.isAllowInheritance()) {
            return fallback;
        }

        List<ParentTraitCandidate> parentCandidates = buildParentCandidates(config, parentA, parentB);
        if (parentCandidates.isEmpty()) {
            return fallback;
        }

        Random random = config.getSelection().isUseSeededRandom()
                ? new Random(seed ^ 0x7A3D43F4A57E2D1BL)
                : new Random();
        int targetCount = resolveTargetCount(config, fallback, parentCandidates);
        if (targetCount <= 0) {
            return EMPTY_VALUES;
        }

        boolean allowDuplicates = config.getStacking().isAllowDuplicateTraits();
        List<TwTraitConfig.TraitDefinition> definitions = List.of(config.getTraits());
        Map<String, TwTraitConfig.TraitDefinition> definitionById = buildDefinitionMap(definitions);
        List<TameworkTraitsComponent.TraitValue> selected = new ArrayList<>(targetCount);
        if (!inheritance.isPreferParentTraits()) {
            appendCandidates(selected, fallback, targetCount, definitionById, allowDuplicates);
        }

        Collections.shuffle(parentCandidates, random);
        for (ParentTraitCandidate parent : parentCandidates) {
            if (selected.size() >= targetCount) {
                break;
            }
            if (random.nextDouble() > clamp01(inheritance.getInheritanceChance())) {
                continue;
            }
            appendCandidate(
                    selected,
                    parent.id(),
                    parent.value(),
                    parent.definition(),
                    targetCount,
                    definitionById,
                    allowDuplicates,
                    clamp01(inheritance.getMutationChance()),
                    random
            );
        }

        appendCandidates(selected, fallback, targetCount, definitionById, allowDuplicates);
        return selected.isEmpty()
                ? EMPTY_VALUES
                : selected.toArray(new TameworkTraitsComponent.TraitValue[0]);
    }

    private static int resolveTargetCount(TwTraitConfig config,
                                          TameworkTraitsComponent.TraitValue[] fallback,
                                          List<ParentTraitCandidate> parentCandidates) {
        int maxTraits = Math.max(0, config.getStacking().getMaxTraitsPerNpc());
        int rolls = Math.max(0, TraitRollService.resolveMaxConfiguredRollCount(config.getSelection()));
        int cap = Math.min(maxTraits, rolls);
        if (cap <= 0) {
            return 0;
        }
        int fallbackCount = fallback != null ? fallback.length : 0;
        int parentCount = parentCandidates != null ? parentCandidates.size() : 0;
        int desired = Math.max(fallbackCount, parentCount);
        if (desired <= 0) {
            desired = cap;
        }
        return Math.min(cap, desired);
    }

    private static void appendCandidates(List<TameworkTraitsComponent.TraitValue> selected,
                                         @Nullable TameworkTraitsComponent.TraitValue[] values,
                                         int targetCount,
                                         Map<String, TwTraitConfig.TraitDefinition> definitionById,
                                         boolean allowDuplicates) {
        if (values == null || values.length == 0 || selected.size() >= targetCount) {
            return;
        }
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (selected.size() >= targetCount) {
                return;
            }
            if (value == null || value.getId() == null || value.getId().isBlank()) {
                continue;
            }
            String normalized = normalize(value.getId());
            if (normalized == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = definitionById.get(normalized);
            appendCandidate(
                    selected,
                    value.getId(),
                    value.getValue(),
                    definition,
                    targetCount,
                    definitionById,
                    allowDuplicates,
                    0.0,
                    null
            );
        }
    }

    private static void appendCandidate(List<TameworkTraitsComponent.TraitValue> selected,
                                        String id,
                                        double rawValue,
                                        @Nullable TwTraitConfig.TraitDefinition definition,
                                        int targetCount,
                                        Map<String, TwTraitConfig.TraitDefinition> definitionById,
                                        boolean allowDuplicates,
                                        double mutationChance,
                                        @Nullable Random random) {
        if (selected == null || selected.size() >= targetCount || id == null || id.isBlank()) {
            return;
        }
        String normalizedId = normalize(id);
        if (normalizedId == null) {
            return;
        }
        int existingIndex = findById(selected, normalizedId);
        if (!allowDuplicates && existingIndex >= 0) {
            selected.set(existingIndex, new TameworkTraitsComponent.TraitValue(
                    id,
                    clampToDefinition(applyMutation(rawValue, definition, mutationChance, random), definition)
            ));
            return;
        }
        if (definition != null && conflictsWithSelected(normalizedId, definition, selected, definitionById)) {
            return;
        }
        selected.add(new TameworkTraitsComponent.TraitValue(
                id,
                clampToDefinition(applyMutation(rawValue, definition, mutationChance, random), definition)
        ));
    }

    private static int findById(List<TameworkTraitsComponent.TraitValue> selected, String normalizedId) {
        for (int i = 0; i < selected.size(); i++) {
            TameworkTraitsComponent.TraitValue existing = selected.get(i);
            String existingId = existing != null ? normalize(existing.getId()) : null;
            if (normalizedId.equals(existingId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean conflictsWithSelected(String candidateId,
                                                 TwTraitConfig.TraitDefinition candidate,
                                                 List<TameworkTraitsComponent.TraitValue> selected,
                                                 Map<String, TwTraitConfig.TraitDefinition> definitionById) {
        for (TameworkTraitsComponent.TraitValue value : selected) {
            if (value == null) {
                continue;
            }
            String selectedId = normalize(value.getId());
            if (selectedId == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition selectedDefinition = definitionById.get(selectedId);
            if (selectedDefinition == null) {
                continue;
            }
            if (isConflict(candidate, selectedId) || isConflict(selectedDefinition, candidateId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConflict(TwTraitConfig.TraitDefinition source, String targetId) {
        if (source == null || targetId == null || targetId.isBlank()) {
            return false;
        }
        for (String conflictId : source.getConflictsWith()) {
            String normalized = normalize(conflictId);
            if (targetId.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, TwTraitConfig.TraitDefinition> buildDefinitionMap(
            List<TwTraitConfig.TraitDefinition> definitions) {
        HashMap<String, TwTraitConfig.TraitDefinition> map = new HashMap<>();
        if (definitions == null || definitions.isEmpty()) {
            return map;
        }
        for (TwTraitConfig.TraitDefinition definition : definitions) {
            if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                continue;
            }
            String normalized = normalize(definition.getId());
            if (normalized != null) {
                map.putIfAbsent(normalized, definition);
            }
        }
        return map;
    }

    private static List<ParentTraitCandidate> buildParentCandidates(TwTraitConfig config,
                                                                    @Nullable TameworkTraitsComponent parentA,
                                                                    @Nullable TameworkTraitsComponent parentB) {
        HashMap<String, ParentValueAccumulator> accumulators = new HashMap<>();
        Map<String, TwTraitConfig.TraitDefinition> definitions =
                buildDefinitionMap(List.of(config.getTraits()));
        appendParentValues(accumulators, definitions, parentA);
        appendParentValues(accumulators, definitions, parentB);
        if (accumulators.isEmpty()) {
            return List.of();
        }
        ArrayList<ParentTraitCandidate> out = new ArrayList<>(accumulators.size());
        for (Map.Entry<String, ParentValueAccumulator> entry : accumulators.entrySet()) {
            String id = entry.getKey();
            ParentValueAccumulator accumulator = entry.getValue();
            if (accumulator == null || accumulator.count <= 0) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = definitions.get(id);
            if (definition == null) {
                continue;
            }
            double value = accumulator.count == 1
                    ? accumulator.sum
                    : accumulator.sum / accumulator.count;
            out.add(new ParentTraitCandidate(definition.getId(), clampToDefinition(value, definition), definition));
        }
        return out;
    }

    private static void appendParentValues(Map<String, ParentValueAccumulator> accumulators,
                                           Map<String, TwTraitConfig.TraitDefinition> definitions,
                                           @Nullable TameworkTraitsComponent component) {
        if (component == null || component.getTraitValues().length == 0) {
            return;
        }
        for (TameworkTraitsComponent.TraitValue value : component.getTraitValues()) {
            if (value == null || value.getId() == null || value.getId().isBlank() || !Double.isFinite(value.getValue())) {
                continue;
            }
            String id = normalize(value.getId());
            if (id == null || !definitions.containsKey(id)) {
                continue;
            }
            ParentValueAccumulator accumulator = accumulators.computeIfAbsent(id, key -> new ParentValueAccumulator());
            accumulator.sum += value.getValue();
            accumulator.count++;
        }
    }

    private static double applyMutation(double value,
                                        @Nullable TwTraitConfig.TraitDefinition definition,
                                        double mutationChance,
                                        @Nullable Random random) {
        if (definition == null || random == null || mutationChance <= EPSILON) {
            return value;
        }
        if (random.nextDouble() > mutationChance) {
            return value;
        }
        double min = finiteOrDefault(definition.getMin(), definition.getDefaultValue());
        double max = finiteOrDefault(definition.getMax(), definition.getDefaultValue());
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (Math.abs(max - min) <= EPSILON) {
            return min;
        }
        return min + (random.nextDouble() * (max - min));
    }

    private static double clampToDefinition(double value, @Nullable TwTraitConfig.TraitDefinition definition) {
        if (definition == null) {
            return value;
        }
        double min = finiteOrDefault(definition.getMin(), definition.getDefaultValue());
        double max = finiteOrDefault(definition.getMax(), definition.getDefaultValue());
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double finiteOrDefault(double value, double fallback) {
        if (Double.isFinite(value)) {
            return value;
        }
        return Double.isFinite(fallback) ? fallback : 0.0;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
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

    private static final class ParentValueAccumulator {
        private double sum;
        private int count;
    }

    private record ParentTraitCandidate(String id, double value, TwTraitConfig.TraitDefinition definition) {
    }
}
