package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Binds compact command-target HUD data into the passive right-side overlay. */
final class CommandTargetHudBinder {
    private static final int HEALTH_FILL_WIDTH = 148;
    private static final int MAX_TRAIT_ROWS = 4;
    private static final int MAX_ATTACHMENT_ROWS = 3;

    private CommandTargetHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder,
                     @Nonnull CommandTargetHudViewModel model,
                     @Nullable String language) {
        LinkedNpcEntry status = model.status();
        commandBuilder.set("#Root.Visible", true);
        commandBuilder.set("#Name.Text", safe(status.displayName(), LocalizedText.resolve(language, "tamework.ui.commandTargetHud.name.unknown")));
        bindHealth(commandBuilder, status, language);
        bindNeedRow(commandBuilder, "#HappinessRow", "tamework.ui.commandTargetHud.happiness", status.hasHappiness(), status.currentHappiness(), status.maxHappiness(), language);
        bindNeedRow(commandBuilder, "#HungerRow", "tamework.ui.commandTargetHud.hunger", status.hasHunger(), status.currentHunger(), status.maxHunger(), language);
        bindNeedRow(commandBuilder, "#ThirstRow", "tamework.ui.commandTargetHud.thirst", status.hasThirst(), status.currentThirst(), status.maxThirst(), language);
        bindLevel(commandBuilder, status.futureStatA(), language);
        bindTraits(commandBuilder, status.traitIndicators());
        bindFood(commandBuilder, model.favoriteFood(), language);
        bindAttachments(commandBuilder, model.attachments());
        bindCooldown(commandBuilder, "#HarvestCooldownRow", status.harvestCooldownKnown() && status.harvestCooldownActive(),
                "tamework.ui.commandTargetHud.harvestCooldown", status.harvestCooldownRemainingMs(), language);
        bindCooldown(commandBuilder, "#BreedingCooldownRow", status.breedingCooldownKnown() && status.breedingCooldownActive(),
                "tamework.ui.commandTargetHud.breedingCooldown", status.breedingCooldownRemainingMs(), language);
        bindTameRequirement(commandBuilder, model.tameRequirement(), language);
    }

    private static void bindHealth(UICommandBuilder commandBuilder, LinkedNpcEntry status, String language) {
        if (!status.hasHealth()) {
            commandBuilder.set("#HealthText.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.health.unavailable"));
            commandBuilder.set("#HealthBar.Visible", false);
            commandBuilder.set("#HealthFill.Visible", false);
            return;
        }
        commandBuilder.set("#HealthText.Text", LocalizedText.format(
                language,
                "tamework.ui.commandTargetHud.health.value",
                status.currentHealth(),
                status.maxHealth()
        ));
        commandBuilder.set("#HealthBar.Visible", true);
        commandBuilder.set("#HealthFill.Visible", true);
        commandBuilder.setObject("#HealthFill.Anchor", LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(status.healthRatio(), HEALTH_FILL_WIDTH));
    }

    private static void bindNeedRow(UICommandBuilder commandBuilder,
                                    String rowSelector,
                                    String labelKey,
                                    boolean visible,
                                    int current,
                                    int max,
                                    @Nullable String language) {
        commandBuilder.set(rowSelector + ".Visible", visible);
        if (!visible) {
            return;
        }
        commandBuilder.set(rowSelector + " #Label.Text", LocalizedText.resolve(language, labelKey));
        commandBuilder.set(rowSelector + " #Value.Text", current + "/" + max);
    }

    private static void bindLevel(UICommandBuilder commandBuilder,
                                  @Nullable LinkedNpcEntry.FutureStat levelStat,
                                  @Nullable String language) {
        commandBuilder.set("#LevelRow.Visible", levelStat != null);
        if (levelStat == null) {
            return;
        }
        commandBuilder.set("#LevelRow #Label.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.level"));
        commandBuilder.set("#LevelRow #Value.Text", safe(levelStat.label(), "?"));
    }

    private static void bindTraits(UICommandBuilder commandBuilder, LinkedNpcTraitIndicator[] indicators) {
        LinkedNpcTraitIndicator[] safeIndicators = indicators == null ? LinkedNpcTraitIndicator.EMPTY : indicators;
        for (int i = 0; i < MAX_TRAIT_ROWS; i++) {
            String selector = "#TraitRow" + i;
            boolean visible = i < safeIndicators.length && safeIndicators[i] != null;
            commandBuilder.set(selector + ".Visible", visible);
            if (!visible) {
                continue;
            }
            commandBuilder.set(selector + " #Label.Text", safe(safeIndicators[i].label(), ""));
            commandBuilder.set(selector + " #Value.Text", safe(safeIndicators[i].iconText(), ""));
        }
    }

    private static void bindFood(UICommandBuilder commandBuilder,
                                 @Nullable CommandTargetHudViewModel.FoodRow food,
                                 @Nullable String language) {
        commandBuilder.set("#FoodRow.Visible", food != null);
        if (food == null) {
            return;
        }
        commandBuilder.set("#FoodLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.favoriteFood"));
        commandBuilder.set("#FoodName.Text", food.displayName());
        boolean hasIcon = food.iconPath() != null && !food.iconPath().isBlank();
        commandBuilder.set("#FoodIcon.Visible", hasIcon);
        if (hasIcon) {
            commandBuilder.set("#FoodIcon.Background", food.iconPath());
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
            commandBuilder.set(selector + " #Label.Text", row.setLabel());
            commandBuilder.set(selector + " #Value.Text", row.valueLabel());
        }
    }

    private static void bindCooldown(UICommandBuilder commandBuilder,
                                     String rowSelector,
                                     boolean visible,
                                     String labelKey,
                                     long remainingMs,
                                     @Nullable String language) {
        commandBuilder.set(rowSelector + ".Visible", visible);
        if (!visible) {
            return;
        }
        commandBuilder.set(rowSelector + " #Label.Text", LocalizedText.resolve(language, labelKey));
        commandBuilder.set(rowSelector + " #Value.Text", formatRemaining(remainingMs));
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

    private static String formatRemaining(long remainingMs) {
        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private static String safe(@Nullable String value, @Nullable String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
