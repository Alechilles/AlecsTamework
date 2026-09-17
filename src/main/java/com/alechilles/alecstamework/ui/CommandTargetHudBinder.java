package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Binds compact command-target HUD data into the passive right-side overlay. */
final class CommandTargetHudBinder {
    private static final int MAX_ATTACHMENT_ROWS = 6;
    private static final int MAX_FOOD_STRIP_ITEMS = 4;
    private static final int ROOT_TOP = 218;
    private static final int ROOT_RIGHT = 26;
    private static final int ROOT_WIDTH = 344;
    private static final int HEALTH_FILL_MAX_WIDTH = 322;
    private static final int ROOT_VERTICAL_CHROME = 22;
    private static final int HEADER_TOP = 0;
    private static final int HEADER_HEIGHT = 26;
    private static final int HEALTH_TOP = 46;
    private static final int HEALTH_HEIGHT = 28;
    private static final int FIRST_DYNAMIC_ROW_TOP = 80;
    private static final int SECTION_GAP = 6;
    private static final int FOOD_ATTACHMENT_GAP = 8;
    private static final int STATUS_ROW_WIDTH = 324;
    private static final int STATUS_ROW_HEIGHT = 48;
    private static final int COOLDOWN_ROW_HEIGHT = 40;
    private static final int COMPACT_METER_WIDTH = 108;
    private static final int COMPACT_METER_FILL_WIDTH = 104;
    private static final int FOOD_HEADING_HEIGHT = 18;
    private static final int FAVORITE_FOOD_HEIGHT = 36;
    private static final int FOOD_STRIP_HEIGHT = 46;
    private static final int FOOD_STACK_GAP = 4;
    private static final int FOOD_ROW_FAVORITE_WIDTH = 112;
    private static final int FOOD_ROW_STRIP_WIDTH = 168;
    private static final int TAME_REQUIREMENT_WIDTH = 116;
    private static final int TAME_REQUIREMENT_HEIGHT = 52;
    private static final int ATTACHMENT_ROW_HEIGHT = 18;
    private static final int OWNER_ROW_WIDTH = 180;
    private static final int OWNER_ROW_HEIGHT = 18;

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
        commandBuilder.set("#RoleSubtitle.Text", status.roleSubtitle());
        commandBuilder.set("#RoleSubtitle.Visible", !status.roleSubtitle().isBlank());
        LinkedNpcPanelVitalsBinder.bindHud(commandBuilder, status, language, HEALTH_FILL_MAX_WIDTH);
        bindStatusVisibility(commandBuilder, status);
        bindLifecycle(commandBuilder, status, language);
        bindProgression(commandBuilder, status, language);
        bindTraits(commandBuilder, status.traitIndicators());
        bindFood(commandBuilder, model.favoriteFood(), model.compatibleFoods(), language);
        bindAttachments(commandBuilder, model.attachments(), language);
        bindTameRequirement(commandBuilder, model.tameRequirement(), language);
        bindOwner(commandBuilder, model.ownerDisplayName(), language);
        bindLayout(commandBuilder, model);
    }

    private static void bindLayout(@Nonnull UICommandBuilder commandBuilder,
                                   @Nonnull CommandTargetHudViewModel model) {
        Layout layout = resolveLayout(model);
        commandBuilder.setObject("#Root.Anchor", rootAnchor(layout.rootHeight()));
        commandBuilder.setObject("#HeaderRow.Anchor", fullWidthAnchor(HEADER_TOP, HEADER_HEIGHT));
        commandBuilder.setObject("#HealthRow.Anchor", fullWidthAnchor(HEALTH_TOP, HEALTH_HEIGHT));
        commandBuilder.setObject("#StatusRingRow.Anchor", leftAnchor(layout.statusTop(), STATUS_ROW_WIDTH, STATUS_ROW_HEIGHT));
        commandBuilder.set("#CooldownRow.Visible", hasCooldownRow(model.status()));
        commandBuilder.setObject("#CooldownRow.Anchor", fullWidthAnchor(layout.cooldownTop(), COOLDOWN_ROW_HEIGHT));
        commandBuilder.set("#AppearanceDivider.Visible", layout.attachmentCount() > 0);
        commandBuilder.setObject("#AppearanceDivider.Anchor", fullWidthAnchor(layout.firstAttachmentTop() - 4, 1));
        commandBuilder.set("#FoodTameRow.Visible", layout.foodTameVisible());
        commandBuilder.setObject("#FoodTameRow.Anchor", fullWidthAnchor(layout.foodTameTop(), layout.foodTameHeight()));
        commandBuilder.setObject("#FoodRow.Anchor", leftAnchor(0, layout.foodRowWidth(), layout.foodRowHeight()));
        commandBuilder.setObject("#FavoriteFoodBlock.Anchor", leftAnchor(layout.favoriteFoodTop(), FOOD_ROW_FAVORITE_WIDTH, FAVORITE_FOOD_HEIGHT));
        commandBuilder.setObject("#FoodStripBlock.Anchor", leftAnchor(layout.foodStripTop(), FOOD_ROW_STRIP_WIDTH, FOOD_STRIP_HEIGHT));
        commandBuilder.setObject("#TameRequirementRow.Anchor", rightAnchor(0, TAME_REQUIREMENT_WIDTH, TAME_REQUIREMENT_HEIGHT));
        bindAttachmentLayout(commandBuilder, layout.firstAttachmentTop());
        commandBuilder.set("#OwnerRow.Visible", layout.ownerVisible());
        commandBuilder.setObject("#OwnerRow.Anchor", rightAnchor(layout.ownerTop(), OWNER_ROW_WIDTH, OWNER_ROW_HEIGHT));
    }

    @Nonnull
    static Layout resolveLayout(@Nonnull CommandTargetHudViewModel model) {
        LinkedNpcEntry status = model.status();
        boolean hasStatusRow = hasStatusRow(status);
        boolean hasFavoriteFood = model.favoriteFood() != null;
        boolean hasFoodStrip = hasRenderableFoods(model.compatibleFoods());
        boolean hasTameRequirement = model.tameRequirement() != null;
        boolean hasOwner = hasOwnerDisplayName(model.ownerDisplayName());
        int attachmentCount = Math.min(MAX_ATTACHMENT_ROWS, model.attachments().size());

        int nextTop = FIRST_DYNAMIC_ROW_TOP;
        int contentBottom = HEALTH_TOP + HEALTH_HEIGHT;
        int statusTop = nextTop;
        if (hasStatusRow) {
            contentBottom = statusTop + STATUS_ROW_HEIGHT;
            nextTop = contentBottom + SECTION_GAP;
        }

        int cooldownTop = nextTop;
        if (hasCooldownRow(status)) {
            contentBottom = cooldownTop + COOLDOWN_ROW_HEIGHT;
            nextTop = contentBottom + SECTION_GAP;
        }

        int foodTameTop = nextTop;
        int favoriteFoodTop = FOOD_HEADING_HEIGHT;
        int foodStripTop = FOOD_HEADING_HEIGHT + (hasFavoriteFood ? FAVORITE_FOOD_HEIGHT + FOOD_STACK_GAP : 0);
        int foodRowHeight = foodRowHeight(hasFavoriteFood, hasFoodStrip)
                + (hasFavoriteFood || hasFoodStrip ? FOOD_HEADING_HEIGHT : 0);
        int foodRowWidth = hasFoodStrip ? FOOD_ROW_STRIP_WIDTH : FOOD_ROW_FAVORITE_WIDTH;
        int foodTameHeight = Math.max(foodRowHeight, hasTameRequirement ? TAME_REQUIREMENT_HEIGHT : 0);
        if (foodTameHeight > 0) {
            contentBottom = foodTameTop + foodTameHeight;
            nextTop = contentBottom + FOOD_ATTACHMENT_GAP;
        }

        int firstAttachmentTop = nextTop;
        if (attachmentCount > 0) {
            contentBottom = firstAttachmentTop + (attachmentCount * ATTACHMENT_ROW_HEIGHT);
            nextTop = contentBottom + SECTION_GAP;
        }

        int ownerTop = nextTop;
        if (hasOwner) {
            contentBottom = ownerTop + OWNER_ROW_HEIGHT;
        }

        return new Layout(
                ROOT_VERTICAL_CHROME + contentBottom,
                statusTop,
                cooldownTop,
                foodTameTop,
                foodTameHeight,
                foodTameHeight > 0,
                foodRowWidth,
                foodRowHeight,
                favoriteFoodTop,
                foodStripTop,
                firstAttachmentTop,
                attachmentCount,
                ownerTop,
                hasOwner
        );
    }

    private static int foodRowHeight(boolean hasFavoriteFood, boolean hasFoodStrip) {
        int height = 0;
        if (hasFavoriteFood) {
            height = FAVORITE_FOOD_HEIGHT;
        }
        if (hasFoodStrip) {
            height += (height > 0 ? FOOD_STACK_GAP : 0) + FOOD_STRIP_HEIGHT;
        }
        return height;
    }

    private static boolean hasStatusRow(@Nonnull LinkedNpcEntry status) {
        return (TameworkRuntimeSettings.happinessEnabled(true) && status.hasHappiness())
                || (TameworkRuntimeSettings.needsEnabled(true) && (status.hasHunger() || status.hasThirst()));
    }

    private static boolean hasProgressionRow(@Nonnull LinkedNpcEntry status) {
        return status.futureStatA() != null
                || LinkedNpcPanelProgressionBinder.availableTalentPoints(status.futureStatB()) > 0;
    }

    private static boolean hasCooldownRow(@Nonnull LinkedNpcEntry status) {
        return status.animalLifecycle().active()
                || status.breedingCooldownKnown()
                || status.harvestCooldownKnown();
    }

    private static boolean hasRenderableFoods(@Nonnull List<CommandTargetHudViewModel.FoodRow> foods) {
        for (CommandTargetHudViewModel.FoodRow food : foods) {
            if (isRenderableFood(food)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOwnerDisplayName(@Nullable String ownerDisplayName) {
        return ownerDisplayName != null && !ownerDisplayName.isBlank();
    }

    private static void bindStatusVisibility(UICommandBuilder commandBuilder, LinkedNpcEntry status) {
        commandBuilder.set("#NeedHappiness.Visible", TameworkRuntimeSettings.happinessEnabled(true) && status.hasHappiness());
        commandBuilder.set("#NeedHunger.Visible", TameworkRuntimeSettings.needsEnabled(true) && status.hasHunger());
        commandBuilder.set("#NeedThirst.Visible", TameworkRuntimeSettings.needsEnabled(true) && status.hasThirst());
        commandBuilder.set("#StatusRingRow.Visible", hasStatusRow(status));
    }

    private static void bindLifecycle(@Nonnull UICommandBuilder commandBuilder,
                                      @Nonnull LinkedNpcEntry status,
                                      @Nullable String language) {
        LinkedNpcPanelCardBinder.LifecycleDisplay display = LinkedNpcPanelCardBinder.resolveLifecycleDisplay(status, language);
        LinkedNpcEntry.AnimalLifecycle lifecycle = status.animalLifecycle();
        commandBuilder.set("#AgeProgress.Visible", display.visible());
        commandBuilder.setObject("#BreedingCooldown.Anchor", compactMeterAnchor());
        commandBuilder.setObject("#HarvestCooldown.Anchor", compactMeterAnchor());
        commandBuilder.setObject("#BreedingCooldown #MeterFill.Anchor", compactMeterFill(
                status.breedingCooldownRemainingMs() < 0L ? 0.0
                        : status.breedingCooldownActive() ? status.breedingCooldownRatio() : 1.0));
        commandBuilder.setObject("#HarvestCooldown #MeterFill.Anchor", compactMeterFill(
                status.harvestCooldownRemainingMs() < 0L ? 0.0
                        : status.harvestCooldownActive() ? status.harvestCooldownRatio() : 1.0));
        if (!display.visible()) {
            return;
        }
        commandBuilder.set("#AgeStage.Style.TextColor", LinkedNpcPanelCardBinder.lifecycleColor(lifecycle));
        commandBuilder.set("#AgeStage.Text", LocalizedText.format(
                language, "tamework.commandmenu.lifecycle.ageStage", display.stageText()));
        commandBuilder.set("#AgeCountdown.Text", display.countdownText());
        commandBuilder.set("#AgeTooltip.TooltipText", lifecycleTooltip(display));
        commandBuilder.setObject("#AgeMeterFill.Anchor", compactMeterFill(lifecycle.stageProgress()));
        commandBuilder.set("#AgeMeterFill.Background", LinkedNpcPanelCardBinder.lifecycleColor(lifecycle));
        commandBuilder.setObject("#AgeProgress.Anchor", compactMeterAnchor());
    }

    @Nonnull
    private static String lifecycleTooltip(@Nonnull LinkedNpcPanelCardBinder.LifecycleDisplay display) {
        if (display.countdownText().isBlank()) {
            return display.yieldTooltip();
        }
        return display.countdownText() + "\n" + display.yieldTooltip();
    }

    private static void bindProgression(UICommandBuilder commandBuilder, LinkedNpcEntry status, @Nullable String language) {
        boolean hasTalentPoints = LinkedNpcPanelProgressionBinder.availableTalentPoints(status.futureStatB()) > 0;
        commandBuilder.set("#ProgressionRow.Visible", hasProgressionRow(status));
        commandBuilder.set("#XpProgressRing.Visible", status.futureStatA() != null);
        String level = status.futureStatA() != null
                ? LinkedNpcPanelProgressionBinder.resolveLevelText(status.futureStatA().label()) : "";
        String levelLabel = LocalizedText.format(language, "tamework.ui.linkedPanel.bonded.talents.level", level);
        int levelWidth = status.futureStatA() != null ? levelLabel.length() * 8 : 0;
        commandBuilder.setObject("#ProgressionRow.Anchor",
                rightAnchor(26, levelWidth + (hasTalentPoints ? 44 : 0), 18));
        commandBuilder.setObject("#XpProgressRing.Anchor", rightAnchor(0, Math.max(1, levelWidth), 18));
        if (status.futureStatA() != null) {
            commandBuilder.set("#XpLevelText.Text", levelLabel);
            commandBuilder.set("#XpTooltip.TooltipText", LinkedNpcPanelProgressionBinder.resolveXpTooltip(status.futureStatA()));
        }
        LinkedNpcPanelProgressionBinder.bindTalentPointIndicator(
                commandBuilder,
                "#TalentPointAction",
                "#TalentPointAction #TalentPointCount",
                "#TalentPointAction #TalentPointCountShadow",
                status.futureStatB(),
                hasTalentPoints
        );
    }

    private static void bindTraits(UICommandBuilder commandBuilder, LinkedNpcTraitIndicator[] indicators) {
        LinkedNpcTraitIndicator[] safeIndicators = indicators == null ? LinkedNpcTraitIndicator.EMPTY : indicators;
        commandBuilder.set("#TraitRingRow.Visible", safeIndicators.length > 0);
        for (int i = 0; i < LinkedNpcTraitIndicatorBinder.MAX_VISIBLE_TRAIT_INDICATORS; i++) {
            String slot = "#TraitSlot" + i;
            boolean visible = i < safeIndicators.length;
            commandBuilder.set(slot + ".Visible", visible);
            if (!visible) {
                continue;
            }
            LinkedNpcTraitIndicator trait = safeIndicators[i];
            commandBuilder.set(slot + " #TraitIconImage.Visible", trait.hasIconTexturePath());
            commandBuilder.set(slot + " #TraitIcon.Visible", !trait.hasIconTexturePath());
            if (trait.hasIconTexturePath()) {
                commandBuilder.set(slot + " #TraitIconImage.Background", trait.iconTexturePath());
                commandBuilder.setObject(slot + " #TraitIconImage.Anchor", LinkedNpcTraitIndicatorBinder.iconAnchor(trait.iconTexturePath(), 2, 5, 20));
            } else {
                commandBuilder.set(slot + " #TraitIcon.Text", trait.iconText());
            }
            commandBuilder.set(slot + " #TraitTooltip.TooltipText", trait.tooltipText());
            commandBuilder.set(slot + " #TraitTooltip.TooltipTextSpans", LinkedNpcTraitIndicatorBinder.tooltipSpans(trait.tooltipText()));
            commandBuilder.set(slot + " #TraitTick.Background", trait.belowDefault() ? "#d45f5f" : "#6fc576");
            Anchor tick = leftAnchor(0, Math.max(2, (int) Math.round(trait.fillRatio() * 20)), 3);
            tick.setLeft(Value.of(2));
            commandBuilder.setObject(slot + " #TraitTick.Anchor", tick);
        }
    }

    private static void bindFood(UICommandBuilder commandBuilder,
                                 @Nullable CommandTargetHudViewModel.FoodRow food,
                                 @Nonnull List<CommandTargetHudViewModel.FoodRow> foodRows,
                                 @Nullable String language) {
        List<CommandTargetHudViewModel.FoodRow> renderableFoodRows = renderableFoods(foodRows);
        boolean hasFoodRows = !renderableFoodRows.isEmpty();
        commandBuilder.set("#FoodRow.Visible", food != null || hasFoodRows);
        commandBuilder.set("#FoodHeading.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.foodPreferences"));
        commandBuilder.set("#FavoriteFoodBlock.Visible", food != null);
        commandBuilder.set("#FoodStripBlock.Visible", hasFoodRows);
        if (food == null && !hasFoodRows) {
            return;
        }
        if (food != null) {
            commandBuilder.set("#FoodLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.favoriteFood"));
            commandBuilder.set("#FoodName.Text", food.displayName());
            bindFoodGrid(commandBuilder, "#FoodItemGrid", food);
        }
        if (hasFoodRows) {
            bindFoodStrip(commandBuilder, renderableFoodRows, language);
        }
    }

    @Nonnull
    private static List<CommandTargetHudViewModel.FoodRow> renderableFoods(
            @Nonnull List<CommandTargetHudViewModel.FoodRow> foods) {
        ArrayList<CommandTargetHudViewModel.FoodRow> rows = new ArrayList<>();
        for (CommandTargetHudViewModel.FoodRow food : foods) {
            if (isRenderableFood(food)) {
                rows.add(food);
            }
        }
        return rows;
    }

    private static boolean isRenderableFood(@Nullable CommandTargetHudViewModel.FoodRow food) {
        return food != null && food.itemId() != null && !food.itemId().isBlank();
    }

    private static void bindFoodStrip(@Nonnull UICommandBuilder commandBuilder,
                                      @Nonnull List<CommandTargetHudViewModel.FoodRow> foods,
                                      @Nullable String language) {
        int rendered = 0;
        int maxIcons = foods.size() > MAX_FOOD_STRIP_ITEMS
                ? MAX_FOOD_STRIP_ITEMS - 1
                : MAX_FOOD_STRIP_ITEMS;
        for (CommandTargetHudViewModel.FoodRow food : foods) {
            if (rendered >= maxIcons) {
                break;
            }
            bindFoodStripSlot(commandBuilder, rendered, food, language);
            rendered++;
        }
        for (int i = rendered; i < MAX_FOOD_STRIP_ITEMS; i++) {
            commandBuilder.set("#FoodSlot" + i + ".Visible", false);
        }
        int remaining = foods.size() - rendered;
        if (remaining > 0) {
            commandBuilder.set("#FoodMore.Visible", true);
            commandBuilder.set("#FoodMore.Text", "+" + remaining);
        } else {
            commandBuilder.set("#FoodMore.Visible", false);
        }
    }

    private static void bindFoodStripSlot(@Nonnull UICommandBuilder commandBuilder,
                                          int index,
                                          @Nonnull CommandTargetHudViewModel.FoodRow food,
                                          @Nullable String language) {
        String selector = "#FoodSlot" + index;
        commandBuilder.set(selector + ".Visible", true);
        bindFoodValue(commandBuilder, selector, food.happinessDelta());
        commandBuilder.set(selector + " #FoodTooltip.TooltipText", foodTooltip(food, language));
        bindFoodGrid(commandBuilder, selector + " #FoodGrid", food);
    }

    private static void bindFoodValue(@Nonnull UICommandBuilder commandBuilder,
                                      @Nonnull String selector,
                                      @Nullable Double value) {
        String text = formatHappinessDelta(value);
        boolean positive = value != null && Double.isFinite(value) && value > 0.0;
        boolean negative = value != null && Double.isFinite(value) && value < 0.0;
        boolean neutral = !text.isBlank() && !positive && !negative;
        setFoodValue(commandBuilder, selector, "#FoodValuePositive", text, positive);
        setFoodValue(commandBuilder, selector, "#FoodValueNegative", text, negative);
        setFoodValue(commandBuilder, selector, "#FoodValueNeutral", text, neutral);
    }

    private static void setFoodValue(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull String selector,
                                     @Nonnull String labelSelector,
                                     @Nonnull String text,
                                     boolean visible) {
        commandBuilder.set(selector + " " + labelSelector + ".Visible", visible);
        commandBuilder.set(selector + " " + labelSelector + ".Text", visible ? text : "");
    }

    private static void bindFoodGrid(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull String selector,
                                     @Nullable CommandTargetHudViewModel.FoodRow food) {
        if (food == null || food.itemId() == null || food.itemId().isBlank()) {
            commandBuilder.set(selector + ".Visible", false);
            return;
        }
        commandBuilder.set(selector + ".Visible", true);
        commandBuilder.set(selector + ".Slots", List.of(itemSlot(food)));
    }

    @Nonnull
    private static ItemGridSlot itemSlot(@Nonnull CommandTargetHudViewModel.FoodRow food) {
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(food.itemId(), 1));
        slot.setName(food.displayName());
        slot.setSkipItemQualityBackground(true);
        return slot;
    }

    @Nonnull
    private static String formatHappinessDelta(@Nullable Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "";
        }
        double rounded = Math.rint(value);
        String text = Math.abs(value - rounded) < 0.001
                ? Integer.toString((int) rounded)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
        return value > 0.0 ? "+" + text : text;
    }

    @Nonnull
    private static String foodTooltip(@Nonnull CommandTargetHudViewModel.FoodRow food,
                                      @Nullable String language) {
        String delta = formatHappinessDelta(food.happinessDelta());
        if (delta.isBlank()) {
            return food.displayName();
        }
        return food.displayName() + "\n" + LocalizedText.format(
                language,
                "tamework.ui.commandTargetHud.foodHappiness",
                delta
        );
    }

    private static void bindAttachments(UICommandBuilder commandBuilder,
                                        @Nonnull List<CommandTargetHudViewModel.AttachmentRow> attachments,
                                        @Nullable String language) {
        for (int i = 0; i < MAX_ATTACHMENT_ROWS; i++) {
            String selector = "#AttachmentRow" + i;
            boolean visible = i < attachments.size();
            commandBuilder.set(selector + ".Visible", visible);
            if (!visible) {
                continue;
            }
            CommandTargetHudViewModel.AttachmentRow row = attachments.get(i);
            String label = LocalizedText.resolveConfigValue(language, row.setLabel(), row.setLabel());
            String value = LocalizedText.resolveConfigValue(language, row.valueLabel(), row.valueLabel());
            commandBuilder.set(selector + " #Text.Text", value.isBlank() ? label : label + ": " + value);
        }
    }

    private static void bindAttachmentLayout(@Nonnull UICommandBuilder commandBuilder,
                                             int firstTop) {
        for (int i = 0; i < MAX_ATTACHMENT_ROWS; i++) {
            commandBuilder.setObject(
                    "#AttachmentRow" + i + ".Anchor",
                    fullWidthAnchor(firstTop + (i * ATTACHMENT_ROW_HEIGHT), ATTACHMENT_ROW_HEIGHT)
            );
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
        String current = row.currentStacksText() != null && !row.currentStacksText().isBlank()
                ? LocalizedText.format(
                        language,
                        "tamework.ui.commandTargetHud.tameRequirement.current",
                        row.currentStacksText()
                )
                : "";
        commandBuilder.set("#TameRequirementLabel.Text", LocalizedText.resolve(language, "tamework.ui.commandTargetHud.tameRequirement"));
        commandBuilder.set("#TameRequirementValue.Text", value);
        commandBuilder.set("#TameRequirementCurrent.Visible", !current.isBlank());
        commandBuilder.set("#TameRequirementCurrent.Text", current);
    }

    private static void bindOwner(@Nonnull UICommandBuilder commandBuilder,
                                  @Nullable String ownerDisplayName,
                                  @Nullable String language) {
        boolean visible = hasOwnerDisplayName(ownerDisplayName);
        commandBuilder.set("#OwnerRow.Visible", visible);
        commandBuilder.set(
                "#OwnerText.Text",
                visible ? LocalizedText.format(language, "tamework.ui.commandTargetHud.owner", ownerDisplayName) : ""
        );
    }

    private static String safe(@Nullable String value, @Nullable String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Nonnull
    private static Anchor rootAnchor(int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(ROOT_TOP));
        anchor.setRight(Value.of(ROOT_RIGHT));
        anchor.setWidth(Value.of(ROOT_WIDTH));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    private static Anchor fullWidthAnchor(int top, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(Math.max(0, top)));
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(0));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    private static Anchor leftAnchor(int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(Math.max(0, top)));
        anchor.setLeft(Value.of(0));
        anchor.setWidth(Value.of(Math.max(1, width)));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    private static Anchor rightAnchor(int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(Math.max(0, top)));
        anchor.setRight(Value.of(0));
        anchor.setWidth(Value.of(Math.max(1, width)));
        anchor.setHeight(Value.of(Math.max(1, height)));
        return anchor;
    }

    @Nonnull
    private static Anchor compactMeterAnchor() {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(COMPACT_METER_WIDTH));
        anchor.setHeight(Value.of(COOLDOWN_ROW_HEIGHT));
        return anchor;
    }

    @Nonnull
    private static Anchor compactMeterFill(double ratio) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(16));
        anchor.setLeft(Value.of(0));
        anchor.setWidth(Value.of((int) Math.round(clampRatio(ratio) * COMPACT_METER_FILL_WIDTH)));
        anchor.setHeight(Value.of(4));
        return anchor;
    }

    private static double clampRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    record Layout(int rootHeight,
                  int statusTop,
                  int cooldownTop,
                  int foodTameTop,
                  int foodTameHeight,
                  boolean foodTameVisible,
                  int foodRowWidth,
                  int foodRowHeight,
                  int favoriteFoodTop,
                  int foodStripTop,
                  int firstAttachmentTop,
                  int attachmentCount,
                  int ownerTop,
                  boolean ownerVisible) {
    }
}
