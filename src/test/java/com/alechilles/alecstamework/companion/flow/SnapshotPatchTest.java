package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.time.Instant;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
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
