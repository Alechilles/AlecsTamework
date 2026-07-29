package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the generic active-summon XP configuration contract. */
class TwLevelingConfigTest {
    private static final double EPSILON = 0.00001d;

    @Test
    void decodedSummonedSectionKeepsChildOverridesAndInheritsMissingFields() {
        TwLevelingConfig parent = decode("""
                { "XpSources": { "Summoned": {
                    "Enabled": true,
                    "XpPerActiveSecond": 0.25,
                    "AwardIntervalSeconds": 12.0,
                    "MaxXpPerHour": 90.0
                } } }
                """);
        TwLevelingConfig child = decode("""
                { "XpSources": { "Summoned": { "XpPerActiveSecond": 0.5 } } }
                """);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("XpSources"),
                Map.of("XpSources", Set.of("Summoned", "Summoned.XpPerActiveSecond"))
        );

        TwLevelingConfig.SummonedXpSourceSettings summoned = child.getXpSources().getSummoned();
        assertTrue(summoned.isEnabled());
        assertEquals(0.5, summoned.getXpPerActiveSecond(), EPSILON);
        assertEquals(12.0, summoned.getAwardIntervalSeconds(), EPSILON);
        assertEquals(90.0, summoned.getMaxXpPerHour(), EPSILON);
    }

    @Test
    void enabledSummonedSettingsRejectNonPositiveAwardValues() {
        TwLevelingConfig.SummonedXpSourceSettings summoned = decode("""
                { "XpSources": { "Summoned": {
                    "Enabled": true,
                    "XpPerActiveSecond": -0.25,
                    "AwardIntervalSeconds": 0.0,
                    "MaxXpPerHour": -90.0
                } } }
                """).getXpSources().getSummoned();

        assertFalse(summoned.isEnabled());
        assertEquals(0.0, summoned.getXpPerActiveSecond(), EPSILON);
        assertEquals(0.0, summoned.getAwardIntervalSeconds(), EPSILON);
        assertEquals(0.0, summoned.getMaxXpPerHour(), EPSILON);
    }

    private static TwLevelingConfig decode(String json) {
        return TwLevelingConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
    }
}
