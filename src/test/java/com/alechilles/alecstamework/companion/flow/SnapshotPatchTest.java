package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.time.Instant;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPatchTest {
    private static BsonDocument entity(BsonDocument components) {
        return new BsonDocument("Components", components);
    }

    /** Alarm instants are written by the engine's own Instant codec (Alarm.CODEC key "Instant"). */
    private static BsonValue encoded(long epochMs) {
        return Codec.INSTANT.encode(Instant.ofEpochMilli(epochMs), EmptyExtraInfo.EMPTY);
    }

    private static long hunt(BsonDocument entity) {
        BsonValue instant = entity.getDocument("Components").getDocument("AlarmStore").getDocument("Parameters")
                .getDocument("hunt").get("Instant");
        return Codec.INSTANT.decode(instant, EmptyExtraInfo.EMPTY).toEpochMilli();
    }

    @Test
    void alarmInstantsMoveByTheClockDifferenceAndTheInputIsUntouched() {
        BsonDocument alarms = new BsonDocument("Parameters", new BsonDocument("hunt",
                new BsonDocument("Instant", encoded(5_000L))));
        BsonDocument original = entity(new BsonDocument("AlarmStore", alarms));

        BsonDocument rebased = SnapshotPatch.rebaseAlarms(original, -2_000L);

        assertEquals(3_000L, hunt(rebased));
        assertEquals(5_000L, hunt(original));
    }

    @Test
    void tameworkDeadlinesOnWorldTimeMoveButUnsetOnesAndBodyCarriedClocksStay() {
        BsonDocument components = new BsonDocument("TameworkAlarm", new BsonDocument("Alarms", new BsonArray(List.of(
                new BsonDocument("Name", new BsonString("graze")).append("UntilMs", new BsonInt64(10_000L))
                        .append("StartedAtMs", new BsonInt64(0L))))))
                .append("TameworkBreeding", new BsonDocument("CooldownUntilMs", new BsonInt64(-4_000L))
                        .append("ManualBreedingUntilMs", new BsonInt64(9_000L)));

        BsonDocument rebased = SnapshotPatch.rebaseAlarms(entity(components), -2_000L).getDocument("Components");

        BsonDocument alarm = rebased.getDocument("TameworkAlarm").getArray("Alarms").get(0).asDocument();
        assertEquals(8_000L, alarm.getInt64("UntilMs").getValue());
        assertEquals(0L, alarm.getInt64("StartedAtMs").getValue());
        assertEquals(-6_000L, rebased.getDocument("TameworkBreeding").getInt64("CooldownUntilMs").getValue());
        assertEquals(9_000L, rebased.getDocument("TameworkBreeding").getInt64("ManualBreedingUntilMs").getValue());

        // Once progression is initialized, AnimalProgressionService.currentTimeMs is a clock the body carries.
        components.append("TameworkLifeStage", new BsonDocument("ProgressionInitialized", BsonBoolean.TRUE));
        BsonDocument carried = SnapshotPatch.rebaseAlarms(entity(components), -2_000L).getDocument("Components");
        assertEquals(-4_000L, carried.getDocument("TameworkBreeding").getInt64("CooldownUntilMs").getValue());
    }

    @Test
    void aReviveDropsDeathAndNeedsSoTheCompanionDoesNotDieAgain() {
        BsonDocument original = entity(new BsonDocument("Death", new BsonDocument())
                .append("TameworkNeeds", new BsonDocument("Hunger", new BsonInt64(0)))
                .append("NPC", new BsonDocument()));

        BsonDocument revived = SnapshotPatch.forRevive(original);

        assertTrue(SnapshotPatch.isDeathSnapshot(original));
        assertFalse(SnapshotPatch.isDeathSnapshot(revived));
        assertFalse(revived.getDocument("Components").containsKey("TameworkNeeds"));
        assertTrue(revived.getDocument("Components").containsKey("NPC"));
    }
}
