package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Converts the standard target HUD model into a detached public snapshot. */
final class CommandTargetHudSnapshotFactory {
    /** Creates a snapshot without a coordinator-owned target key. */
    @Nonnull
    CommandTargetHudSnapshot create(@Nonnull CommandTargetHudViewModel model) {
        return create(model, null);
    }

    /** Creates a snapshot bound to the coordinator's exact target key. */
    @Nonnull
    CommandTargetHudSnapshot create(
            @Nonnull CommandTargetHudViewModel model,
            @Nullable String targetKey
    ) {
        if (model == null) {
            throw new NullPointerException("model");
        }
        LinkedNpcEntry status = model.status();
        return new CommandTargetHudSnapshot(
                status.npcUuid(),
                targetKey,
                status.displayName(),
                status.speciesId(),
                status.speciesLabel(),
                status.gender(),
                lifecycleStatus(status),
                vitals(status),
                status.happinessModifierBreakdown(),
                cooldowns(status),
                food(model.favoriteFood()),
                foods(model.compatibleFoods()),
                attachments(model.attachments()),
                tameRequirement(model.tameRequirement()),
                progression(status),
                traits(status.traitIndicators()),
                model.ownerDisplayName()
        );
    }

    @Nonnull
    private static CommandTargetHudSnapshot.Vitals vitals(@Nonnull LinkedNpcEntry status) {
        return new CommandTargetHudSnapshot.Vitals(
                status.hasHealth() ? status.currentHealth() : null,
                status.hasHealth() ? status.maxHealth() : null,
                status.hasHappiness() ? status.currentHappiness() : null,
                status.hasHappiness() ? status.maxHappiness() : null,
                status.hasHappiness() ? status.targetHappinessPercent() : null,
                status.hasHunger() ? status.currentHunger() : null,
                status.hasHunger() ? status.maxHunger() : null,
                status.hasThirst() ? status.currentThirst() : null,
                status.hasThirst() ? status.maxThirst() : null
        );
    }

    @Nonnull
    private static CommandTargetHudSnapshot.Cooldowns cooldowns(
            @Nonnull LinkedNpcEntry status
    ) {
        return new CommandTargetHudSnapshot.Cooldowns(
                cooldown(status.harvestCooldownKnown(),
                        status.harvestCooldownActive(),
                        status.harvestCooldownRemainingMs(),
                        status.harvestCooldownRatio()),
                cooldown(status.breedingCooldownKnown(),
                        status.breedingCooldownActive(),
                        status.breedingCooldownRemainingMs(),
                        status.breedingCooldownRatio())
        );
    }

    @Nonnull
    private static CommandTargetHudSnapshot.Cooldown cooldown(
            boolean known,
            boolean active,
            long remainingMillis,
            double ratio
    ) {
        return known
                ? new CommandTargetHudSnapshot.Cooldown(
                        active, remainingMillis, ratio, true)
                : new CommandTargetHudSnapshot.Cooldown(null, null, null, false);
    }

    @Nullable
    private static CommandTargetHudSnapshot.FoodRow food(
            @Nullable CommandTargetHudViewModel.FoodRow value
    ) {
        return value == null ? null : new CommandTargetHudSnapshot.FoodRow(
                value.itemId(), value.displayName(), value.iconPath(),
                value.happinessDelta());
    }

    @Nonnull
    private static List<CommandTargetHudSnapshot.FoodRow> foods(
            @Nullable List<CommandTargetHudViewModel.FoodRow> values
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<CommandTargetHudSnapshot.FoodRow> result = new ArrayList<>(values.size());
        for (CommandTargetHudViewModel.FoodRow value : values) {
            if (value != null) {
                result.add(food(value));
            }
        }
        return List.copyOf(result);
    }

