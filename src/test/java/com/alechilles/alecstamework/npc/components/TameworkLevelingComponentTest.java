package com.alechilles.alecstamework.npc.components;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers persistence compatibility for active-summon XP cadence state. */
class TameworkLevelingComponentTest {
    private static final double EPSILON = 0.00001d;

    @Test
    void codecRoundTripAndClonePreserveSummonedCadenceState() {
        TameworkLevelingComponent decoded = TameworkLevelingComponent.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "ConfigId": "level",
                          "Level": 4,
                          "CurrentXp": 20.0,
                          "TotalXp": 50.0,
                          "SummonedActiveSeconds": 3.5,
                          "SummonedWindowAwardedXp": 8.0,
                          "SummonedWindowStartedAtMs": 1000,
                          "SummonedLastSampleAtMs": 1250
                        }
                        """),
                new ExtraInfo()
        );

        TameworkLevelingComponent clone = decoded.clone();

        assertEquals(3.5, clone.getSummonedActiveSeconds(), EPSILON);
        assertEquals(8.0, clone.getSummonedWindowAwardedXp(), EPSILON);
        assertEquals(1_000L, clone.getSummonedWindowStartedAtMs());
        assertEquals(1_250L, clone.getSummonedLastSampleAtMs());
    }

    @Test
    void legacyCodecPayloadDefaultsSummonedCadenceStateToEmpty() {
        TameworkLevelingComponent decoded = TameworkLevelingComponent.CODEC.decode(
                BsonDocument.parse("{ \"ConfigId\": \"level\", \"Level\": 2 }"),
                new ExtraInfo()
        );

        assertEquals(0.0, decoded.getSummonedActiveSeconds(), EPSILON);
        assertEquals(0.0, decoded.getSummonedWindowAwardedXp(), EPSILON);
        assertEquals(0L, decoded.getSummonedWindowStartedAtMs());
        assertEquals(0L, decoded.getSummonedLastSampleAtMs());
    }
}
