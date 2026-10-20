package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Entry;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Kind;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate.Outcome;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate.SavedEntity;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBodyLocateTest {
    private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OLD_NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final CompanionIndex index = new CompanionIndex(() -> 9_000L, (before, after) -> { });
    private final List<SnapshotEnvelope> queued = new ArrayList<>();
    private final Set<UUID> bodies = new HashSet<>();

    private LegacyBodyLocate locate(Map<UUID, Entry> aliases) {
        return new LegacyBodyLocate(index, new LegacyAliases(aliases), bodies::contains, queued::add, () -> 7_000L);
    }

    /** A record as the importer leaves a body it has no position for: LIVE at 0,0,0 in a guessed world. */
    private void neverSeen() {
        index.insert(CompanionRecord.builder(PROFILE, "tamed_wolf", CompanionLocation.live("default", 0, 0, 0))
                .currentNpcUuid(NPC).homeWorld("default").build());
    }

    static SavedEntity saved(UUID npc, String world, double x, double y, double z) {
        return new SavedEntity(npc, world, x, y, z, () -> BsonDocument.parse("{\"Components\": {"
                + "\"NPC\": {\"RoleName\": \"Tamed_Wolf\"},"
                + " \"TameworkNpcName\": {\"Name\": \"Rex\"},"
                + " \"TameworkLeveling\": {\"ConfigId\": \"Leveling_A\", \"Level\": 7}}}"));
    }

    @Test
    void aSavedBodyGivesItsNeverSeenRecordItsPlaceSummaryAndAFullSnapshot() {
        neverSeen();

        Outcome outcome = locate(Map.of(NPC, new Entry(PROFILE, Kind.CURRENT)))
                .apply(saved(NPC, "orbis", 120.5, 64.0, -33.25));

        assertEquals(Outcome.LOCATED, outcome);
        CompanionRecord record = index.get(PROFILE);
        assertEquals(LocationKind.LIVE, record.location().kind());
        assertEquals("orbis", record.location().world());
        assertEquals(120.5, record.location().x());
        assertEquals(64.0, record.location().y());
        assertEquals(-33.25, record.location().z());
        assertEquals("orbis", record.homeWorld());
        assertEquals(0L, record.generation());
        assertEquals(NPC, record.currentNpcUuid());
        assertEquals("Tamed_Wolf", record.roleId());
        assertEquals("Rex", record.summary().customName());
        assertEquals(7, record.summary().level());
        assertEquals(7_000L, record.lastSnapshotAtMs());
        assertEquals(1, queued.size());
        SnapshotEnvelope snapshot = queued.get(0);
        assertEquals(PROFILE, snapshot.profileId());
        assertEquals(CompanionSnapshots.FORMAT, snapshot.format());
        assertEquals(0L, snapshot.generation());
        assertEquals("orbis", CompanionSnapshots.world(snapshot));
        assertEquals("Tamed_Wolf", CompanionSnapshots.entity(snapshot).getDocument("Components")
                .getDocument("NPC").getString("RoleName").getValue());
    }

    @Test
    void aRecordThatWasSeenOrRecalledOrHasARegisteredBodyIsNotTouched() {
        index.insert(CompanionRecord.builder(PROFILE, "Tamed_Wolf", CompanionLocation.live("default", 5, 70, 5))
                .currentNpcUuid(NPC).build());
        LegacyBodyLocate locate = locate(Map.of(NPC, new Entry(PROFILE, Kind.CURRENT)));
        CompanionRecord seen = index.get(PROFILE);

        assertEquals(Outcome.IGNORED, locate.apply(saved(NPC, "orbis", 1, 2, 3)));
        assertEquals(seen, index.get(PROFILE));

        // Recalled since the import: the record is a generation ahead of the saved body.
        index.update(PROFILE, seen.revision(), b -> b.generation(1)
                .location(CompanionLocation.live("default", 0, 0, 0)));
        CompanionRecord recalled = index.get(PROFILE);
        assertEquals(Outcome.IGNORED, locate.apply(saved(NPC, "orbis", 1, 2, 3)));
        assertEquals(recalled, index.get(PROFILE));

        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        UUID otherNpc = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        index.insert(CompanionRecord.builder(other, "Tamed_Wolf", CompanionLocation.live("default", 0, 0, 0))
                .currentNpcUuid(otherNpc).build());
        bodies.add(other);
        CompanionRecord loaded = index.get(other);
        LegacyBodyLocate withBody = locate(Map.of(otherNpc, new Entry(other, Kind.CURRENT)));
        assertEquals(Outcome.IGNORED, withBody.apply(saved(otherNpc, "orbis", 1, 2, 3)));
        assertEquals(loaded, index.get(other));
        assertTrue(withBody.markNotFound().isEmpty(), "a record with a registered body is never marked lost");
        assertTrue(queued.isEmpty());
    }

    @Test
    void aLeftoverBodyIsOnlyCounted() {
        neverSeen();
        CompanionRecord before = index.get(PROFILE);

        Outcome outcome = locate(Map.of(NPC, new Entry(PROFILE, Kind.CURRENT), OLD_NPC, new Entry(PROFILE, Kind.STALE)))
                .apply(saved(OLD_NPC, "orbis", 1, 2, 3));

        assertEquals(Outcome.STALE, outcome);
        assertEquals(before, index.get(PROFILE));
        assertTrue(queued.isEmpty());
    }

    @Test
    void aRecordWithNoBodyOnDiskBecomesLostAndItsBodyStillRejoinsIt() {
        neverSeen();
        Entry alias = new Entry(PROFILE, Kind.CURRENT);
        LegacyBodyLocate locate = locate(Map.of(NPC, alias));

        assertEquals(List.of(PROFILE), locate.markNotFound());

        CompanionRecord lost = index.get(PROFILE);
        assertEquals(LocationKind.LOST, lost.location().kind());
        assertEquals(LegacyBodyResolution.CAUSE_BODY_NOT_FOUND, lost.location().cause());
        assertEquals(0L, lost.generation());
        assertNull(lost.currentNpcUuid());
        assertEquals(0, locate.remaining());
        assertTrue(queued.isEmpty(), "its snapshot is kept as it was");

        // Its body turns up later, in a world that was not read.
        LegacyBodyResolution.Decision decision = LegacyBodyResolution.decide(
                new LegacyBodyResolution.Body(true, false, null, NPC), alias, lost, false, false, false);
        assertEquals(LegacyBodyResolution.Action.REJOIN, decision.action());
        assertEquals(0L, decision.generation());

        // And a saved copy of it found by a later pass fills the record again.
        assertEquals(Outcome.LOCATED, locate.apply(saved(NPC, "nether", 4, 5, 6)));
        assertEquals(LocationKind.LIVE, index.get(PROFILE).location().kind());
        assertEquals(NPC, index.get(PROFILE).currentNpcUuid());
    }

    @Test
    void aRecoveredLostRecordIsNotRejoinedByItsOldBody() {
        neverSeen();
        Entry alias = new Entry(PROFILE, Kind.CURRENT);
        locate(Map.of(NPC, alias)).markNotFound();
        CompanionRecord lost = index.get(PROFILE);
        index.update(PROFILE, lost.revision(), b -> b.generation(1)
                .location(CompanionLocation.live("default", 9, 9, 9)).currentNpcUuid(OLD_NPC));

        LegacyBodyResolution.Decision decision = LegacyBodyResolution.decide(
                new LegacyBodyResolution.Body(true, false, null, NPC), alias, index.get(PROFILE), false, false, false);

        assertEquals(LegacyBodyResolution.Action.REMOVE_STALE, decision.action());
    }
}
