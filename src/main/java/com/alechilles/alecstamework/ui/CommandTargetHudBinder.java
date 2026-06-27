package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Binds compact command-target HUD data into the passive right-side overlay. */
final class CommandTargetHudBinder {
    private static final int MAX_ATTACHMENT_ROWS = 6;
    private static final int MAX_COMPATIBLE_FOOD_ICONS = 3;

    private CommandTargetHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder,
                     @Nonnull CommandTargetHudViewModel model,
                     @Nullable String language) {
        LinkedNpcEntry status = model.status();
        commandBuilder.set("#Root.Visible", true);
        commandBuilder.set("#GenderMaleIcon.Visible", status.isMale());
        commandBuilder.set("#GenderFemaleIcon.Visible", status.isFemale());
        commandBuilder.set("#Name.Text", safe(status.displayName(), LocalizedText.resolve(language, "tamework.ui.commandTargetHud.name.unknown")));
        LinkedNpcPanelVitalsBinder.bind(commandBuilder, "#Root", status, language);
        bindStatusRingVisibility(commandBuilder, status);
        bindProgression(commandBuilder, status);
        bindTraitRings(commandBuilder, status.traitIndicators());
        bindFood(commandBuilder, model.favoriteFood(), model.compatibleFoods(), language);
        bindAttachments(commandBuilder, model.attachments());
        bindTameRequirement(commandBuilder, model.tameRequirement(), language);
    }

    private static void bindStatusRingVisibility(UICommandBuilder commandBuilder, LinkedNpcEntry status) {
        boolean hasHappiness = status.hasHappiness();
        boolean hasHunger = status.hasHunger();
        boolean hasThirst = status.hasThirst();
        boolean hasBreedingCooldown = status.breedingCooldownKnown() && status.breedingCooldownActive();
        boolean hasHarvestCooldown = status.harvestCooldownKnown() && status.harvestCooldownActive();
        commandBuilder.set("#NeedHappiness.Visible", hasHappiness);
        commandBuilder.set("#NeedHunger.Visible", hasHunger);
        commandBuilder.set("#NeedThirst.Visible", hasThirst);
        commandBuilder.set("#StatusRingRow.Visible", hasHappiness || hasHunger || hasThirst || hasBreedingCooldown || hasHarvestCooldown);
    }

    private static void bindProgression(UICommandBuilder commandBuilder, LinkedNpcEntry status) {
        boolean hasLevel = status.futureStatA() != null;
        boolean hasTalentPoints = LinkedNpcPanelProgressionBinder.availableTalentPoints(status.futureStatB()) > 0;
        commandBuilder.set("#ProgressionRow.Visible", hasLevel || hasTalentPoints);
        LinkedNpcPanelProgressionBinder.bindXpProgressRing(
                commandBuilder,
                "#XpProgressRing",
                "#XpProgressRing #XpLevelText",
                "#XpProgressRing #XpTooltip",
                status.futureStatA()
        );
        LinkedNpcPanelProgressionBinder.bindTalentPointIndicator(
                commandBuilder,
                "#TalentPointAction",
                "#TalentPointAction #TalentPointCount",
                "#TalentPointAction #TalentPointCountShadow",
                status.futureStatB(),
                hasTalentPoints
        );
    }

    private static void bindTraitRings(UICommandBuilder commandBuilder, LinkedNpcTraitIndicator[] indicators) {
        LinkedNpcTraitIndicator[] safeIndicators = indicators == null ? LinkedNpcTraitIndicator.EMPTY : indicators;
        commandBuilder.set("#TraitRingRow.Visible", safeIndicators.length > 0);
        LinkedNpcTraitIndicatorBinder.bind(commandBuilder, "#Root", safeIndicators);
    }

    private static void bindFood(UICommandBuilder commandBuilder,
                                 @Nullable CommandTargetHudViewModel.FoodRow food,
                                 @Nonnull List<CommandTargetHudViewModel.FoodRow> compatibleFoods,
                                 @Nullable String language) {
        boolean hasCompatibleFoods = !compatibleFoods.isEmpty();
        commandBuilder.set("#FoodRow.Visible", food != null || hasCompatibleFoods);
        commandBuilder.set("#FavoriteFoodBlock.Visible", food != null);
        commandBuilder.set("#CompatibleFoodBlock.Visible", hasCompatibleFoods);
        if (food == null && !hasCompatibleFoods) {
            return;
        }
        if (food != null) {
            commandBuilder.set("#FoodLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.favoriteFood"));
            commandBuilder.set("#FoodName.Text", food.displayName());
            boolean hasIcon = food.iconPath() != null && !food.iconPath().isBlank();
            commandBuilder.set("#FoodIcon.Visible", hasIcon);
            if (hasIcon) {
                commandBuilder.set("#FoodIcon.Background", food.iconPath());
            }
        }
        if (hasCompatibleFoods) {
            commandBuilder.set("#CompatibleFoodLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.compatibleFoods"));
            bindCompatibleFoodIcons(commandBuilder, compatibleFoods);
        }
    }

    private static void bindCompatibleFoodIcons(@Nonnull UICommandBuilder commandBuilder,
                                                @Nonnull List<CommandTargetHudViewModel.FoodRow> foods) {
        int rendered = 0;
        int maxIcons = foods.size() > MAX_COMPATIBLE_FOOD_ICONS
                ? MAX_COMPATIBLE_FOOD_ICONS - 1
                : MAX_COMPATIBLE_FOOD_ICONS;
        for (CommandTargetHudViewModel.FoodRow food : foods) {
            if (food == null || food.iconPath() == null || food.iconPath().isBlank()) {
                continue;
            }
            if (rendered >= maxIcons) {
                break;
            }
            String selector = "#CompatibleFoodIcon" + rendered;
            commandBuilder.set(selector + ".Visible", true);
            commandBuilder.set(selector + ".Background", food.iconPath());
            rendered++;
        }
        for (int i = rendered; i < MAX_COMPATIBLE_FOOD_ICONS; i++) {
            commandBuilder.set("#CompatibleFoodIcon" + i + ".Visible", false);
        }
        int remaining = foods.size() - rendered;
        if (remaining > 0) {
            commandBuilder.set("#CompatibleFoodMore.Visible", true);
            commandBuilder.set("#CompatibleFoodMore.Text", "+" + remaining);
        } else {
            commandBuilder.set("#CompatibleFoodMore.Visible", false);
        }
    }

    private static void bindAttachments(UICommandBuilder commandBuilder,
                                        @Nonnull List<CommandTargetHudViewModel.AttachmentRow> attachments) {
        for (int i = 0; i < MAX_ATTACHMENT_ROWS; i++) {
            String selector = "#AttachmentRow" + i;
            boolean visible = i < attachments.size();
            commandBuilder.set(selector + ".Visible", visible);
            if (!visible) {
                continue;
            }
            CommandTargetHudViewModel.AttachmentRow row = attachments.get(i);
            commandBuilder.set(selector + " #Text.Text", row.displayLine());
        }
    }

    private static void bindTameRequirement(UICommandBuilder commandBuilder,
                                            @Nullable CommandTargetHudViewModel.TameRequirementRow row,
                                            @Nullable String language) {
        commandBuilder.set("#TameRequirementRow.Visible", row != null);
        if (row == null) {
            return;
        }
        String value = LocalizedText.format(language, "tamework.ui.commandTargetHud.tameRequirement.stacks", row.requiredStacks());
        if (row.currentStacksText() != null && !row.currentStacksText().isBlank()) {
            value = value + " " + LocalizedText.format(
                    language,
                    "tamework.ui.commandTargetHud.tameRequirement.current",
                    row.currentStacksText()
            );
        }
        commandBuilder.set("#TameRequirementLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.tameRequirement"));
        commandBuilder.set("#TameRequirementValue.Text", value);
    }

    private static String safe(@Nullable String value, @Nullable String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
