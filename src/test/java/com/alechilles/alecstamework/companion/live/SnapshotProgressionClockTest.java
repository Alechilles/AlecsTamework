package com.alechilles.alecstamework.companion.live;

import java.util.UUID;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SnapshotProgressionClockTest {
    private static final UUID OWNER = UUID.randomUUID();

    private static BsonDocument body(long progressionClockMs, boolean paused) {
        BsonDocument lifeStage = new BsonDocument("ProgressionInitialized", BsonBoolean.TRUE)
                .append("StoredProgressionPaused", BsonBoolean.valueOf(paused))
                .append("ProgressionOwnerId", new BsonString(OWNER.toString()))
                .append("ProgressionClockMs", new BsonInt64(progressionClockMs));
        return new BsonDocument("Components", new BsonDocument("TameworkLifeStage", lifeStage));
    }

    private static long clockOf(BsonDocument entity) {
        return entity.getDocument("Components").getDocument("TameworkLifeStage").getInt64("ProgressionClockMs").getValue();
    }

    /** The time pending at capture survives the restore; the time out of the world and the other server's clock do not. */
    @Test
    void aRestoreKeepsOnlyThePendingTimeWhateverTheDestinationClockSays() {
        BsonDocument entity = body(1_000L, false);
        BsonDocument data = SnapshotProgressionClock.withReading(new BsonDocument(), entity, owner -> 1_500L);

        for (long destination : new long[] {200L, 1_500L, 90_000L}) {
            BsonDocument restored = SnapshotProgressionClock.rebase(entity, data, owner -> destination);
            assertEquals(500L, destination - clockOf(restored), "pending time at destination clock " + destination);
        }
        assertEquals(1_000L, clockOf(entity), "the snapshot itself is not changed");
    }

    @Test
    void aSnapshotWithoutAReadingOrWithPausedProgressionRestoresAsItWas() {
        BsonDocument running = body(1_000L, false);
        assertEquals(1_000L, clockOf(SnapshotProgressionClock.rebase(running, new BsonDocument(), owner -> 9_000L)));

        BsonDocument paused = body(1_000L, true);
        BsonDocument data = SnapshotProgressionClock.withReading(new BsonDocument(), paused, owner -> 1_500L);
        assertFalse(data.containsKey(SnapshotProgressionClock.OWNER_CLOCK_MS));
        assertEquals(1_000L, clockOf(SnapshotProgressionClock.rebase(paused,
                new BsonDocument(SnapshotProgressionClock.OWNER_CLOCK_MS, new BsonInt64(1_500L)), owner -> 9_000L)));
    }
}
