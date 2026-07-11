package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime contract for atomic replacement of the tick-safe managed-coop occupancy view. */
class ManagedCoopResidentIndexTest {
    private static final ManagedCoopAuthorityKey COOP_A =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final ManagedCoopAuthorityKey COOP_B =
            new ManagedCoopAuthorityKey("world", 4, 5, 6);

    @Test
    void rebuildCreatesImmutableAliasAwareLookups() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ArrayList<AuthorityRecord> authorities = new ArrayList<>(List.of(authority(COOP_A, "coop_a")));
        ResidentRecord resident = resident("resident-a", COOP_A, "coop_a", 2, "profile-a",
                uuid(1), uuid(2), uuid(3));
        ArrayList<ResidentRecord> residents = new ArrayList<>(List.of(resident));

        ManagedCoopResidentIndex.RebuildResult result = index.rebuild(
                ManagedCoopReadResult.loaded(authorities),
                ManagedCoopReadResult.loaded(residents)
        );
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        authorities.clear();
        residents.clear();

        assertTrue(result.rebuilt());
        assertEquals(1L, snapshot.revision());
        assertEquals("coop_a", snapshot.authority(COOP_A, "COOP_A").coopId());
        assertSame(resident, snapshot.residentAt(COOP_A, 2));
        assertSame(resident, snapshot.residentByProfile("profile-a"));
        assertSame(resident, snapshot.residentByUuid(uuid(1)));
        assertSame(resident, snapshot.residentByUuid(uuid(2)));
        assertSame(resident, snapshot.residentByUuid(uuid(3)));
        assertEquals(List.of(resident), snapshot.residents(COOP_A));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.authorities().add(authority(COOP_B, "coop_b")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.allResidents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.residents(COOP_A).clear());
    }

    @Test
    void failedOrInvalidRebuildPreservesPreviousSnapshot() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord first = resident("resident-a", COOP_A, "coop_a", 0, "profile-a",
                uuid(1), uuid(1), null);
        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority(COOP_A, "coop_a"))),
                ManagedCoopReadResult.loaded(List.of(first))
        ).rebuilt());
        ManagedCoopResidentIndex.Snapshot original = index.snapshot();

        ManagedCoopResidentIndex.RebuildResult failedRead = index.rebuild(
                ManagedCoopReadResult.integrityFailure(new IllegalStateException("corrupt_authorities")),
                ManagedCoopReadResult.loaded(List.of())
        );
        ResidentRecord conflicting = resident("resident-b", COOP_A, "coop_a", 1, "profile-b",
                uuid(1), uuid(4), null);
        ManagedCoopResidentIndex.RebuildResult invalidCandidate = index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority(COOP_A, "coop_a"))),
                ManagedCoopReadResult.loaded(List.of(first, conflicting))
        );

        assertFalse(failedRead.rebuilt());
        assertFalse(invalidCandidate.rebuilt());
        assertSame(original, index.snapshot());
        assertSame(first, index.residentByProfile("profile-a"));
        assertNull(index.residentByProfile("profile-b"));
    }

    @Test
    void successfulReplacementDropsEveryMappingFromPreviousSnapshot() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord first = resident("resident-a", COOP_A, "coop_a", 0, "profile-a",
                uuid(1), uuid(2), null);
        ResidentRecord second = resident("resident-b", COOP_B, "coop_b", 3, "profile-b",
                uuid(5), null, uuid(6));
        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority(COOP_A, "coop_a"))),
                ManagedCoopReadResult.loaded(List.of(first))
        ).rebuilt());
        ManagedCoopResidentIndex.Snapshot original = index.snapshot();

        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority(COOP_B, "coop_b"))),
                ManagedCoopReadResult.loaded(List.of(second))
        ).rebuilt());
        ManagedCoopResidentIndex.Snapshot replacement = index.snapshot();

        assertTrue(replacement.revision() > original.revision());
        assertNull(index.authority(COOP_A, "coop_a"));
        assertNull(index.residentByProfile("profile-a"));
        assertNull(index.residentByUuid(uuid(2)));
        assertSame(second, index.residentAt(COOP_B, 3));
        assertSame(second, index.residentByUuid(uuid(6)));
    }

    private static AuthorityRecord authority(ManagedCoopAuthorityKey key, String coopId) {
        return new AuthorityRecord(
                key.authorityId(), key, coopId, AuthorityState.TWORK_MANAGED,
                true, 1, -100L, -90L, null
        );
    }

    private static ResidentRecord resident(String residentId,
                                           ManagedCoopAuthorityKey key,
                                           String coopId,
                                           int slot,
                                           String profileId,
                                           UUID residentUuid,
                                           UUID sourceUuid,
                                           UUID deployedUuid) {
        return new ResidentRecord(
                residentId, key, coopId, slot, profileId, "Mob_Chicken",
                residentUuid, sourceUuid, deployedUuid,
                "{}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1,
                ResidentState.HOUSED, 0L, true, -100L, 0L, -100L, -90L
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }
}
