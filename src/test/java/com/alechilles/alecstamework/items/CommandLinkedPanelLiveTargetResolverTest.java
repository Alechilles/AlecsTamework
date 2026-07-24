package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandLinkedPanelLiveTargetResolverTest {
    @Test
    void redirectsHistoricalRecordToCanonicalProjection() {
        UUID profileUuid = UUID.randomUUID();
        UUID historicalUuid = UUID.randomUUID();
        UUID liveUuid = UUID.randomUUID();
        CommandLinkedPanelLiveTargetResolver resolver =
                resolver(
                        profileUuid,
                        liveUuid,
                        candidate -> candidate.equals(liveUuid)
                                ? oneLocation(candidate)
                                : absent(candidate)
                );

        LinkedNpcRecord redirected = resolver.resolveRedirect(
                record(historicalUuid, profileUuid.toString())
        );

        assertEquals(liveUuid, redirected.npcUuid);
        assertEquals(profileUuid.toString(), redirected.profileId);
    }

    @Test
    void doesNotRedirectWhenBothAliasesAreLive() {
        UUID profileUuid = UUID.randomUUID();
        UUID historicalUuid = UUID.randomUUID();
        UUID liveUuid = UUID.randomUUID();
        CommandLinkedPanelLiveTargetResolver resolver =
                resolver(profileUuid, liveUuid, this::oneLocation);

        assertNull(resolver.resolveRedirect(
                record(historicalUuid, profileUuid.toString())
        ));
    }

    private CommandLinkedPanelLiveTargetResolver resolver(
            UUID profileUuid,
            UUID liveUuid,
            CommandNpcIdentityService.LiveNpcProbe probe
    ) {
        CompanionProfileProjectionState projection =
                new CompanionProfileProjectionState(
                        new ProfileId(profileUuid),
                        new NpcAlias(liveUuid),
                        null,
                        null,
                        "Tamed_Chicken",
                        "Chicken",
                        null,
                        true,
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        100L
                );
        CommandPersistenceView view = new CommandPersistenceView(
                new CommandPersistenceView.ProjectionLookup() {
                    @Override
                    public Optional<CompanionProfileProjectionState> find(
                            ProfileId profileId
                    ) {
                        return projection.profileId().equals(profileId)
                                ? Optional.of(projection)
                                : Optional.empty();
                    }

                    @Override
                    public Optional<CompanionProfileProjectionState> find(
                            NpcAlias alias
                    ) {
                        return projection.currentAlias().equals(alias)
                                ? Optional.of(projection)
                                : Optional.empty();
                    }
                }
        );
        return new CommandLinkedPanelLiveTargetResolver(
                new CommandNpcProfileActionResolver(
                        new CommandNpcIdentityService(view, probe)
                )
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
                "Tamed_Chicken",
                "Follow",
                true,
                false,
                null
        );
    }

    private LoadedNpcIdentityIndex.Probe oneLocation(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                List.of(new LoadedNpcIdentityIndex.Location(
                        "default", "store-a"
                ))
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
