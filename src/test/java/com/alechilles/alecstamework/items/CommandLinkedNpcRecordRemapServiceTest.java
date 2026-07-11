package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.CommandLinkedNpcRecordRemapService.RemapStatus.CONFLICT;
import static com.alechilles.alecstamework.items.CommandLinkedNpcRecordRemapService.RemapStatus.NO_MATCH;
import static com.alechilles.alecstamework.items.CommandLinkedNpcRecordRemapService.RemapStatus.REMAPPED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for profile-first command-record projection remaps. */
class CommandLinkedNpcRecordRemapServiceTest {
    @Test
    void profileAwareRemapChangesOnlyRequestedProfileWhenStaleUuidIsShared() {
        UUID staleUuid = uuid(1);
        UUID currentUuid = uuid(2);
        LinkedNpcRecord profileA = record(staleUuid, "profile-a", "Profile A");
        LinkedNpcRecord profileB = record(staleUuid, "profile-b", "Profile B");

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        List.of(profileA, profileB),
                        "profile-a",
                        staleUuid,
                        currentUuid
                );

        assertEquals(REMAPPED, result.status());
        assertEquals(currentUuid, findProfile(result.records(), "profile-a").npcUuid);
        assertEquals(staleUuid, findProfile(result.records(), "profile-b").npcUuid);
        assertEquals("Profile B", findProfile(result.records(), "profile-b").cachedDisplayName);
    }

    @Test
    void profileAwareRemapFailsClosedWhenCurrentUuidBelongsToDifferentProfile() {
        UUID staleUuid = uuid(1);
        UUID currentUuid = uuid(2);
        List<LinkedNpcRecord> source = List.of(
                record(staleUuid, "profile-a", "Profile A"),
                record(currentUuid, "profile-b", "Profile B")
        );

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        source,
                        "profile-a",
                        staleUuid,
                        currentUuid
                );

        assertEquals(CONFLICT, result.status());
        assertSame(source, result.records());
        assertSame(source.get(0), result.records().get(0));
        assertSame(source.get(1), result.records().get(1));
    }

    @Test
    void resolvedAndUnresolvedEvidenceAdoptsLegacyRecordThenDeduplicatesByProfile() {
        UUID staleUuid = uuid(1);
        UUID currentUuid = uuid(2);
        LinkedNpcRecord unresolved = record(staleUuid, null, "Legacy Cache");
        LinkedNpcRecord resolved = record(staleUuid, "profile-a", "Resolved Cache");

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        List.of(unresolved, resolved),
                        "profile-a",
                        staleUuid,
                        currentUuid
                );

        assertEquals(REMAPPED, result.status());
        assertEquals(1, result.records().size());
        assertEquals("profile-a", result.records().getFirst().profileId);
        assertEquals(currentUuid, result.records().getFirst().npcUuid);
        assertEquals("Resolved Cache", result.records().getFirst().cachedDisplayName);
    }

    @Test
    void uuidOnlyRemapReportsConflictWhenTwoProfilesShareHistoricalUuid() {
        UUID staleUuid = uuid(1);
        List<LinkedNpcRecord> source = List.of(
                record(staleUuid, "profile-a", "Profile A"),
                record(staleUuid, "profile-b", "Profile B")
        );

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(source, staleUuid, uuid(2));

        assertEquals(CONFLICT, result.status());
        assertSame(source, result.records());
    }

    @Test
    void profileAwareNoMatchLeavesOriginalListUnchanged() {
        List<LinkedNpcRecord> source = List.of(record(uuid(9), "profile-b", "Profile B"));

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        source,
                        "profile-a",
                        uuid(1),
                        uuid(2)
                );

        assertEquals(NO_MATCH, result.status());
        assertSame(source, result.records());
        assertSame(source.getFirst(), result.records().getFirst());
    }

    @Test
    void remapPreservesEveryCachedField() {
        UUID currentUuid = uuid(2);
        LinkedNpcRecord original = detailedRecord(uuid(1), "profile-a");

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        List.of(original),
                        "profile-a",
                        original.npcUuid,
                        currentUuid
                );

        LinkedNpcRecord remapped = result.records().getFirst();
        assertEquals(REMAPPED, result.status());
        assertEquals(currentUuid, remapped.npcUuid);
        assertEquals("profile-a", remapped.profileId);
        assertEquals(new Vector3d(1, 2, 3), remapped.lastKnownPosition);
        assertEquals("world-a", remapped.lastKnownWorldName);
        assertEquals(new Vector3d(4, 5, 6), remapped.homePosition);
        assertEquals("Display Name", remapped.cachedDisplayName);
        assertEquals("name.key", remapped.cachedNameKey);
        assertEquals("Mob_Test", remapped.cachedRoleId);
        assertEquals("Follow", remapped.cachedCommandState);
        assertFalse(remapped.active);
        assertTrue(remapped.breedingEnabled);
        assertEquals("group-a", remapped.groupId);
    }

    @Test
    void uuidOnlyRemapRetainsBackwardCompatibleUniqueMatch() {
        UUID staleUuid = uuid(1);
        UUID currentUuid = uuid(2);

        CommandLinkedNpcRecordRemapService.RemapResult result =
                CommandLinkedNpcRecordRemapService.remapLinkedNpcRecords(
                        List.of(record(staleUuid, null, "Legacy")),
                        staleUuid,
                        currentUuid
                );

        assertEquals(REMAPPED, result.status());
        assertEquals(currentUuid, result.records().getFirst().npcUuid);
        assertEquals("Legacy", result.records().getFirst().cachedDisplayName);
    }

    private static LinkedNpcRecord findProfile(List<LinkedNpcRecord> records, String profileId) {
        for (LinkedNpcRecord record : records) {
            if (profileId.equals(record.profileId)) {
                return record;
            }
        }
        throw new AssertionError("Missing profile " + profileId);
    }

    private static LinkedNpcRecord record(UUID npcUuid, String profileId, String displayName) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                null,
                null,
                null,
                displayName,
                null,
                "Mob_Test",
                null,
                true,
                false,
                null
        );
    }

    private static LinkedNpcRecord detailedRecord(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                new Vector3d(1, 2, 3),
                "world-a",
                new Vector3d(4, 5, 6),
                "Display Name",
                "name.key",
                "Mob_Test",
                "Follow",
                false,
                true,
                "group-a"
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
