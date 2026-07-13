package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the stale HOUSED snapshot observed by the 2026-07-12 noon release failure. */
class ManagedCoopReleaseResidentResolverTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final UUID PLANNED = new UUID(0L, 2L);
    private static final String HASH = "a".repeat(64);

    @Test
    void resolvesReleasingResidentPublishedAfterStaleHousedSelection() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        ResidentRecord selected = resident(ResidentState.HOUSED, 0L);
        rebuild(index, selected);
        ResidentRecord releasing = resident(ResidentState.RELEASING, 1L);
        rebuild(index, releasing);

        ResidentRecord resolved = new ManagedCoopReleaseResidentResolver(
                index, () -> true).resolve(claim(index.snapshot().revision()));

        assertSame(releasing, resolved);
        assertEquals(ResidentState.HOUSED, selected.state());
        assertEquals(ResidentState.RELEASING, resolved.state());
    }

    @Test
    void rejectsIndexThatStillExposesPreClaimHousedResident() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        rebuild(index, resident(ResidentState.HOUSED, 0L));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ManagedCoopReleaseResidentResolver(index, () -> true)
                        .resolve(claim(index.snapshot().revision()))
        );

        assertTrue(failure.getMessage().contains("current_resident_mismatch"));
    }

    @Test
    void rejectsUntrustedCompositeIndex() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        rebuild(index, resident(ResidentState.RELEASING, 1L));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ManagedCoopReleaseResidentResolver(index, () -> false)
                        .resolve(claim(index.snapshot().revision()))
        );

        assertTrue(failure.getMessage().contains("index_untrusted"));
    }

    private static void rebuild(ManagedCoopResidentIndex index, ResidentRecord resident) {
        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(resident))
        ).rebuilt());
    }

    private static AuthorityRecord authority() {
        return new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop_chicken",
                AuthorityState.TWORK_MANAGED, true, 1, 1L, 1L, null
        );
    }

    private static ResidentRecord resident(ResidentState state, long generation) {
        return new ResidentRecord(
                "resident", AUTHORITY, "coop_chicken", 0, "profile", "tamed_skrill",
                SOURCE, SOURCE, null, "{}", HASH, 1, state, generation, true,
                1L, 0L, 1L, 1L
        );
    }

    private static SpawnReady claim(long indexRevision) {
        return new SpawnReady(
                "release", "profile", "resident", AUTHORITY, "coop_chicken", 0,
                SOURCE, PLANNED, null, HASH, 0L, 1L, 1L,
                OperationState.SPAWN_CLAIMED, indexRevision, true
        );
    }
}
