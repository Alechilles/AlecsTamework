package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreRulesTest {
    private static CompanionRecord live() {
        return CompanionTransitions.newLive(UUID.randomUUID(), 3, new CompanionTransitions.BodyFacts(UUID.randomUUID(),
                UUID.randomUUID(), "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY));
    }

    private static CompanionRecord dead(long reviveAt) {
        CompanionRecord live = live();
        return CompanionTransitions.died(live, CompanionSummary.EMPTY, 1_000L, reviveAt, "PLAYER", 1_000L)
                .apply(live.toBuilder()).build();
    }

    private static SnapshotEnvelope snapshot(CompanionRecord r, long generation, boolean dead) {
        BsonDocument components = new BsonDocument("NPC", new BsonDocument());
        if (dead) {
            components.append("Death", new BsonDocument());
        }
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", components))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(r.profileId(), CompanionSnapshots.FORMAT, generation, data);
    }

    @Test
    void reviveWaitsForTheCooldown() {
        assertEquals(RestoreRules.Verdict.COOLDOWN, RestoreRules.forRecord(dead(61_000L), RestoreRules.Reason.REVIVE, 60_000L));
        assertEquals(RestoreRules.Verdict.ALLOWED, RestoreRules.forRecord(dead(61_000L), RestoreRules.Reason.REVIVE, 61_000L));
    }

    @Test
    void eachReasonAcceptsOnlyItsLocations() {
        assertEquals(RestoreRules.Verdict.ALLOWED, RestoreRules.forRecord(live(), RestoreRules.Reason.RECALL, 0L));
        assertEquals(RestoreRules.Verdict.NOT_ALLOWED, RestoreRules.forRecord(dead(0L), RestoreRules.Reason.RECALL, 0L));
        assertEquals(RestoreRules.Verdict.NOT_ALLOWED, RestoreRules.forRecord(live(), RestoreRules.Reason.REVIVE, 0L));
        assertEquals(RestoreRules.Verdict.NOT_FOUND, RestoreRules.forRecord(null, RestoreRules.Reason.RECOVER, 0L));
    }

    @Test
    void aDeathSnapshotOnlyServesARevive() {
        CompanionRecord record = dead(0L);
        SnapshotEnvelope deathSnapshot = snapshot(record, record.generation(), true);

        assertEquals(RestoreRules.Verdict.ALLOWED, RestoreRules.forSnapshot(record, deathSnapshot, RestoreRules.Reason.REVIVE));
        CompanionRecord liveRecord = live();
        assertEquals(RestoreRules.Verdict.NOT_ALLOWED, RestoreRules.forSnapshot(liveRecord,
                snapshot(liveRecord, liveRecord.generation(), true), RestoreRules.Reason.RECALL));
    }

    @Test
    void aSnapshotNewerThanItsRecordOrMissingIsNotUsed() {
        CompanionRecord record = live();
        assertEquals(RestoreRules.Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(record,
                snapshot(record, record.generation() + 1, false), RestoreRules.Reason.RECALL));
        assertEquals(RestoreRules.Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(record, null, RestoreRules.Reason.RECALL));
        SnapshotEnvelope noEntity = new SnapshotEnvelope(record.profileId(), CompanionSnapshots.FORMAT,
                record.generation(), new BsonDocument("World", new BsonString("default")));
        assertEquals(RestoreRules.Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(record, noEntity, RestoreRules.Reason.RECALL));
    }
}
