package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies linked companion vitals to UI card controls.
 */
final class LinkedNpcPanelVitalsBinder {
    private static final int CARD_HEALTH_FILL_MAX_WIDTH = 232;
    private static final String MUTED_FILL_COLOR = "#727772";
    private static final String ICON_NEED_HAPPINESS = "Tamework/LinkedPanelIcons/Need_Happiness.png";
    private static final String ICON_NEED_HUNGER = "Tamework/LinkedPanelIcons/Need_Hunger.png";
    private static final String ICON_NEED_THIRST = "Tamework/LinkedPanelIcons/Need_Thirst.png";
    private static final Color TOOLTIP_WHITE = new Color(0xff, 0xff, 0xff);
    private static final Color TOOLTIP_GREEN = new Color(0x6f, 0xc5, 0x76);
    private static final Color TOOLTIP_RED = new Color(0xd4, 0x5f, 0x5f);
    private static final Color TOOLTIP_GRAY = new Color(0xaf, 0xb6, 0xb0);

    private LinkedNpcPanelVitalsBinder() {
    }

    static void bindHud(UICommandBuilder commands, LinkedNpcEntry entry, String language, int healthWidth) {
        bindHealth(commands, "#Root", entry, language, healthWidth, 20, false);
        bindNeedMeters(commands, "#Root", entry, language);
        bindBreedingCooldownMeter(commands, "#Root", entry, language);
        bindHarvestCooldownMeter(commands, "#Root", entry, language);
        String[] needs = {"#NeedHappiness", "#NeedHunger", "#NeedThirst"};
        double[] ratios = {entry.happinessRatio(), entry.hungerRatio(), entry.thirstRatio()};
        for (int i = 0; i < needs.length; i++) {
            commands.setObject(needs[i] + " #MeterFill.Anchor", hudFill(ratios[i], 100));
            commands.set(needs[i] + " #NeedValueText.Style", Value.ref("TameworkCommandTargetHud.ui", "MeterValue"));
        }
        Anchor marker = hudFill(0, 2);
        marker.setLeft(Value.of((int) Math.round(clamp(entry.breedingHappinessRatio()) * 98)));
        marker.setTop(Value.of(24));
        marker.setWidth(Value.of(2));
        marker.setHeight(Value.of(10));
        commands.setObject("#NeedHappiness #BreedingThresholdMarker.Anchor", marker);
        commands.setObject("#BreedingCooldown #MeterFill.Anchor", hudFill(
                entry.breedingCooldownRemainingMs() < 0 ? 0 : entry.breedingCooldownActive() ? entry.breedingCooldownRatio() : 1, 156));
        commands.setObject("#HarvestCooldown #MeterFill.Anchor", hudFill(
                entry.harvestCooldownRemainingMs() < 0 ? 0 : entry.harvestCooldownActive() ? entry.harvestCooldownRatio() : 1, 156));
        boolean breedingMuted = !entry.loaded() || !entry.breedingEnabled()
                || LinkedNpcPanelStatusTextService.breedingBlockedByHappiness(entry);
        commands.set("#BreedingCooldown #CooldownText.Style", Value.ref("TameworkCommandTargetHud.ui",
                breedingMuted ? "CooldownMuted" : "CooldownValue"));
        commands.set("#HarvestCooldown #CooldownText.Style", Value.ref("TameworkCommandTargetHud.ui",
                entry.loaded() ? "CooldownValue" : "CooldownMuted"));
        commands.set("#BreedingCooldown #CooldownLabel.Style", Value.ref("TameworkCommandTargetHud.ui", "CooldownLabel"));
        commands.set("#HarvestCooldown #CooldownLabel.Style", Value.ref("TameworkCommandTargetHud.ui", "CooldownLabel"));
    }

