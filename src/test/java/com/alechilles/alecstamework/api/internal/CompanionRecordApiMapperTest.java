package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRecordApiMapperTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BODY = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String TOOL = "00000000-0000-0000-0000-0000000000c0";

    private static CompanionRecord.Builder record(CompanionLocation location) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", location)
                .ownerUuid(OWNER).ownerName("Alice").displayName("Sheep").updatedAtMs(5_000L);
    }

    private static CompanionSummary named(String customName) {
        return new CompanionSummary(customName, null, null, null,
                0f, 0f, null, 0.0, null, 0.0, 0.0, false, false, 0L, 0L, 0L, 0L,
                null, 0, 0.0, 0.0, 0, Map.of(), 0L, 0L, 0L, null, null, null);
    }

    @Test
    void liveCompanionMapsItsIdentityBodyToolsAndPosition() {
        CompanionRecord record = record(CompanionLocation.live("default", 10.5, 64, -3))
                .currentNpcUuid(BODY).summary(named("Wooly")).toolIds(List.of(TOOL)).build();

        NpcProfileView view = CompanionRecordApiMapper.toProfileView(record);

        assertEquals(record.profileId().toString(), view.profileId());
        assertEquals(BODY, view.currentNpcUuid());
        assertEquals(OWNER, view.ownerUuid());
        assertEquals("Alice", view.ownerName());
        assertEquals("Sheep", view.roleId());
        assertEquals("Sheep", view.displayName());
        assertEquals("Wooly", view.customName());
        assertTrue(view.tamed());
        assertNull(view.coopId());
        assertNull(view.coopSlot());
        assertEquals(Set.of(TOOL), view.toolIds());
        assertTrue(view.activeSnapshotTypes().isEmpty());
        assertEquals(5_000L, view.lastUpdatedAtMs());
        assertNull(CompanionRecordApiMapper.snapshotJson(record));
        assertEquals(new Vector3View(10.5, 64, -3), CompanionRecordApiMapper.lastKnownPosition(record));
    }

    @Test
    void coopResidentReportsItsCoopBlockAndSlot() {
        CompanionRecord record = record(CompanionLocation.coop("default", 4, 70, -9, 2)).build();

        NpcProfileView view = CompanionRecordApiMapper.toProfileView(record);

        assertEquals("default:4:70:-9", view.coopId());
        assertEquals(2, view.coopSlot());
        assertTrue(view.activeSnapshotTypes().isEmpty());
        assertEquals(new Vector3View(4, 70, -9), CompanionRecordApiMapper.lastKnownPosition(record));
    }

    @Test
    void capturedCompanionHasACaptureSnapshotAndNoPosition() {
        CompanionRecord record = record(CompanionLocation.item()).summary(named("Wooly")).build();

        assertEquals(Set.of("capture"), CompanionRecordApiMapper.toProfileView(record).activeSnapshotTypes());
        JsonObject json = JsonParser.parseString(CompanionRecordApiMapper.snapshotJson(record)).getAsJsonObject();
        assertEquals("capture", json.get("snapshotType").getAsString());
        assertEquals(record.profileId().toString(), json.get("profileId").getAsString());
        assertEquals("Sheep", json.get("roleId").getAsString());
        assertEquals(OWNER.toString(), json.get("ownerUuid").getAsString());
        assertEquals("Wooly", json.get("customName").getAsString());
        assertNull(CompanionRecordApiMapper.lastKnownPosition(record));
    }

    @Test
    void deadCompanionHasADeathSnapshotWithItsCauseAndSignedTimes() {
        CompanionRecord record = record(CompanionLocation.dead("combat"))
                .diedAtMs(-2_000L).reviveAvailableAtMs(-500L).build();

        assertEquals(Set.of("death"), CompanionRecordApiMapper.toProfileView(record).activeSnapshotTypes());
        JsonObject json = JsonParser.parseString(CompanionRecordApiMapper.snapshotJson(record)).getAsJsonObject();
        assertEquals("death", json.get("snapshotType").getAsString());
        assertEquals("combat", json.get("cause").getAsString());
        assertEquals(-2_000L, json.get("diedAtMs").getAsLong());
        assertEquals(-500L, json.get("reviveAvailableAtMs").getAsLong());
    }

    @Test
    void lostCompanionHasALostSnapshot() {
        CompanionRecord record = record(CompanionLocation.lost("body_missing")).build();

        assertEquals(Set.of("lost"), CompanionRecordApiMapper.toProfileView(record).activeSnapshotTypes());
        JsonObject json = JsonParser.parseString(CompanionRecordApiMapper.snapshotJson(record)).getAsJsonObject();
        assertEquals("lost", json.get("snapshotType").getAsString());
        assertEquals("body_missing", json.get("cause").getAsString());
        assertFalse(json.has("diedAtMs"));
    }

    @Test
    void storedRosterMemberIsAnOwnedProfileWithNoBodyAndNoSnapshot() {
        CompanionRecord record = record(CompanionLocation.stored(StoredReason.ROSTER))
                .rosterId("primary").rosterSlot(1).toolIds(List.of(TOOL)).build();

        NpcProfileView view = CompanionRecordApiMapper.toProfileView(record);

        assertTrue(view.tamed());
        assertNull(view.currentNpcUuid());
        assertEquals(Set.of(TOOL), view.toolIds());
        assertTrue(view.activeSnapshotTypes().isEmpty());
        assertNull(CompanionRecordApiMapper.lastKnownPosition(record));
    }
}
