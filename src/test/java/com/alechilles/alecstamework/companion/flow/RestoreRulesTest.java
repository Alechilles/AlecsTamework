package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.companion.flow.RestoreRules.Reason;
import static com.alechilles.alecstamework.companion.flow.RestoreRules.Verdict;
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

    private static CompanionRecord at(CompanionLocation location) {
        return CompanionRecord.builder(UUID.randomUUID(), "r", location).build();
    }

    @Test
    void eachReasonAcceptsOnlyItsLocations() {
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.live("w", 0, 0, 0)), Reason.RECALL, 0));
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(at(CompanionLocation.item()), Reason.RECALL, 0));
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(dead(0L), Reason.RECALL, 0));
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(live(), Reason.REVIVE, 0));
        assertEquals(Verdict.NOT_FOUND, RestoreRules.forRecord(null, Reason.RECOVER, 0));
        for (CompanionLocation l : List.of(CompanionLocation.lost(null), CompanionLocation.live("w", 0, 0, 0),
                CompanionLocation.item(), CompanionLocation.coop("w", 1, 2, 3, 0))) {
            assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(l), Reason.RECOVER, 0), l.kind().name());
        }
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(at(CompanionLocation.stored(StoredReason.ROSTER)), Reason.RECOVER, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.item()), Reason.RELEASE, 0));
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(at(CompanionLocation.live("w", 0, 0, 0)), Reason.RELEASE, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.coop("w", 1, 2, 3, 0)), Reason.COOP_RELEASE, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.stored(StoredReason.ROSTER)), Reason.SUMMON, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.stored(StoredReason.TIMED)), Reason.SUMMON, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(at(CompanionLocation.stored(StoredReason.BONDED)), Reason.SUMMON, 0));
        assertEquals(Verdict.NOT_ALLOWED, RestoreRules.forRecord(at(CompanionLocation.stored(StoredReason.PROVISIONED)), Reason.SUMMON, 0));
    }

    @Test
    void onlyAProvisionedBondedCompanionWhoseSnapshotWasNeverWrittenComesBackWithNone() {
        CompanionRecord provisioned = at(CompanionLocation.stored(StoredReason.PROVISIONED)).toBuilder()
                .bonded(true).rosterId("hydragon:horn").ownerUuid(UUID.randomUUID())
                .origin("hydragon", "soul-bond-1").build();
        // A stop between the first summon's commit and its first snapshot leaves it like this.
        CompanionRecord lostBeforeItsFirstSnapshot = provisioned.toBuilder()
                .location(CompanionLocation.lost("REMOVED")).build();
        CompanionRecord captured = provisioned.toBuilder().origin(null, null)
                .location(CompanionLocation.stored(StoredReason.BONDED)).build();

        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(provisioned, Reason.SUMMON, 0));
        assertEquals(Verdict.ALLOWED, RestoreRules.forSnapshot(provisioned, null, Reason.SUMMON, true));
        assertEquals(Verdict.ALLOWED, RestoreRules.forSnapshot(lostBeforeItsFirstSnapshot, null, Reason.RECOVER, true));
        // A snapshot that may exist but was not read is not "never written".
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(provisioned, null, Reason.SUMMON, false));
        // A captured companion always had a snapshot; a revive never comes from the role.
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(captured, null, Reason.SUMMON, true));
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(provisioned, null, Reason.REVIVE, true));
    }

    @Test
    void anItemFromAnOlderGenerationIsStale() {
        CompanionRecord item = CompanionRecord.builder(UUID.randomUUID(), "r", CompanionLocation.item()).generation(4).build();
        assertEquals(Verdict.STALE, RestoreRules.forRecord(item, Reason.RELEASE, 0, 3));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(item, Reason.RELEASE, 0, 4));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(item, Reason.RELEASE, 0, -1));
    }

    @Test
    void summonWaitsForItsCooldown() {
        CompanionRecord stored = CompanionRecord.builder(UUID.randomUUID(), "r", CompanionLocation.stored(StoredReason.ROSTER))
                .summonCooldownUntilMs(1_000).build();
        assertEquals(Verdict.COOLDOWN, RestoreRules.forRecord(stored, Reason.SUMMON, 999));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(stored, Reason.SUMMON, 1_000));
        CompanionRecord bonded = stored.toBuilder().location(CompanionLocation.stored(StoredReason.BONDED)).build();
        assertEquals(Verdict.COOLDOWN, RestoreRules.forRecord(bonded, Reason.SUMMON, 999));
        assertEquals(Verdict.ALLOWED, RestoreRules.forRecord(bonded, Reason.SUMMON, 1_000));
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

    private static final String IMPORTED_STATE = "{\"version\":\"1\",\"npcUuid\":\"" + UUID.randomUUID()
            + "\",\"roleId\":\"Tamed_Sheep\",\"currentHealth\":0.0,\"maximumHealth\":20.0,\"healthPercent\":0.0}";

    @Test
    void anImportedStateSnapshotServesARecoverAReviveAndASummon() {
        CompanionRecord lost = at(CompanionLocation.lost("IMPORTED_UNRESOLVED"));
        CompanionRecord dead = dead(0L);
        CompanionRecord stored = at(CompanionLocation.stored(StoredReason.BONDED));

        assertEquals(Verdict.ALLOWED, RestoreRules.forSnapshot(lost,
                SnapshotEnvelope.importedState(lost.profileId(), 0L, IMPORTED_STATE), Reason.RECOVER));
        assertEquals(Verdict.ALLOWED, RestoreRules.forSnapshot(dead,
                SnapshotEnvelope.importedState(dead.profileId(), 0L, IMPORTED_STATE), Reason.REVIVE));
        assertEquals(Verdict.ALLOWED, RestoreRules.forSnapshot(stored,
                SnapshotEnvelope.importedState(stored.profileId(), 0L, IMPORTED_STATE), Reason.SUMMON));
    }

    @Test
    void anImportedStateSnapshotThatCannotBeReadIsNotUsed() {
        CompanionRecord stored = at(CompanionLocation.stored(StoredReason.ROSTER));
        UUID id = stored.profileId();

        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(stored,
                SnapshotEnvelope.importedState(id, 0L, "{not json"), Reason.SUMMON));
        // A 4.x death wrapper the importer did not unwrap has no source NPC UUID at its root.
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(stored,
                SnapshotEnvelope.importedState(id, 0L, "{\"fullState\":" + IMPORTED_STATE + "}"), Reason.SUMMON));
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(stored,
                new SnapshotEnvelope(id, SnapshotEnvelope.FORMAT_IMPORTED_STATE, 0L, new BsonDocument()), Reason.SUMMON));
        assertEquals(Verdict.NO_SNAPSHOT, RestoreRules.forSnapshot(stored,
                SnapshotEnvelope.importedState(id, stored.generation() + 1, IMPORTED_STATE), Reason.SUMMON));
    }

    @Test
    void importedHealthIsNeverAppliedAtZeroOrOnARevive() {
        assertEquals(true, RestoreRules.appliesImportedHealth(Reason.SUMMON, 12.0, 60.0));
        assertEquals(true, RestoreRules.appliesImportedHealth(Reason.RECOVER, null, 60.0));
        assertEquals(false, RestoreRules.appliesImportedHealth(Reason.RECOVER, 0.0, 0.0));
        assertEquals(false, RestoreRules.appliesImportedHealth(Reason.SUMMON, null, null));
        assertEquals(false, RestoreRules.appliesImportedHealth(Reason.REVIVE, 12.0, 60.0));
    }
}
