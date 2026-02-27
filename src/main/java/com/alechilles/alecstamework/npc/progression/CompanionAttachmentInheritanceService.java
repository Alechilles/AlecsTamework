package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves deterministic offspring attachment selections from parent attachment maps.
 */
public final class CompanionAttachmentInheritanceService {
    private static final String RANDOM_SENTINEL = "__random__";
    private static final long ATTACHMENT_RANDOM_SALT = 0x4BCDA7E21F98D9ABL;

    private CompanionAttachmentInheritanceService() {
    }

    public static Map<String, String> resolveInheritedSelections(@Nullable Map<String, String> parentAAttachments,
                                                                 @Nullable Map<String, String> parentBAttachments,
                                                                 @Nullable Map<String, Set<String>> attachmentOptions,
                                                                 long seed,
                                                                 @Nullable AttachmentInheritanceProfile profile) {
        if (attachmentOptions == null || attachmentOptions.isEmpty()) {
            return Collections.emptyMap();
        }
        AttachmentInheritanceProfile resolvedProfile = profile == null
                ? AttachmentInheritanceProfile.disabled()
                : profile;
        if (!resolvedProfile.enabled()) {
            return Collections.emptyMap();
        }

        Map<String, String> safeParentA = CompanionModelAttachmentService.sanitizeAttachmentSelections(parentAAttachments);
        Map<String, String> safeParentB = CompanionModelAttachmentService.sanitizeAttachmentSelections(parentBAttachments);
        Random random = new Random(seed ^ ATTACHMENT_RANDOM_SALT);
        List<String> setIds = new ArrayList<>(attachmentOptions.keySet());
        Collections.sort(setIds);

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String setId : setIds) {
            if (setId == null || setId.isBlank()) {
                continue;
            }
            Set<String> valueSet = attachmentOptions.get(setId);
            if (valueSet == null || valueSet.isEmpty()) {
                continue;
            }
            List<String> orderedValues = new ArrayList<>(valueSet);
            Collections.sort(orderedValues);
            if (orderedValues.isEmpty()) {
                continue;
            }

            String parentAValue = resolveParentSelection(safeParentA, setId, valueSet);
            String parentBValue = resolveParentSelection(safeParentB, setId, valueSet);
            String selected = resolveSelection(
                    parentAValue,
                    parentBValue,
                    orderedValues,
                    resolvedProfile,
                    random
            );
            if (selected != null && !selected.isBlank()) {
                out.put(setId, selected);
            }
        }

        return out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(out);
    }

    @Nullable
    private static String resolveSelection(@Nullable String parentAValue,
                                           @Nullable String parentBValue,
                                           @Nonnull List<String> orderedValues,
                                           @Nonnull AttachmentInheritanceProfile profile,
                                           @Nonnull Random random) {
        if (orderedValues.isEmpty()) {
            return null;
        }
        boolean hasParentValue = (parentAValue != null && !parentAValue.isBlank())
                || (parentBValue != null && !parentBValue.isBlank());
        if (!hasParentValue) {
            return pickRandomValue(orderedValues, random);
        }

        if (profile.mutationChance() > 0.0 && random.nextDouble() < profile.mutationChance()) {
            return pickRandomValue(orderedValues, random);
        }

        LinkedHashMap<String, Double> weightedChoices = new LinkedHashMap<>();
        if (parentAValue != null && !parentAValue.isBlank()) {
            addWeight(weightedChoices, parentAValue, profile.parentWeight());
        }
        if (parentBValue != null && !parentBValue.isBlank()) {
            addWeight(weightedChoices, parentBValue, profile.parentWeight());
        }
        addWeight(weightedChoices, RANDOM_SENTINEL, profile.randomWeight());

        String choice = weightedPick(weightedChoices, random);
        if (choice == null || RANDOM_SENTINEL.equals(choice)) {
            return pickRandomValue(orderedValues, random);
        }
        return choice;
    }

    @Nullable
    private static String weightedPick(@Nonnull Map<String, Double> weightedChoices,
                                       @Nonnull Random random) {
        if (weightedChoices.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (Double weight : weightedChoices.values()) {
            if (weight != null && Double.isFinite(weight) && weight > 0.0) {
                total += weight;
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double roll = random.nextDouble() * total;
        double cumulative = 0.0;
        for (Map.Entry<String, Double> entry : weightedChoices.entrySet()) {
            if (entry == null || entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() <= 0.0) {
                continue;
            }
            cumulative += entry.getValue();
            if (roll <= cumulative) {
                return entry.getKey();
            }
        }
        return weightedChoices.keySet().stream().reduce((first, second) -> second).orElse(null);
    }

    private static void addWeight(@Nonnull Map<String, Double> weightedChoices,
                                  @Nullable String selectionId,
                                  double weight) {
        if (selectionId == null || selectionId.isBlank() || !Double.isFinite(weight) || weight <= 0.0) {
            return;
        }
        weightedChoices.merge(selectionId, weight, Double::sum);
    }

    @Nullable
    private static String resolveParentSelection(@Nonnull Map<String, String> parentSelections,
                                                 @Nullable String setId,
                                                 @Nonnull Set<String> allowedValues) {
        if (setId == null || setId.isBlank()) {
            return null;
        }
        String selection = parentSelections.get(setId);
        if (selection == null || selection.isBlank()) {
            return null;
        }
        return allowedValues.contains(selection) ? selection : null;
    }

    @Nullable
    private static String pickRandomValue(@Nonnull List<String> orderedValues, @Nonnull Random random) {
        if (orderedValues.isEmpty()) {
            return null;
        }
        int index = random.nextInt(orderedValues.size());
        return orderedValues.get(index);
    }

    /**
     * Immutable inheritance profile derived from breeding config.
     */
    public record AttachmentInheritanceProfile(boolean enabled,
                                               double parentWeight,
                                               double randomWeight,
                                               double mutationChance) {
        public static AttachmentInheritanceProfile fromConfig(@Nullable TwBreedingConfig.InheritanceSettings settings) {
            if (settings == null || !settings.isInheritAttachments()) {
                return disabled();
            }
            TwBreedingConfig.AttachmentInheritanceSettings attachment = settings.getAttachmentInheritance();
            return new AttachmentInheritanceProfile(
                    true,
                    sanitizeWeight(attachment.getParentWeight(), 1.0),
                    sanitizeWeight(attachment.getRandomWeight(), 0.25),
                    sanitizeMutationChance(attachment.getMutationChance())
            );
        }

        public static AttachmentInheritanceProfile disabled() {
            return new AttachmentInheritanceProfile(false, 0.0, 0.0, 0.0);
        }

        private static double sanitizeWeight(double value, double fallback) {
            if (!Double.isFinite(value) || value < 0.0) {
                return fallback;
            }
            return value;
        }

        private static double sanitizeMutationChance(double value) {
            if (!Double.isFinite(value) || value <= 0.0) {
                return 0.0;
            }
            if (value >= 1.0) {
                return 1.0;
            }
            return value;
        }
    }
}
