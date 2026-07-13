package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for released coop projections whose command record keeps an old UUID. */
class CommandLinkedPanelLiveTargetResolverTest {
    @Test
    void redirectsHistoricalRecordToSoleCanonicalLiveProjection() {
        UUID historicalUuid = UUID.randomUUID();
        UUID liveUuid = UUID.randomUUID();
        CommandLinkedPanelLiveTargetResolver resolver = resolver(
                historicalUuid,
                liveUuid,
                candidate -> candidate.equals(liveUuid)
                        ? oneLocation(candidate)
                        : absent(candidate)
        );

        LinkedNpcRecord redirected = resolver.resolveRedirect(record(historicalUuid));

        assertEquals(liveUuid, redirected.npcUuid);
        assertEquals("profile-a", redirected.profileId);
    }

    @Test
    void doesNotRedirectWhenBothAliasesAreLive() {
        UUID historicalUuid = UUID.randomUUID();
        UUID liveUuid = UUID.randomUUID();
        CommandLinkedPanelLiveTargetResolver resolver = resolver(
                historicalUuid, liveUuid, this::oneLocation);

        assertNull(resolver.resolveRedirect(record(historicalUuid)));
    }

    private CommandLinkedPanelLiveTargetResolver resolver(
            UUID historicalUuid,
            UUID liveUuid,
            CommandNpcIdentityService.LiveNpcProbe probe) {
        NpcIdentityRepository.ProfileIdentity identity = new NpcIdentityRepository.ProfileIdentity(
                "profile-a",
                liveUuid,
                List.of(liveUuid, historicalUuid),
                true,
                new NpcIdentityRepository.ProfileFlags(false, false, false, false, null, null),
                null,
                null
        );
        CommandNpcIdentityService identityService = new CommandNpcIdentityService(
                (profileId, uuid) -> NpcIdentityRepository.IdentityLoadResult.found(identity),
                probe
        );
        return new CommandLinkedPanelLiveTargetResolver(
                new CommandNpcProfileActionResolver(identityService));
    }

    private LinkedNpcRecord record(UUID npcUuid) {
        return new LinkedNpcRecord(
                npcUuid, null, null, null, null, null, null,
                "Tamed_Chicken", "Follow", true, false, null
        );
    }

    private LoadedNpcIdentityIndex.Probe oneLocation(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                List.of(new LoadedNpcIdentityIndex.Location("default", "store-a"))
        );
    }

    private LoadedNpcIdentityIndex.Probe absent(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.ABSENT,
                List.of()
        );
    }
}
