package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies linked companion vitals to UI card controls.
 */
final class LinkedNpcPanelVitalsBinder {
    private static final int VITAL_FILL_MAX_WIDTH = 204;
    private static final double NEED_RING_SEGMENT_MULTIPLIER = 4.0;
    private static final int NEED_RING_SEGMENT_PIXELS = 18;
    private static final String HAPPINESS_FILL_COLOR = "#f2c97c";
    private static final String HUNGER_FILL_COLOR = "#d9a066";
    private static final String THIRST_FILL_COLOR = "#76b7ea";
    private static final String UNAVAILABLE_FILL_COLOR = "#4e6077";

    private LinkedNpcPanelVitalsBinder() {
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bindHealth(commandBuilder, entrySelector, entry);
        bindNeedRings(commandBuilder, entrySelector, entry);
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
            commandBuilder.set(healthTextSelector + ".Text", "Dead");
            commandBuilder.set(healthTextShadowSelector + ".Text", "Dead");
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
                "M",
                resolveHappinessNeed(entry),
                HAPPINESS_FILL_COLOR
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedHunger",
                "F",
                resolveHungerNeed(entry),
                HUNGER_FILL_COLOR
        );
        bindNeedRing(
                commandBuilder,
                entrySelector + " #NeedThirst",
                "W",
                resolveThirstNeed(entry),
                THIRST_FILL_COLOR
        );
    }

    private static NeedVisual resolveHappinessNeed(LinkedNpcEntry entry) {
        if (entry.hasHappiness()) {
            return new NeedVisual(
                    entry.happinessRatio(),
                    "Happiness: " + entry.currentHappiness() + "/" + entry.maxHappiness()
                            + " (" + percent(entry.happinessRatio()) + "%)",
                    true
            );
        }
        if (entry.dead()) {
            return new NeedVisual(0.0, LinkedNpcPanelStatusTextService.resolveDeadHappinessText(entry), false);
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
                                     String iconText,
                                     NeedVisual visual,
                                     String activeFillColor) {
        commandBuilder.set(slotSelector + ".Visible", true);
        commandBuilder.set(slotSelector + " #NeedIcon.Text", iconText);
        commandBuilder.set(slotSelector + " #NeedTooltip.TooltipText", visual.tooltipText());
        String fillColor = visual.available() ? activeFillColor : UNAVAILABLE_FILL_COLOR;
        commandBuilder.set(slotSelector + " #RingFillTop.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillRight.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBottom.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillLeft.Background", fillColor);

        SegmentFill fill = resolveSegmentFill(visual.available() ? visual.fillRatio() : 0.0);
        commandBuilder.setObject(
                slotSelector + " #RingFillTop.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitTopFillAnchor(toPixels(fill.top()), true)
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillRight.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRightFillAnchor(toPixels(fill.right()), true)
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBottom.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitBottomFillAnchor(toPixels(fill.bottom()), true)
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillLeft.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitLeftFillAnchor(toPixels(fill.left()), true)
        );
    }

    private static SegmentFill resolveSegmentFill(double fillRatio) {
        double scaled = clamp(fillRatio) * NEED_RING_SEGMENT_MULTIPLIER;
        double first = resolveSegmentRatio(scaled, 0.0);
        double second = resolveSegmentRatio(scaled, 1.0);
        double third = resolveSegmentRatio(scaled, 2.0);
        double fourth = resolveSegmentRatio(scaled, 3.0);
        return new SegmentFill(first, fourth, third, second);
    }

    private static double resolveSegmentRatio(double scaledRatio, double segmentIndex) {
        return clamp(scaledRatio - segmentIndex);
    }

    private static int toPixels(double segmentRatio) {
        return (int) Math.round(clamp(segmentRatio) * NEED_RING_SEGMENT_PIXELS);
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

    private record SegmentFill(double top, double right, double bottom, double left) {
    }
}
