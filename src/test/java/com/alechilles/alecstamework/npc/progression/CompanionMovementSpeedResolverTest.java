package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompanionMovementSpeedResolverTest {
    private final CompanionMovementSpeedResolver resolver = new CompanionMovementSpeedResolver();

    @Test
    void combinesBaseMatchingAttachmentModifiersAndProgressionThenQuantizes() {
        var result = resolver.resolve(config("mod:moose", 1.10, 0.50, 2.00, saddle("Yes", 1.10)),
                attachments("Saddle", "Yes"), 1.05);

        assertEquals(1.2705, result.rawMultiplier(), 0.0000001);
        assertEquals(1.2705, result.clampedMultiplier(), 0.0000001);
        assertEquals(1.25, result.quantizedMultiplier());
        assertEquals("mod:moose", result.configId());
    }

    @Test
    void appliesEveryMatchingAttachmentModifier() {
        var result = resolver.resolve(config("mod:moose", 1.0, 0.50, 2.00,
                saddle("Leather", 1.10), saddle("Leather", 1.20)), attachments("Saddle", "Leather"), 1.0);

        assertEquals(1.32, result.rawMultiplier(), 0.0000001);
        assertEquals(1.30, result.quantizedMultiplier());
    }

    @Test
    void ignoresNonMatchingAttachmentModifiers() {
        var result = resolver.resolve(config("mod:moose", 1.10, 0.50, 2.00, saddle("Leather", 1.50)),
                attachments("Saddle", "Iron"), 1.0);

        assertEquals(1.10, result.quantizedMultiplier());
    }

    @Test
    void replacesMalformedAndNonFiniteInputsWithNeutralMultiplier() {
        var result = resolver.resolve(config("mod:moose", Double.NaN, Double.NaN, Double.POSITIVE_INFINITY,
                saddle("Leather", Double.NaN)), attachments("Saddle", "Leather"), Double.NEGATIVE_INFINITY);

        assertEquals(1.0, result.rawMultiplier());
        assertEquals(1.0, result.clampedMultiplier());
        assertEquals(1.0, result.quantizedMultiplier());
    }

    @Test
    void clampsToSupportedBoundsBeforeQuantizing() {
        var low = resolver.resolve(config("mod:moose", 0.10, 0.10, 9.0), Map.of(), 1.0);
        var high = resolver.resolve(config("mod:moose", 9.0, 0.10, 9.0), Map.of(), 1.0);

        assertEquals(0.50, low.clampedMultiplier());
        assertEquals(0.50, low.quantizedMultiplier());
        assertEquals(2.00, high.clampedMultiplier());
        assertEquals(2.00, high.quantizedMultiplier());
    }

    @Test
    void quantizesNearestFivePercentUsingIntegerHundredths() {
        var down = resolver.resolve(config("mod:moose", 1.124999999999, 0.50, 2.00), Map.of(), 1.0);
        var up = resolver.resolve(config("mod:moose", 1.125, 0.50, 2.00), Map.of(), 1.0);

        assertEquals(1.10, down.quantizedMultiplier());
        assertEquals(1.15, up.quantizedMultiplier());
    }

    private static TwCompanionMovementConfig.ResolvedMovement config(String id, double base, double min, double max,
                                                                       TwCompanionMovementConfig.AttachmentModifier... modifiers) {
        return new TwCompanionMovementConfig.ResolvedMovement(id, base, min, max, List.of(modifiers));
    }

    private static TwCompanionMovementConfig.AttachmentModifier saddle(String value, double multiplier) {
        return new TwCompanionMovementConfig.AttachmentModifier("Saddle", List.of(value), multiplier);
    }

    private static Map<String, String> attachments(String slot, String value) {
        return Map.of(slot, value);
    }
}
