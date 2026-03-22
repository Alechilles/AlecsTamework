package com.alechilles.alecstamework.ui;

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
        bindHealth(commandBuilder, entrySelector, entry);
        bindNeedRings(commandBuilder, entrySelector, entry);
        bindBreedingCooldown(commandBuilder, entrySelector, entry);
    }

    private static void bindHealth(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
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
            String deadText = LinkedNpcPanelStatusTextService.resolveDeadHealthText(entry);
            commandBuilder.set(healthTextSelector + ".Text", deadText);
            commandBuilder.set(healthTextShadowSelector + ".Text", deadText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (entry.lost()) {
            String lostText = "Lost";
            commandBuilder.set(healthTextSelector + ".Text", lostText);
            commandBuilder.set(healthTextShadowSelector + ".Text", lostText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            String statusText = entry.captured() ? "Captured" : "Unloaded";
            commandBuilder.set(healthTextSelector + ".Text", statusText);
            commandBuilder.set(healthTextShadowSelector + ".Text", statusText);
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        commandBuilder.set(healthTextSelector + ".Text", "N/A");
        commandBuilder.set(healthTextShadowSelector + ".Text", "N/A");
        commandBuilder.set(healthFillSelector + ".Visible", false);
    }

    private static void bindNeedRings(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedHappiness",
                new NeedIcon("M", ICON_NEED_HAPPINESS),
                resolveHappinessNeed(entry)
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedHunger",
                new NeedIcon("F", ICON_NEED_HUNGER),
                resolveHungerNeed(entry)
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedThirst",
                new NeedIcon("W", ICON_NEED_THIRST),
                resolveThirstNeed(entry)
        );
    }

    private static NeedVisual resolveHappinessNeed(LinkedNpcEntry entry) {
        if (entry.hasHappiness()) {
            String tooltip = "Happiness: " + entry.currentHappiness() + "/" + entry.maxHappiness()
                    + " (" + percent(entry.happinessRatio()) + "%)";
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
            return new NeedVisual(0.0, LinkedNpcPanelStatusTextService.resolveDeadHappinessText(entry), false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, "Happiness: unavailable (lost).", false);
        }
        if (!entry.loaded()) {
            return new NeedVisual(
                    0.0,
                    LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(entry),
                    false
            );
        }
        return new NeedVisual(0.0, "Happiness: unavailable", false);
    }

    private static NeedVisual resolveHungerNeed(LinkedNpcEntry entry) {
        if (entry.hasHunger()) {
            return new NeedVisual(
                    entry.hungerRatio(),
                    "Hunger: " + entry.currentHunger() + "/" + entry.maxHunger()
                            + " (" + percent(entry.hungerRatio()) + "%)",
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, "Hunger: unavailable (dead)", false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, "Hunger: unavailable (lost)", false);
        }
        if (!entry.loaded()) {
            if (entry.captured()) {
                return new NeedVisual(0.0, "Hunger: unavailable (captured)", false);
            }
            return new NeedVisual(0.0, "Hunger: unavailable (unloaded)", false);
        }
        return new NeedVisual(0.0, "Hunger: unavailable", false);
    }

    private static NeedVisual resolveThirstNeed(LinkedNpcEntry entry) {
        if (entry.hasThirst()) {
            return new NeedVisual(
                    entry.thirstRatio(),
                    "Thirst: " + entry.currentThirst() + "/" + entry.maxThirst()
                            + " (" + percent(entry.thirstRatio()) + "%)",
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, "Thirst: unavailable (dead)", false);
        }
        if (entry.lost()) {
            return new NeedVisual(0.0, "Thirst: unavailable (lost)", false);
        }
        if (!entry.loaded()) {
            if (entry.captured()) {
                return new NeedVisual(0.0, "Thirst: unavailable (captured)", false);
            }
            return new NeedVisual(0.0, "Thirst: unavailable (unloaded)", false);
        }
        return new NeedVisual(0.0, "Thirst: unavailable", false);
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
                                             LinkedNpcEntry entry) {
        String slotSelector = entrySelector + " #BreedingCooldown";
        boolean cooldownRecharging = entry.breedingCooldownKnown() && entry.breedingCooldownActive();
        commandBuilder.set(slotSelector + ".Visible", cooldownRecharging);
        if (!cooldownRecharging) {
            return;
        }
        commandBuilder.set(
                slotSelector + " #BreedingCooldownTooltip.TooltipText",
                LinkedNpcPanelStatusTextService.resolveBreedingCooldownTooltip(entry)
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
