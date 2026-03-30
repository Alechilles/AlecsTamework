package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies linked companion vitals to UI card controls.
 */
final class LinkedNpcPanelVitalsBinder {
    private static final int VITAL_FILL_MAX_WIDTH = 204;
    private static final double NEED_RING_BAR1_LENGTH = 12.0;
    private static final double NEED_RING_BAR2_LENGTH = 24.0;
    private static final double NEED_RING_BAR3_LENGTH = 24.0;
    private static final double NEED_RING_BAR4_LENGTH = 24.0;
    private static final double NEED_RING_BAR5_LENGTH = 12.0;
    private static final double NEED_RING_PERIMETER_PIXELS =
            NEED_RING_BAR1_LENGTH + NEED_RING_BAR2_LENGTH + NEED_RING_BAR3_LENGTH + NEED_RING_BAR4_LENGTH + NEED_RING_BAR5_LENGTH;
    private static final String ICON_NEED_HAPPINESS = "Tamework/LinkedPanelIcons/Need_Happiness.png";
    private static final String ICON_NEED_HUNGER = "Tamework/LinkedPanelIcons/Need_Hunger.png";
    private static final String ICON_NEED_THIRST = "Tamework/LinkedPanelIcons/Need_Thirst.png";

    private LinkedNpcPanelVitalsBinder() {
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bind(commandBuilder, entrySelector, entry, null);
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry, String language) {
        bindHealth(commandBuilder, entrySelector, entry, language);
        bindNeedRings(commandBuilder, entrySelector, entry, language);
        bindBreedingCooldown(commandBuilder, entrySelector, entry, language);
    }

