package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers atomic, fail-closed refresh behavior for the managed-coop runtime index. */
class ManagedCoopResidentIndexRefreshServiceTest {
    private static final ManagedCoopAuthorityKey COOP =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @Test
    void completeSnapshotReplacesIndexAtomically() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord resident = resident("profile-a", uuid(1));
        MutableSource source = new MutableSource(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(resident))
        );
        List<String> warnings = new ArrayList<>();
        ManagedCoopResidentIndexRefreshService service =
                new ManagedCoopResidentIndexRefreshService(index, source, warnings::add);

        ManagedCoopResidentIndexRefreshService.RefreshResult result = service.refresh();

        assertTrue(result.refreshed());
        assertEquals(1L, result.revision());
        assertSame(resident, index.residentByProfile("profile-a"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void failedReadPreservesLastKnownGoodSnapshotAndReportsReason() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord original = resident("profile-a", uuid(1));
        MutableSource source = new MutableSource(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(original))
        );
        List<String> warnings = new ArrayList<>();
        ManagedCoopResidentIndexRefreshService service =
                new ManagedCoopResidentIndexRefreshService(index, source, warnings::add);
        service.refresh();

        source.residents = ManagedCoopReadResult.sqlFailure(new SQLException("database busy"));
        ManagedCoopResidentIndexRefreshService.RefreshResult rejected = service.refresh();

        assertFalse(rejected.refreshed());
        assertEquals(1L, rejected.revision());
        assertFalse(index.isTrusted());
        assertSame(original, index.residentByProfile("profile-a"));
        assertTrue(rejected.detail().startsWith("residents:sql_error:"));
        assertEquals(1, warnings.size());
    }

    @Test
    void missingTypedReadResultFailsClosedWithoutDiscardingEvidence() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord original = resident("profile-a", uuid(1));
        MutableSource source = new MutableSource(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(original))
        );
        ManagedCoopResidentIndexRefreshService service =
                new ManagedCoopResidentIndexRefreshService(index, source, ignored -> { });
        service.refresh();
        source.authorities = null;

        ManagedCoopResidentIndexRefreshService.RefreshResult rejected = service.refresh();

        assertFalse(rejected.refreshed());
        assertEquals(1L, rejected.revision());
        assertEquals("authorities:missing_read_result", rejected.detail());
        assertFalse(index.isTrusted());
        assertSame(original, index.residentByProfile("profile-a"));
    }

    private static AuthorityRecord authority() {
        return new AuthorityRecord(
                COOP.authorityId(), COOP, "coop_chicken", AuthorityState.TWORK_MANAGED,
                true, 1, -100L, -90L, null
        );
    }

    private static ResidentRecord resident(String profileId, UUID uuid) {
        return new ResidentRecord(
                "resident-" + profileId, COOP, "coop_chicken", 0, profileId, "Mob_Chicken",
                uuid, uuid, null, "{}",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1,
                ResidentState.HOUSED, 0L, true, -100L, 0L, -100L, -90L
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }

    private static final class MutableSource
            implements ManagedCoopResidentIndexRefreshService.SnapshotSource {
        private ManagedCoopReadResult<List<AuthorityRecord>> authorities;
        private ManagedCoopReadResult<List<ResidentRecord>> residents;

        private MutableSource(ManagedCoopReadResult<List<AuthorityRecord>> authorities,
                              ManagedCoopReadResult<List<ResidentRecord>> residents) {
            this.authorities = authorities;
            this.residents = residents;
        }

        @Override
        public ManagedCoopReadResult<List<AuthorityRecord>> loadAuthorities() {
            return authorities;
        }

        @Override
        public ManagedCoopReadResult<List<ResidentRecord>> loadResidents() {
            return residents;
        }
    }
}
