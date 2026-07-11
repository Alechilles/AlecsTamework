package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for profile-aware relocation and terminal lost-transition routing. */
class CommandNpcProfileActionResolverTest {
    private static final LoadedNpcIdentityIndex.Location LOCATION_A =
            new LoadedNpcIdentityIndex.Location("world-a", "store-a");
    private static final LoadedNpcIdentityIndex.Location LOCATION_B =
            new LoadedNpcIdentityIndex.Location("world-b", "store-b");

    @Test
    void staleHistoricalUuidRedirectsToSoleLiveCurrentUuidAndKeepsProfile() {
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", currentUuid, List.of(currentUuid, staleUuid)),
                probe -> probe.equals(currentUuid)
                        ? oneLocation(probe, LOCATION_B)
                        : absent(probe)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(record(staleUuid, null));

        assertEquals(CommandNpcProfileActionResolver.ResolutionStatus.RESOLVED, target.status());
        assertEquals("profile-a", target.profileId());
        assertEquals(currentUuid, target.targetNpcUuid());
        assertTrue(target.redirected());
        assertEquals(currentUuid, target.resolvedRecord().npcUuid);
        assertEquals("profile-a", target.resolvedRecord().profileId);

        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                resolver.canonicalizeRecords(List.of(record(staleUuid, null)));
        assertTrue(canonical.safeToPersist());
        assertTrue(canonical.identityChanged());
        assertEquals(currentUuid, canonical.records().get(0).npcUuid);
        assertEquals("profile-a", canonical.records().get(0).profileId);
    }

    @Test
    void twoLiveAliasesFailClosedWithoutSelectingOrRewritingEitherUuid() {
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", currentUuid, List.of(currentUuid, staleUuid)),
                probe -> probe.equals(currentUuid)
                        ? oneLocation(probe, LOCATION_A)
                        : oneLocation(probe, LOCATION_B)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(record(staleUuid, "profile-a"));

        assertEquals(CommandNpcProfileActionResolver.ResolutionStatus.CONFLICT, target.status());
        assertEquals("multiple_live_profile_aliases", target.reason());
        assertFalse(target.isActionable());
        assertNull(target.targetNpcUuid());
        assertNull(target.resolvedRecord());
        assertFalse(resolver.canonicalizeRecords(
                List.of(record(staleUuid, "profile-a"))).safeToPersist());
    }

    @Test
    void incompleteLoadedIndexFailsClosedWithoutSelectingOrRewritingAUuid() {
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", currentUuid, List.of(currentUuid, staleUuid)),
                probe -> probe.equals(currentUuid)
                        ? oneLocation(probe, LOCATION_A)
                        : unknown(probe)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(record(staleUuid, "profile-a"));

        assertEquals(CommandNpcProfileActionResolver.ResolutionStatus.FAILED, target.status());
        assertEquals("loaded_identity_index_incomplete", target.reason());
        assertFalse(target.isActionable());
        assertNull(target.targetNpcUuid());
        assertNull(target.resolvedRecord());
        assertFalse(resolver.canonicalizeRecords(
                List.of(record(staleUuid, "profile-a"))).safeToPersist());
    }

    @Test
    void currentUuidRemainsTheActionTargetWithoutAFalseRedirect() {
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", currentUuid, List.of(currentUuid)),
                probe -> oneLocation(probe, LOCATION_A)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(record(currentUuid, "profile-a"));

        assertEquals(CommandNpcProfileActionResolver.ResolutionStatus.RESOLVED, target.status());
        assertEquals(currentUuid, target.targetNpcUuid());
        assertFalse(target.redirected());
        assertEquals("profile-a", target.resolvedRecord().profileId);
        assertFalse(resolver.canonicalizeRecords(
                List.of(record(currentUuid, "profile-a"))).identityChanged());
    }

    @Test
    void currentUuidRecordWithoutProfileIsPersistentlyUpgradedToStableProfileIdentity() {
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", currentUuid, List.of(currentUuid)),
                probe -> oneLocation(probe, LOCATION_A)
        );

        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                resolver.canonicalizeRecords(List.of(record(currentUuid, null)));

        assertTrue(canonical.safeToPersist());
        assertTrue(canonical.identityChanged());
        assertEquals(currentUuid, canonical.records().get(0).npcUuid);
        assertEquals("profile-a", canonical.records().get(0).profileId);
    }

    @Test
    void terminalLostTransitionIsBlockedWhenAnyProfileAliasIsLive() {
        UUID droppedUuid = UUID.randomUUID();
        UUID liveUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                identity("profile-a", droppedUuid, List.of(droppedUuid, liveUuid)),
                probe -> probe.equals(liveUuid)
                        ? oneLocation(probe, LOCATION_B)
                        : absent(probe)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveLostTransition(droppedUuid);

        assertEquals(CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED, target.status());
        assertEquals("profile_alias_is_live", target.reason());
        assertFalse(target.isActionable());
    }

    private CommandNpcProfileActionResolver resolver(
            NpcIdentityRepository.ProfileIdentity identity,
            CommandNpcIdentityService.LiveNpcProbe liveProbe) {
        CommandNpcIdentityService identityService = new CommandNpcIdentityService(
                (profileId, historicalUuid) -> new NpcIdentityRepository.IdentityLoadResult(
                        NpcIdentityRepository.LoadStatus.FOUND,
                        identity,
                        null,
                        null,
                        null,
                        null
                ),
                liveProbe
        );
        return new CommandNpcProfileActionResolver(identityService);
    }

    private NpcIdentityRepository.ProfileIdentity identity(String profileId,
                                                           UUID currentUuid,
                                                           List<UUID> aliases) {
        return new NpcIdentityRepository.ProfileIdentity(
                profileId,
                currentUuid,
                aliases,
                true,
                new NpcIdentityRepository.ProfileFlags(false, false, false, false, null, null),
                null,
                null
        );
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                null,
                null,
                null,
                null,
                null,
                "Mob_Test",
                "Follow",
                true,
                false,
                null
        );
    }

    private LoadedNpcIdentityIndex.Probe oneLocation(UUID npcUuid,
                                                     LoadedNpcIdentityIndex.Location location) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                List.of(location)
        );
    }

    private LoadedNpcIdentityIndex.Probe absent(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.ABSENT,
                List.of()
        );
    }

    private LoadedNpcIdentityIndex.Probe unknown(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN,
                List.of()
        );
    }
}