    private static Anchor hudFill(double ratio, int width) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setTop(Value.of(26));
        anchor.setWidth(Value.of((int) Math.round(clamp(ratio) * width)));
        anchor.setHeight(Value.of(6));
        return anchor;
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bind(commandBuilder, entrySelector, entry, null);
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry, String language) {
        bindHealth(commandBuilder, entrySelector, entry, language, CARD_HEALTH_FILL_MAX_WIDTH, 20, true);
        bindNeedMeters(commandBuilder, entrySelector, entry, language);
        bindBreedingCooldownMeter(commandBuilder, entrySelector, entry, language);
        bindHarvestCooldownMeter(commandBuilder, entrySelector, entry, language);
    }

    private static void bindHealth(UICommandBuilder commandBuilder,
                                   String entrySelector,
                                   LinkedNpcEntry entry,
                                   String language,
                                   int healthFillMaxWidth,
                                   int healthFillHeight,
                                   boolean panelCard) {
        String healthTextSelector = entrySelector + " #HealthText";
        String healthTextShadowSelector = entrySelector + " #HealthTextShadow";
        String healthFillSelector = entrySelector + " #HealthFill";
        String healthTooltipSelector = entrySelector + " #HealthTooltip";
        if (entry.hasHealth()) {
            String healthText = (entry.dead() ? 0 : entry.currentHealth()) + "/" + entry.maxHealth();
            boolean muted = !entry.loaded();
            commandBuilder.set(healthTextSelector + ".Text", healthText);
            commandBuilder.set(healthTextShadowSelector + ".Text", healthText);
            if (panelCard) {
                commandBuilder.set(healthTextSelector + ".Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                        muted ? "HealthTextMuted" : "HealthTextNormal"));
                commandBuilder.set(healthTextShadowSelector + ".Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                        muted ? "HealthTextShadowMuted" : "HealthTextShadowNormal"));
            }
            commandBuilder.set(healthFillSelector + ".Visible", true);
            commandBuilder.set(healthFillSelector + ".Background", muted ? MUTED_FILL_COLOR : "#6fc576");
            commandBuilder.set(healthTooltipSelector + ".TooltipText",
                    LinkedNpcPanelStatusTextService.appendLastKnownTooltip(healthText, entry, language));
            Anchor healthFill = LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(
                    entry.dead() ? 0.0 : entry.healthRatio(), healthFillMaxWidth);
            healthFill.setHeight(Value.of(healthFillHeight));
            commandBuilder.setObject(healthFillSelector + ".Anchor", healthFill);
            return;
        }
        if (entry.dead()) {
            // Legacy death records may not contain a saved maximum. Keep the empty bar honest.
            String deadText = "0/?";
            commandBuilder.set(healthTextSelector + ".Text", deadText);
            commandBuilder.set(healthTextShadowSelector + ".Text", deadText);
            commandBuilder.set(healthFillSelector + ".Visible", true);
            Anchor emptyFill = LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(0.0, healthFillMaxWidth);
            emptyFill.setHeight(Value.of(healthFillHeight));
            commandBuilder.setObject(healthFillSelector + ".Anchor", emptyFill);
            commandBuilder.set(
                    healthTooltipSelector + ".TooltipText",
                    LinkedNpcPanelStatusTextService.appendLastKnownTooltip(
                            LinkedNpcPanelStatusTextService.resolveDeadHealthTooltip(entry, language), entry, language)
            );
            return;
        }
        if (entry.inCoop()) {
            String coopText = LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.inCoop");
            commandBuilder.set(healthTextSelector + ".Text", coopText);
            commandBuilder.set(healthTextShadowSelector + ".Text", coopText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            commandBuilder.set(healthTooltipSelector + ".TooltipText", coopText);
            return;
        }
        if (entry.lost()) {
            String lostText = LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.lost");
            commandBuilder.set(healthTextSelector + ".Text", lostText);
            commandBuilder.set(healthTextShadowSelector + ".Text", lostText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            commandBuilder.set(healthTooltipSelector + ".TooltipText", lostText);
            return;
        }
        if (!entry.loaded()) {
            String statusText = entry.captured()
                    ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.captured")
                    : LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.unloaded");
            commandBuilder.set(healthTextSelector + ".Text", statusText);
            commandBuilder.set(healthTextShadowSelector + ".Text", statusText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            commandBuilder.set(healthTooltipSelector + ".TooltipText", statusText);
            return;
        }
        String notAvailable = LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.notAvailable");
        commandBuilder.set(healthTextSelector + ".Text", notAvailable);
        commandBuilder.set(healthTextShadowSelector + ".Text", notAvailable);
        commandBuilder.set(healthFillSelector + ".Visible", false);
        commandBuilder.set(healthTooltipSelector + ".TooltipText", notAvailable);
    }

    private static void bindNeedMeters(UICommandBuilder commandBuilder,
                                       String entrySelector,
                                       LinkedNpcEntry entry,
                                       String language) {
        bindNeedMeter(
                commandBuilder,
                entrySelector + " #NeedHappiness",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.happiness"), ICON_NEED_HAPPINESS),
                resolveHappinessNeed(entry, language),
                shouldShowHappiness(entry),
                "#d7ba77"
        );
        String markerSelector = entrySelector + " #NeedHappiness #BreedingThresholdMarker";
        boolean showMarker = shouldShowBreedingThreshold(entry);
        commandBuilder.set(markerSelector + ".Visible", showMarker);
        if (showMarker) {
            commandBuilder.setObject(markerSelector + ".Anchor",
                    LinkedNpcPanelStatusMeter.buildNeedThresholdAnchor(entry.breedingHappinessRatio()));
        }
        bindNeedMeter(
                commandBuilder,
                entrySelector + " #NeedHunger",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.hunger"), ICON_NEED_HUNGER),
                resolveHungerNeed(entry, language),
                shouldShowNeeds(entry),
                "#ba9b79"
        );
        bindNeedMeter(
                commandBuilder,
                entrySelector + " #NeedThirst",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.thirst"), ICON_NEED_THIRST),
                resolveThirstNeed(entry, language),
                shouldShowNeeds(entry),
                "#7eb6b0"
        );
    }

    private static boolean shouldShowHappiness(LinkedNpcEntry entry) {
        return TameworkRuntimeSettings.happinessEnabled(true) && (entry.hasHappiness() || !entry.loaded());
    }

    private static boolean shouldShowNeeds(LinkedNpcEntry entry) {
        return TameworkRuntimeSettings.needsEnabled(true) && ((entry.hasHunger() && entry.hasThirst()) || !entry.loaded());
    }

    private static boolean shouldShowBreedingThreshold(LinkedNpcEntry entry) {
        return shouldShowHappiness(entry) && entry.hasHappiness()
                && entry.breedingHappinessRatio() > 0.0 && entry.breedingHappinessRatio() <= 1.0;
    }

    private static NeedVisual resolveHappinessNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasHappiness()) {
            String tooltip = entry.happinessModifierBreakdown();
            if (tooltip == null || tooltip.isBlank()) {
                tooltip = LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.happiness.tooltip",
                        percent(entry.happinessRatio()),
                        entry.targetHappinessPercent()
                );
            }
            tooltip = LinkedNpcPanelStatusTextService.appendLastKnownTooltip(tooltip, entry, language);
            return new NeedVisual(
                    entry.happinessRatio(),
                    tooltip,
                    true,
                    !entry.loaded(),
                    happinessTooltipSpans(tooltip, language)
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LinkedNpcPanelStatusTextService.resolveDeadHappinessText(entry, language), false, false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.lost"), false, false);
        }
        if (!entry.loaded()) {
            return new NeedVisual(
                    0.0,
                    LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(entry, language),
                    false,
                    false
            );
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable"), false, false);
    }

    private static NeedVisual resolveHungerNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasHunger()) {
            return new NeedVisual(
                    entry.hungerRatio(),
                    LinkedNpcPanelStatusTextService.appendLastKnownTooltip(
                            LocalizedText.format(
                                    language,
                                    "tamework.ui.linkedPanel.hunger.tooltip",
                                    percent(entry.hungerRatio())
                            ), entry, language),
                    true,
                    !entry.loaded()
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.dead"), false, false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.lost"), false, false);
        }
        if (!entry.loaded()) {
            if (entry.inCoop()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.inCoop"), false, false);
            }
            if (entry.captured()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.captured"), false, false);
            }
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.unloaded"), false, false);
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable"), false, false);
    }

    private static NeedVisual resolveThirstNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasThirst()) {
            return new NeedVisual(
                    entry.thirstRatio(),
                    LinkedNpcPanelStatusTextService.appendLastKnownTooltip(
                            LocalizedText.format(
                                    language,
                                    "tamework.ui.linkedPanel.thirst.tooltip",
                                    percent(entry.thirstRatio())
                            ), entry, language),
                    true,
                    !entry.loaded()
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.dead"), false, false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.lost"), false, false);
        }
        if (!entry.loaded()) {
            if (entry.inCoop()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.inCoop"), false, false);
            }
            if (entry.captured()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.captured"), false, false);
            }
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.unloaded"), false, false);
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable"), false, false);
    }

    private static void bindNeedMeter(UICommandBuilder commandBuilder,
                                      String slotSelector,
                                      NeedIcon icon,
                                      NeedVisual visual,
                                      boolean visible,
                                      String fillColor) {
        commandBuilder.set(slotSelector + ".Visible", visible);
        if (!visible) {
            return;
        }
        if (icon.hasTexturePath()) {
            commandBuilder.set(slotSelector + " #NeedIcon.Visible", false);
            commandBuilder.set(slotSelector + " #NeedIconImage.Visible", true);
            commandBuilder.set(slotSelector + " #NeedIconImage.Background", icon.texturePath());
        } else {
            commandBuilder.set(slotSelector + " #NeedIconImage.Visible", false);
            commandBuilder.set(slotSelector + " #NeedIcon.Visible", true);
            commandBuilder.set(slotSelector + " #NeedIcon.Text", icon.fallbackText());
        }
        commandBuilder.set(slotSelector + " #NeedTooltip.TooltipText", visual.tooltipText());
        commandBuilder.set(slotSelector + " #NeedTooltip.TooltipTextSpans", visual.tooltipSpans());
        commandBuilder.set(slotSelector + " #NeedValueText.Text",
                visual.available() ? percent(visual.fillRatio()) + "%" : "—");
        commandBuilder.set(slotSelector + " #NeedValueText.Style", Value.ref(
                "TameworkLinkedNpcPanelCard.ui",
                visual.muted() ? "CooldownTextMuted" : "CooldownTextNormal"));
        commandBuilder.setObject(
                slotSelector + " #MeterFill.Anchor",
                LinkedNpcPanelStatusMeter.buildNeedFillAnchor(visual.available() ? visual.fillRatio() : 0.0)
        );
        commandBuilder.set(slotSelector + " #MeterFill.Background",
                visual.muted() ? MUTED_FILL_COLOR : fillColor);
    }

    private static void bindBreedingCooldownMeter(UICommandBuilder commands, String card,
                                                  LinkedNpcEntry entry, String language) {
        boolean muted = !entry.loaded() || !entry.breedingEnabled()
                || LinkedNpcPanelStatusTextService.breedingBlockedByHappiness(entry);
        bindCooldownMeter(commands, card + " #BreedingCooldown",
                !entry.lost() && entry.breedingCooldownKnown(), (entry.loaded() || entry.breedingEnabled()) && entry.breedingCooldownActive(),
                entry.breedingCooldownRatio(), !entry.loaded() && !entry.breedingEnabled() ? 0L : entry.breedingCooldownRemainingMs(),
                LocalizedText.resolve(language, entry.breedingEnabled()
                        ? "tamework.ui.linkedPanel.breedingCooldown.ready"
                        : "tamework.ui.linkedPanel.action.breedingOff"),
                LinkedNpcPanelStatusTextService.resolveBreedingCooldownTooltip(entry, language),
                "#BreedingCooldownTooltip", muted, "#bb959e", language, entry);
        commands.set(card + " #BreedingCooldownIconImage.Visible", !muted);
        commands.set(card + " #BreedingCooldownIconMuted.Visible", muted);
    }

    private static void bindHarvestCooldownMeter(UICommandBuilder commands, String card,
                                                 LinkedNpcEntry entry, String language) {
        bindCooldownMeter(commands, card + " #HarvestCooldown",
                !entry.lost() && entry.harvestCooldownKnown(), entry.harvestCooldownActive(),
                entry.harvestCooldownRatio(), entry.harvestCooldownRemainingMs(),
                LocalizedText.resolve(language, "tamework.ui.linkedPanel.harvestCooldown.ready"),
                LinkedNpcPanelStatusTextService.resolveHarvestCooldownTooltip(entry, language),
                "#HarvestCooldownTooltip", !entry.loaded(), "#cbbb88", language, entry);
    }

    private static void bindCooldownMeter(UICommandBuilder commands, String slot, boolean visible,
                                          boolean active, double ratio, long remainingMs,
                                          String readyText, String tooltip, String tooltipSelector,
                                          boolean muted, String fillColor, String language,
                                          LinkedNpcEntry entry) {
        commands.set(slot + ".Visible", visible);
        commands.set(slot + " #CooldownText.Text",
                LinkedNpcPanelStatusTextService.resolveCooldownLabel(
                        active, remainingMs, readyText, entry, language));
        commands.set(slot + " " + tooltipSelector + ".TooltipText", tooltip);
        commands.set(slot + " #CooldownText.Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                muted ? "CooldownTextMuted" : "CooldownTextNormal"));
        commands.set(slot + " #CooldownLabel.Style", Value.ref("TameworkLinkedNpcPanelCard.ui",
                muted ? "CooldownTextMuted" : "CooldownLabelNormal"));
        commands.set(slot + " #MeterFill.Background", muted ? "#727772" : fillColor);
        commands.setObject(slot + " #MeterFill.Anchor",
                LinkedNpcPanelStatusMeter.buildFillAnchor(
                        remainingMs < 0L ? 0.0 : active ? ratio : 1.0));
    }

    private static int percent(double ratio) {
        return (int) Math.round(clamp(ratio) * 100.0);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Nonnull
    static Message happinessTooltipSpans(@Nonnull String tooltip, @Nullable String language) {
        String activeHeading = LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.activeEffects");
        String allHeading = LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.allEffects");
        boolean inactive = false;
        List<Message> spans = new ArrayList<>();
        String[] lines = tooltip.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String prefix = index == 0 ? "" : "\n";
            if (line.equals(allHeading)) {
                inactive = true;
                spans.add(Message.raw(prefix + line).color(TOOLTIP_GRAY));
                continue;
            }
            if (line.equals(activeHeading)) {
                spans.add(Message.raw(prefix + line).color(TOOLTIP_WHITE));
                continue;
            }
            if (inactive) {
                spans.add(Message.raw(prefix + line).color(TOOLTIP_GRAY));
                continue;
            }
            int delimiter = line.lastIndexOf(": ");
            if (delimiter > 0 && delimiter + 2 < line.length()) {
                String value = line.substring(delimiter + 2);
                Color valueColor = value.startsWith("+") ? TOOLTIP_GREEN
                        : value.startsWith("-") ? TOOLTIP_RED : TOOLTIP_WHITE;
                spans.add(Message.raw(prefix + line.substring(0, delimiter + 2)).color(TOOLTIP_WHITE));
                spans.add(Message.raw(value).color(valueColor));
                continue;
            }
            spans.add(Message.raw(prefix + line).color(TOOLTIP_WHITE));
        }
        return Message.join(spans.toArray(new Message[0]));
    }

    private record NeedVisual(double fillRatio,
                              String tooltipText,
                              boolean available,
                              boolean muted,
                              Message tooltipSpans) {
        private NeedVisual(double fillRatio, String tooltipText, boolean available, boolean muted) {
            this(fillRatio, tooltipText, available, muted, Message.raw(tooltipText));
        }
    }

    private record NeedIcon(String fallbackText, String texturePath) {
        private boolean hasTexturePath() {
            return texturePath != null && !texturePath.isBlank();
        }
    }

}
