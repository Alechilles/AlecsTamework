package com.alechilles.alecstamework.integration.nameplatebuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NameplateBuilderCompanionSegmentBridgeTest {
    private static final NameplateBuilderCompanionSegmentBridge.BoundedValue SAMPLE_VALUE =
            new NameplateBuilderCompanionSegmentBridge.BoundedValue(80, 100, 80);

    @Test
    void computePercentClampsWithinRange() {
        Assertions.assertEquals(0, NameplateBuilderCompanionSegmentBridge.computePercent(-10.0, 0.0, 100.0));
        Assertions.assertEquals(75, NameplateBuilderCompanionSegmentBridge.computePercent(75.0, 0.0, 100.0));
        Assertions.assertEquals(100, NameplateBuilderCompanionSegmentBridge.computePercent(150.0, 0.0, 100.0));
    }

    @Test
    void formatRemainingDurationUsesCompactSecondsAndMinutes() {
        Assertions.assertEquals("18s", NameplateBuilderCompanionSegmentBridge.formatRemainingDuration(17.2));
        Assertions.assertEquals("1m 5s", NameplateBuilderCompanionSegmentBridge.formatRemainingDuration(64.1));
    }

    @Test
    void computeTranquilizerStacksRoundsFromPeakDuration() {
        Assertions.assertEquals(0, NameplateBuilderCompanionSegmentBridge.computeTranquilizerStacks(0.0));
        Assertions.assertEquals(1, NameplateBuilderCompanionSegmentBridge.computeTranquilizerStacks(30.0));
        Assertions.assertEquals(3, NameplateBuilderCompanionSegmentBridge.computeTranquilizerStacks(80.0));
        Assertions.assertEquals(4, NameplateBuilderCompanionSegmentBridge.computeTranquilizerStacks(105.0));
    }

    @Test
    void formatTranquilizerValueSupportsVariants() {
        Assertions.assertEquals("3 (1m 45s)", NameplateBuilderCompanionSegmentBridge.formatTranquilizerValue(3, "1m 45s", 0));
        Assertions.assertEquals("3", NameplateBuilderCompanionSegmentBridge.formatTranquilizerValue(3, "1m 45s", 1));
        Assertions.assertEquals("1m 45s", NameplateBuilderCompanionSegmentBridge.formatTranquilizerValue(3, "1m 45s", 2));
    }

    @Test
    void resolvePeakDurationPrefersTrackedPeakButFallsBackToCurrent() {
        Assertions.assertEquals(80.0, NameplateBuilderCompanionSegmentBridge.resolvePeakDuration(80.0, 45.0));
        Assertions.assertEquals(65.0, NameplateBuilderCompanionSegmentBridge.resolvePeakDuration(0.0, 65.0));
        Assertions.assertEquals(0.0, NameplateBuilderCompanionSegmentBridge.resolvePeakDuration(0.0, 0.0));
    }

    @Test
    void formatTraitRelativePercentMatchesLinkedPanelStyle() {
        Assertions.assertEquals("50%", NameplateBuilderCompanionSegmentBridge.formatTraitRelativePercent(1.05, 0.9, 1.0, 1.1));
        Assertions.assertEquals("-50%", NameplateBuilderCompanionSegmentBridge.formatTraitRelativePercent(0.95, 0.9, 1.0, 1.1));
        Assertions.assertEquals("0%", NameplateBuilderCompanionSegmentBridge.formatTraitRelativePercent(1.0, 0.9, 1.0, 1.1));
    }

    @Test
    void formatBoundedValueSupportsShortLabelVariants() {
        Assertions.assertEquals("80%", NameplateBuilderCompanionSegmentBridge.formatBoundedValue(SAMPLE_VALUE, 0, "Hap"));
        Assertions.assertEquals("80/100", NameplateBuilderCompanionSegmentBridge.formatBoundedValue(SAMPLE_VALUE, 1, "Hap"));
        Assertions.assertEquals("Hap 80%", NameplateBuilderCompanionSegmentBridge.formatBoundedValue(SAMPLE_VALUE, 2, "Hap"));
        Assertions.assertEquals("Hap 80/100", NameplateBuilderCompanionSegmentBridge.formatBoundedValue(SAMPLE_VALUE, 3, "Hap"));
    }

    @Test
    void abbreviateLabelUsesFirstThreeCharacters() {
        Assertions.assertEquals("Tou", NameplateBuilderCompanionSegmentBridge.abbreviateLabel("Toughness"));
        Assertions.assertEquals("Agi", NameplateBuilderCompanionSegmentBridge.abbreviateLabel("Agility"));
        Assertions.assertEquals("Str", NameplateBuilderCompanionSegmentBridge.abbreviateLabel("Strength"));
    }
}
