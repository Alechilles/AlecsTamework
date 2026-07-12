package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ActiveRecovery;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.IdentityLoadResult;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.LoadStatus;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ManagedAssignment;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ProfileFlags;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ProfileIdentity;
import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionIdentityGuard.Status.CONFLICT;
import static com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionIdentityGuard.Status.UNAVAILABLE;
import static com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionIdentityGuard.Status.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fail-closed live-alias evidence for deployed vanilla projection adoption. */
class ManagedCoopVanillaProjectionIdentityGuardTest {
    private static final String PROFILE = "profile-a";
    private static final UUID SOURCE = new UUID(0L, 101L);
    private static final UUID OTHER = new UUID(0L, 102L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 1, 2, 3);

    @Test
    void verifiesKnownSourceWhenEveryOtherDurableAliasIsAbsent() {
        LoadedNpcIdentityIndex loaded = initializedIndex();
        loaded.recordAdded(SOURCE, location("world-a", "store-a"));
        ManagedCoopVanillaProjectionIdentityGuard guard = guard(
                loaded, identity(List.of(SOURCE, OTHER), null, null, true));

        assertEquals(VERIFIED, guard.verify(PROFILE, SOURCE, AUTHORITY, 0).status());
        assertEquals(VERIFIED, guard(
                loaded, identityWithFlags(new ProfileFlags(
                        false, false, false, true, "world-a|1,2,3|0", null)))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());
    }

    @Test
    void missingAndMultipleSourceLocationsFailClosed() {
        LoadedNpcIdentityIndex missing = initializedIndex();
        ManagedCoopVanillaProjectionIdentityGuard missingGuard = guard(
                missing, identity(List.of(SOURCE), null, null, true));
        assertEquals(UNAVAILABLE,
                missingGuard.verify(PROFILE, SOURCE, AUTHORITY, 0).status());

        LoadedNpcIdentityIndex multiple = initializedIndex();
        multiple.recordAdded(SOURCE, location("world-a", "store-a"));
        multiple.recordAdded(SOURCE, location("world-a", "store-b"));
        ManagedCoopVanillaProjectionIdentityGuard multipleGuard = guard(
                multiple, identity(List.of(SOURCE), null, null, true));
        assertEquals(CONFLICT,
                multipleGuard.verify(PROFILE, SOURCE, AUTHORITY, 0).status());
    }

    @Test
    void anyOtherLiveDurableAliasIsAConflict() {
        LoadedNpcIdentityIndex loaded = initializedIndex();
        loaded.recordAdded(SOURCE, location("world-a", "store-a"));
        loaded.recordAdded(OTHER, location("world-b", "store-b"));
        ManagedCoopVanillaProjectionIdentityGuard guard = guard(
                loaded, identity(List.of(SOURCE, OTHER), null, null, true));

        ManagedCoopVanillaProjectionIdentityGuard.Result result =
                guard.verify(PROFILE, SOURCE, AUTHORITY, 0);

        assertEquals(CONFLICT, result.status());
        assertEquals("deployed_projection_other_live_alias:" + OTHER, result.detail());
    }

    @Test
    void unknownIndexActiveRecoveryAndConflictingManagedAssignmentNeverVerify() {
        LoadedNpcIdentityIndex incomplete = new LoadedNpcIdentityIndex();
        incomplete.recordAdded(SOURCE, location("world-a", "store-a"));
        assertEquals(UNAVAILABLE, guard(
                incomplete, identity(List.of(SOURCE), null, null, true))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());

        LoadedNpcIdentityIndex loaded = initializedIndex();
        loaded.recordAdded(SOURCE, location("world-a", "store-a"));
        ActiveRecovery recovery = new ActiveRecovery(
                UUID.randomUUID().toString(), RecoveryState.PROJECTION_CREATED,
                SOURCE, SOURCE, 1L);
        assertEquals(CONFLICT, guard(
                loaded, identity(List.of(SOURCE), null, recovery, true))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());

        ManagedAssignment otherCoop = new ManagedAssignment(
                "resident-a", "other-authority", 0, SOURCE, SOURCE, SOURCE,
                ResidentState.DEPLOYED, 0L);
        assertEquals(CONFLICT, guard(
                loaded, identity(List.of(SOURCE), otherCoop, null, true))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());

        assertEquals(CONFLICT, guard(
                loaded, identityWithFlags(new ProfileFlags(
                        true, false, false, false, null, null)))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());
        assertEquals(CONFLICT, guard(
                loaded, identityWithFlags(new ProfileFlags(
                        false, false, false, true, "world-a|9,9,9|0", null)))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());
    }

    @Test
    void sourceMustBeAKnownDurableAliasInTheExpectedWorld() {
        LoadedNpcIdentityIndex loaded = initializedIndex();
        loaded.recordAdded(SOURCE, location("world-b", "store-a"));
        assertEquals(CONFLICT, guard(
                loaded, identity(List.of(SOURCE), null, null, true))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());

        LoadedNpcIdentityIndex expectedWorld = initializedIndex();
        expectedWorld.recordAdded(SOURCE, location("world-a", "store-a"));
        assertEquals(CONFLICT, guard(
                expectedWorld, identity(List.of(OTHER), null, null, false))
                .verify(PROFILE, SOURCE, AUTHORITY, 0).status());
    }

    private ManagedCoopVanillaProjectionIdentityGuard guard(
            LoadedNpcIdentityIndex loaded,
            IdentityLoadResult result) {
        return new ManagedCoopVanillaProjectionIdentityGuard(
                (profileId, sourceUuid) -> result, loaded);
    }

    private IdentityLoadResult identity(List<UUID> aliases,
                                        ManagedAssignment assignment,
                                        ActiveRecovery recovery,
                                        boolean historicalKnown) {
        ProfileIdentity identity = new ProfileIdentity(
                PROFILE,
                aliases.isEmpty() ? null : aliases.getFirst(),
                aliases,
                historicalKnown,
                new ProfileFlags(false, false, false, false, null, null),
                assignment,
                recovery);
        return new IdentityLoadResult(
                LoadStatus.FOUND, identity, null, null, null, null);
    }

    private IdentityLoadResult identityWithFlags(ProfileFlags flags) {
        ProfileIdentity identity = new ProfileIdentity(
                PROFILE, SOURCE, List.of(SOURCE), true, flags, null, null);
        return new IdentityLoadResult(
                LoadStatus.FOUND, identity, null, null, null, null);
    }

    private LoadedNpcIdentityIndex initializedIndex() {
        LoadedNpcIdentityIndex loaded = new LoadedNpcIdentityIndex();
        loaded.markInitializationComplete();
        return loaded;
    }

    private LoadedNpcIdentityIndex.Location location(String world, String store) {
        return new LoadedNpcIdentityIndex.Location(world, store);
    }
}
