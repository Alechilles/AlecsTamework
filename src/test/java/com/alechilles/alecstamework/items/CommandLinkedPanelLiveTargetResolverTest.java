package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import java.util.List;
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
        CommandPersistenceView view = view(profileUuid, liveUuid, LifecycleState.ACTIVE);
        return new CommandLinkedPanelLiveTargetResolver(
                new CommandNpcProfileActionResolver(
                        new CommandNpcIdentityService(view, probe)
                )
        );
    }

    private static CommandPersistenceView view(UUID profileUuid, UUID currentUuid, LifecycleState state) {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        CompanionRecord live = CompanionTransitions.newLive(profileUuid, 0, new CompanionTransitions.BodyFacts(
                currentUuid, null, null, "Tamed_Chicken", "Chicken", "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(live);
        switch (state) {
            case ACTIVE -> { }
            case DEAD_REVIVABLE -> index.update(profileUuid, live.revision(),
                    CompanionTransitions.died(live, CompanionSummary.EMPTY, 1_000L, 61_000L, "PLAYER", null));
            case RELEASED -> index.update(profileUuid, live.revision(), CompanionTransitions.released(live));
            default -> throw new IllegalArgumentException(state.name());
        }
        return new CommandPersistenceView(new CompanionQueries(index, new LoadedBodies<>()));
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
