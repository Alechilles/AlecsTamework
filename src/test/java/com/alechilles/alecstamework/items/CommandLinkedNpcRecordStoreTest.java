package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for backward-compatible command-record identity and storage semantics.
 */
class CommandLinkedNpcRecordStoreTest {
    private final LinkedNpcRecordCodec codec = new LinkedNpcRecordCodec();
    private final LinkedNpcRecordCollection records = new LinkedNpcRecordCollection();
    private final CommandLinkedNpcRecordStore store = new CommandLinkedNpcRecordStore();

    @Test
    void codecReadsLegacyRecordWithoutProfileId() {
        UUID npcUuid = UUID.randomUUID();
        String encodedWorld = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("adventure_world".getBytes(StandardCharsets.UTF_8));

        LinkedNpcRecord record = codec.parse(
                npcUuid + "|12.5|64.0|-3.25|1.0|2.0|3.0|lw=" + encodedWorld + "|rid=Y2F0X3BldA"
        );

        assertEquals(npcUuid, record.npcUuid);
        assertNull(record.profileId);
        assertEquals("adventure_world", record.lastKnownWorldName);
        assertEquals(12.5, record.lastKnownPosition.x);
        assertEquals(64.0, record.lastKnownPosition.y);
        assertEquals(-3.25, record.lastKnownPosition.z);
    }

    @Test
    void codecRoundTripsProfileTokenAndNormalizesBlankProfile() {
        UUID npcUuid = UUID.randomUUID();
        LinkedNpcRecord profiled = record(npcUuid, "  profile-a  ");

        String encoded = codec.encode(profiled);
        LinkedNpcRecord decoded = codec.parse(encoded);
        LinkedNpcRecord blank = codec.parse(npcUuid + "|pid=   ");

        assertTrue(encoded.contains("|pid=profile-a"));
        assertEquals("profile-a", decoded.profileId);
        assertEquals(npcUuid, decoded.npcUuid);
        assertNull(blank.profileId);
        assertFalse(codec.encode(record(npcUuid, null)).contains("|pid="));
    }

    @Test
    void codecExtractionPreservesExistingOptionalFields() {
        LinkedNpcRecord original = new LinkedNpcRecord(
                UUID.randomUUID(), "profile-a", new Vector3d(1, 2, 3), "world-a",
                new Vector3d(4, 5, 6), "Display", "name.key", "Mob_Test",
                "Follow", false, true, "group-a"
        );

        LinkedNpcRecord decoded = codec.parse(codec.encode(original));

        assertEquals(original.npcUuid, decoded.npcUuid);
        assertEquals("profile-a", decoded.profileId);
        assertEquals(new Vector3d(1, 2, 3), decoded.lastKnownPosition);
        assertEquals(new Vector3d(4, 5, 6), decoded.homePosition);
        assertEquals("world-a", decoded.lastKnownWorldName);
        assertEquals("Display", decoded.cachedDisplayName);
        assertEquals("name.key", decoded.cachedNameKey);
        assertEquals("Mob_Test", decoded.cachedRoleId);
        assertEquals("Follow", decoded.cachedCommandState);
        assertFalse(decoded.active);
        assertTrue(decoded.breedingEnabled);
        assertEquals("group-a", decoded.groupId);
    }

    @Test
    void writeDeduplicatesProfilesAndUnresolvedUuidsIndependently() {
        UUID sharedUuid = UUID.randomUUID();
        UUID replacementUuid = UUID.randomUUID();

        List<LinkedNpcRecord> deduplicated = records.deduplicate(List.of(
                record(sharedUuid, "profile-a"),
                record(replacementUuid, "profile-a"),
                record(sharedUuid, null),
                record(sharedUuid, null),
                record(sharedUuid, "profile-b")
        ));

        assertEquals(3, deduplicated.size());
        assertEquals(sharedUuid, store.find(deduplicated, "profile-a", null).npcUuid);
        assertEquals(sharedUuid, store.find(deduplicated, "profile-b", null).npcUuid);
        assertEquals(sharedUuid, store.find(deduplicated, null, sharedUuid).npcUuid);
    }

    @Test
    void profileAwareUpsertAttachesProfileToUniqueUnresolvedLegacyRecord() {
        UUID npcUuid = UUID.randomUUID();

        List<LinkedNpcRecord> updated = records.upsert(
                List.of(record(npcUuid, null)), "profile-a", npcUuid, null, null, null,
                "Profiled", null, "Mob_Test", null, null
        );

        assertEquals(1, updated.size());
        assertEquals(npcUuid, store.find(updated, "profile-a", null).npcUuid);
        assertEquals("profile-a", updated.getFirst().profileId);
    }

    @Test
    void profileAwareUpsertNeverAdoptsRecordAssignedToAnotherProfile() {
        UUID npcUuid = UUID.randomUUID();

        List<LinkedNpcRecord> updated = records.upsert(
                List.of(record(npcUuid, "profile-b")), "profile-a", npcUuid, null, null, null,
                "Profile A", null, "Mob_Test", null, null
        );

        assertEquals(2, updated.size());
        assertEquals(npcUuid, store.find(updated, "profile-a", null).npcUuid);
        assertEquals(npcUuid, store.find(updated, "profile-b", null).npcUuid);
    }

    @Test
    void uuidOnlyUpsertDoesNotGuessBetweenMultipleAssignedProfiles() {
        UUID npcUuid = UUID.randomUUID();
        List<LinkedNpcRecord> source = List.of(
                record(npcUuid, "profile-a"),
                record(npcUuid, "profile-b")
        );

        List<LinkedNpcRecord> updated = records.upsert(
                source, null, npcUuid, null, null, null,
                "Unresolved", null, "Mob_Test", null, null
        );

        assertEquals(3, updated.size());
        assertEquals(npcUuid, store.find(updated, null, npcUuid).npcUuid);
        assertEquals(npcUuid, store.find(updated, "profile-a", null).npcUuid);
        assertEquals(npcUuid, store.find(updated, "profile-b", null).npcUuid);
    }

    @Test
    void profileAwareUpsertRemapsCachedUuidForSameProfile() {
        UUID historicalUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();

        List<LinkedNpcRecord> updated = records.upsert(
                List.of(record(historicalUuid, "profile-a")), "profile-a", currentUuid, null, null, null,
                null, null, null, null, null
        );

        assertEquals(1, updated.size());
        assertEquals(currentUuid, updated.getFirst().npcUuid);
        assertEquals("profile-a", updated.getFirst().profileId);
    }

    @Test
    void profileAwareRemoveClearsUnresolvedFallbackButLeavesOtherProfileIntact() {
        UUID sharedUuid = UUID.randomUUID();
        List<LinkedNpcRecord> source = List.of(
                record(sharedUuid, null),
                record(sharedUuid, "profile-a"),
                record(sharedUuid, "profile-b")
        );

        List<LinkedNpcRecord> updated = records.removeByIdentity(source, "profile-a", sharedUuid);

        assertEquals(1, updated.size());
        assertNull(store.find(updated, "profile-a", sharedUuid));
        assertNull(store.find(updated, null, sharedUuid));
        assertEquals(sharedUuid, store.find(updated, "profile-b", sharedUuid).npcUuid);
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid, profileId, null, null, null,
                "Test NPC", null, "Mob_Test", null,
                true, false, null
        );
    }

}