    @Nonnull
    private static List<CommandTargetHudSnapshot.AttachmentRow> attachments(
            @Nullable List<CommandTargetHudViewModel.AttachmentRow> values
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<CommandTargetHudSnapshot.AttachmentRow> result = new ArrayList<>(values.size());
        for (CommandTargetHudViewModel.AttachmentRow value : values) {
            if (value != null) {
                result.add(new CommandTargetHudSnapshot.AttachmentRow(
                        value.setLabel(), value.valueLabel()));
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private static CommandTargetHudSnapshot.TameRequirement tameRequirement(
            @Nullable CommandTargetHudViewModel.TameRequirementRow value
    ) {
        return value == null ? null : new CommandTargetHudSnapshot.TameRequirement(
                value.tranquilizerRequired(), value.requiredStacks(),
                value.currentStacksText());
    }

    @Nonnull
    private static CommandTargetHudSnapshot.Progression progression(
            @Nonnull LinkedNpcEntry status
    ) {
        LinkedNpcEntry.FutureStat experience = status.futureStatA();
        LinkedNpcEntry.FutureStat talentPoints = status.futureStatB();
        Integer level = experience == null ? null : parseLevel(experience.label());
        Long currentExperience = experience == null
                ? null : (long) experience.current();
        Long experienceToNextLevel = experience == null
                ? null : (long) experience.max();
        Integer availableTalentPoints = talentPoints == null
                ? null : Math.max(0, talentPoints.current());
        Integer maxLevel = experience == null
                ? null : parseMaxLevel(experience.tooltipHeaderText());
        Boolean atMaxLevel = experience == null
                ? null : isMaxLevel(experience);
        return new CommandTargetHudSnapshot.Progression(
                level,
                currentExperience,
                experienceToNextLevel,
                availableTalentPoints,
                maxLevel,
                atMaxLevel,
                experience == null ? null : experience.tooltipHeaderText(),
                experience == null ? null : experience.tooltipText()
        );
    }

    @Nullable
    private static Integer parseMaxLevel(@Nullable String tooltipHeader) {
        if (tooltipHeader == null || tooltipHeader.isBlank()) {
            return null;
        }
        int colon = tooltipHeader.indexOf(':');
        int slash = tooltipHeader.indexOf('/', colon + 1);
        if (colon < 0 || slash < 0 || slash + 1 >= tooltipHeader.length()) {
            return null;
        }
        int end = slash + 1;
        while (end < tooltipHeader.length()
                && Character.isDigit(tooltipHeader.charAt(end))) {
            end++;
        }
        if (end == slash + 1) {
            return null;
        }
        try {
            return Integer.valueOf(tooltipHeader.substring(slash + 1, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isMaxLevel(@Nonnull LinkedNpcEntry.FutureStat experience) {
        String label = experience.label();
        return label != null && label.toUpperCase(java.util.Locale.ROOT).contains("MAX");
    }

    @Nullable
    static Integer parseLevel(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String[] parts = label.trim().split("\\s+");
        for (int index = 0; index + 1 < parts.length; index++) {
            if ("level".equalsIgnoreCase(parts[index])) {
                Integer parsed = parsePositiveInteger(parts[index + 1]);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        for (String part : parts) {
            Integer parsed = parsePositiveInteger(part);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @Nullable
    private static Integer parsePositiveInteger(@Nullable String value) {
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static List<CommandTargetHudSnapshot.Trait> traits(
            @Nullable LinkedNpcTraitIndicator[] indicators
    ) {
        if (indicators == null || indicators.length == 0) {
            return List.of();
        }
        List<CommandTargetHudSnapshot.Trait> result = new ArrayList<>(indicators.length);
        for (int index = 0; index < indicators.length; index++) {
            LinkedNpcTraitIndicator indicator = indicators[index];
            if (indicator == null) {
                continue;
            }
            String id = "trait-" + index;
            result.add(new CommandTargetHudSnapshot.Trait(
                    id,
                    indicator.label(),
                    indicator.iconTexturePath(),
                    indicator.iconText(),
                    indicator.tooltipText(),
                    indicator.fillRatio(),
                    indicator.counterClockwise(),
                    indicator.belowDefault()
            ));
        }
        return List.copyOf(result);
    }

    @Nonnull
    static String lifecycleStatus(@Nonnull LinkedNpcEntry status) {
        if (status.dead()) {
            return "dead";
        }
        if (status.captured()) {
            return "captured";
        }
        if (status.inCoop()) {
            return "in_coop";
        }
        if (status.lost()) {
            return "lost";
        }
        return status.loaded() ? "loaded" : "unloaded";
    }
}