    private static void bindHealth(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry, String language) {
        String healthTextSelector = entrySelector + " #HealthText";
        String healthTextShadowSelector = entrySelector + " #HealthTextShadow";
        String healthFillSelector = entrySelector + " #HealthFill";
        if (entry.hasHealth()) {
            String healthText = entry.currentHealth() + "/" + entry.maxHealth();
            commandBuilder.set(healthTextSelector + ".Text", healthText);
            commandBuilder.set(healthTextShadowSelector + ".Text", healthText);
            commandBuilder.set(healthFillSelector + ".Visible", true);
            commandBuilder.setObject(
                    healthFillSelector + ".Anchor",
                    LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(entry.healthRatio(), VITAL_FILL_MAX_WIDTH)
            );
            return;
        }
        if (entry.dead()) {
            String deadText = LinkedNpcPanelStatusTextService.resolveDeadHealthText(entry, language);
            commandBuilder.set(healthTextSelector + ".Text", deadText);
            commandBuilder.set(healthTextShadowSelector + ".Text", deadText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (entry.inCoop()) {
            String coopText = LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.inCoop");
            commandBuilder.set(healthTextSelector + ".Text", coopText);
            commandBuilder.set(healthTextShadowSelector + ".Text", coopText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (entry.lost()) {
            String lostText = LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.lost");
            commandBuilder.set(healthTextSelector + ".Text", lostText);
            commandBuilder.set(healthTextShadowSelector + ".Text", lostText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            String statusText = entry.captured()
                    ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.captured")
                    : LocalizedText.resolve(language, "tamework.ui.linkedPanel.status.unloaded");
            commandBuilder.set(healthTextSelector + ".Text", statusText);
            commandBuilder.set(healthTextShadowSelector + ".Text", statusText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        String notAvailable = LocalizedText.resolve(language, "tamework.ui.linkedPanel.health.notAvailable");
        commandBuilder.set(healthTextSelector + ".Text", notAvailable);
        commandBuilder.set(healthTextShadowSelector + ".Text", notAvailable);
        commandBuilder.set(healthFillSelector + ".Visible", false);
    }

    private static void bindNeedRings(UICommandBuilder commandBuilder,
                                      String entrySelector,
                                      LinkedNpcEntry entry,
                                      String language) {
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedHappiness",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.happiness"), ICON_NEED_HAPPINESS),
                resolveHappinessNeed(entry, language)
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedHunger",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.hunger"), ICON_NEED_HUNGER),
                resolveHungerNeed(entry, language)
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedThirst",
                new NeedIcon(LocalizedText.resolve(language, "tamework.ui.linkedPanel.needIcons.thirst"), ICON_NEED_THIRST),
                resolveThirstNeed(entry, language)
        );
    }

    private static NeedVisual resolveHappinessNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasHappiness()) {
            String tooltip = LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.happiness.tooltip",
                    entry.currentHappiness(),
                    entry.maxHappiness(),
                    percent(entry.happinessRatio())
            );
            if (entry.happinessModifierBreakdown() != null && !entry.happinessModifierBreakdown().isBlank()) {
                tooltip = tooltip + "\n" + entry.happinessModifierBreakdown();
            }
            return new NeedVisual(
                    entry.happinessRatio(),
                    tooltip,
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LinkedNpcPanelStatusTextService.resolveDeadHappinessText(entry, language), false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable.lost"), false);
        }
        if (!entry.loaded()) {
            return new NeedVisual(
                    0.0,
                    LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(entry, language),
                    false
            );
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.unavailable"), false);
    }

    private static NeedVisual resolveHungerNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasHunger()) {
            return new NeedVisual(
                    entry.hungerRatio(),
                    LocalizedText.format(
                            language,
                            "tamework.ui.linkedPanel.hunger.tooltip",
                            entry.currentHunger(),
                            entry.maxHunger(),
                            percent(entry.hungerRatio())
                    ),
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.dead"), false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.lost"), false);
        }
        if (!entry.loaded()) {
            if (entry.inCoop()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.inCoop"), false);
            }
            if (entry.captured()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.captured"), false);
            }
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable.unloaded"), false);
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.hunger.unavailable"), false);
    }

    private static NeedVisual resolveThirstNeed(LinkedNpcEntry entry, String language) {
        if (entry.hasThirst()) {
            return new NeedVisual(
                    entry.thirstRatio(),
                    LocalizedText.format(
                            language,
                            "tamework.ui.linkedPanel.thirst.tooltip",
                            entry.currentThirst(),
                            entry.maxThirst(),
                            percent(entry.thirstRatio())
                    ),
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.dead"), false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.lost"), false);
        }
        if (!entry.loaded()) {
            if (entry.inCoop()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.inCoop"), false);
            }
            if (entry.captured()) {
                return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.captured"), false);
            }
            return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable.unloaded"), false);
        }
        return new NeedVisual(0.0, LocalizedText.resolve(language, "tamework.ui.linkedPanel.thirst.unavailable"), false);
    }

    private static void bindNeedRing(UICommandBuilder commandBuilder,
                                     String slotSelector,
                                     NeedIcon icon,
                                     NeedVisual visual) {
        commandBuilder.set(slotSelector + ".Visible", true);
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
        SegmentFill fill = resolveSegmentFill(visual.available() ? visual.fillRatio() : 0.0);
        commandBuilder.setObject(slotSelector + " #RingFillBar1.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar1Anchor(fill.bar1()));
        commandBuilder.setObject(slotSelector + " #RingFillBar2.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar2Anchor(fill.bar2()));
        commandBuilder.setObject(slotSelector + " #RingFillBar3.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar3Anchor(fill.bar3()));
        commandBuilder.setObject(slotSelector + " #RingFillBar4.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar4Anchor(fill.bar4()));
        commandBuilder.setObject(slotSelector + " #RingFillBar5.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar5Anchor(fill.bar5()));
    }

    private static void bindBreedingCooldown(UICommandBuilder commandBuilder,
                                             String entrySelector,
                                             LinkedNpcEntry entry,
                                             String language) {
        String slotSelector = entrySelector + " #BreedingCooldown";
        boolean cooldownRecharging = entry.breedingCooldownKnown() && entry.breedingCooldownActive();
        commandBuilder.set(slotSelector + ".Visible", cooldownRecharging);
        if (!cooldownRecharging) {
            return;
        }
        commandBuilder.set(
                slotSelector + " #BreedingCooldownTooltip.TooltipText",
                LinkedNpcPanelStatusTextService.resolveBreedingCooldownTooltip(entry, language)
        );
        SegmentFill fill = resolveSegmentFill(entry.breedingCooldownRatio());
        commandBuilder.setObject(slotSelector + " #RingFillBar1.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar1Anchor(fill.bar1()));
        commandBuilder.setObject(slotSelector + " #RingFillBar2.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar2Anchor(fill.bar2()));
        commandBuilder.setObject(slotSelector + " #RingFillBar3.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar3Anchor(fill.bar3()));
        commandBuilder.setObject(slotSelector + " #RingFillBar4.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar4Anchor(fill.bar4()));
        commandBuilder.setObject(slotSelector + " #RingFillBar5.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar5Anchor(fill.bar5()));
    }

    private static SegmentFill resolveSegmentFill(double fillRatio) {
        int coveredPixels = (int) Math.floor(clamp(fillRatio) * NEED_RING_PERIMETER_PIXELS + 1.0e-9);
        int remaining = coveredPixels;
        int bar1 = consumePixels(remaining, (int) NEED_RING_BAR1_LENGTH);
        remaining -= bar1;
        int bar2 = consumePixels(remaining, (int) NEED_RING_BAR2_LENGTH);
        remaining -= bar2;
        int bar3 = consumePixels(remaining, (int) NEED_RING_BAR3_LENGTH);
        remaining -= bar3;
        int bar4 = consumePixels(remaining, (int) NEED_RING_BAR4_LENGTH);
        remaining -= bar4;
        int bar5 = consumePixels(remaining, (int) NEED_RING_BAR5_LENGTH);
        return new SegmentFill(
                bar1,
                bar2,
                bar3,
                bar4,
                bar5
        );
    }

    private static int consumePixels(int remaining, int segmentLength) {
        return Math.max(0, Math.min(segmentLength, remaining));
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

    private record NeedVisual(double fillRatio, String tooltipText, boolean available) {
    }

    private record NeedIcon(String fallbackText, String texturePath) {
        private boolean hasTexturePath() {
            return texturePath != null && !texturePath.isBlank();
        }
    }

    private record SegmentFill(int bar1, int bar2, int bar3, int bar4, int bar5) {
    }
}
