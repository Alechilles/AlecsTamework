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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNpcIdentityServiceTest {
    @Test
    void staleAliasCanonicalizesToCurrentProjectedAlias() {
        UUID profileUuid = UUID.randomUUID();
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcIdentityService service = service(
                view(profileUuid, currentUuid, LifecycleState.ACTIVE),
                this::absent
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(staleUuid, profileUuid.toString()));
        LinkedNpcRecord canonical =
                service.canonicalRecord(
                        record(staleUuid, profileUuid.toString()),
                        resolution
                );

        assertEquals(
                CommandNpcIdentityService.ResolutionStatus.RESOLVED,
                resolution.status()
        );
        assertEquals(currentUuid, resolution.currentNpcUuid());
        assertEquals(currentUuid, canonical.npcUuid);
        assertEquals(profileUuid.toString(), canonical.profileId);
    }

    @Test
    void aliasProjectionConflictingWithExplicitProfileFailsClosed() {
        UUID explicitProfile = UUID.randomUUID();
        UUID actualProfile = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcIdentityService service = service(
                view(actualProfile, currentUuid, LifecycleState.ACTIVE),
                this::absent
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(currentUuid, explicitProfile.toString()));

        assertEquals(
                CommandNpcIdentityService.ResolutionStatus.CONFLICT,
                resolution.status()
        );
        assertEquals(
                "record_profile_conflicts_with_current_alias",
                resolution.failureReason()
        );
    }

    @Test
    void projectionAbsenceWithoutExactLiveEvidenceFailsClosed() {
        UUID npcUuid = UUID.randomUUID();
        CommandPersistenceView empty = new CommandPersistenceView(new CompanionQueries(
                new CompanionIndex(System::currentTimeMillis, (before, after) -> { }), new LoadedBodies<>()));
        CommandNpcIdentityService service =
                new CommandNpcIdentityService(empty, this::absent);

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(npcUuid, null));

        assertEquals(
                CommandNpcIdentityService.ResolutionStatus.UNRESOLVED,
                resolution.status()
        );
        assertEquals(npcUuid.toString(), resolution.profileId());
        assertTrue(resolution.durableState().suppressesLiveAction());
    }

    @Test
    void duplicateLiveAliasesRemainAConflict() {
        UUID profileUuid = UUID.randomUUID();
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcIdentityService service = service(
                view(profileUuid, currentUuid, LifecycleState.ACTIVE),
                this::oneLocation
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(staleUuid, profileUuid.toString()));

        assertEquals(
                CommandNpcIdentityService.ResolutionStatus.CONFLICT,
                resolution.status()
        );
        assertEquals(
                "multiple_live_profile_aliases",
                resolution.failureReason()
        );
        assertEquals(2, resolution.liveUuids().size());
    }

    @Test
    void canonicalSnapshotFlagsAreExposedWithoutRecoveryState() {
        UUID profileUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcIdentityService service = service(
                view(profileUuid, currentUuid, LifecycleState.DEAD_REVIVABLE),
                this::absent
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(currentUuid, profileUuid.toString()));

        assertTrue(resolution.durableState().dead());
        assertFalse(resolution.durableState().captured());
        assertFalse(resolution.durableState().lost());
        assertFalse(resolution.durableState().inCoop());
    }

    @Test
    void canonicalizationRemovesReleasedProfilesFromCommandTools() {
        UUID profileUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcIdentityService service = service(
                view(profileUuid, currentUuid, LifecycleState.RELEASED),
                this::absent
        );

        CommandNpcIdentityService.CanonicalizationResult result =
                service.canonicalize(List.of(record(
                        currentUuid, profileUuid.toString()
                )));

        assertTrue(result.records().isEmpty());
        assertFalse(result.hasConflicts());
        assertFalse(result.hasFailures());
    }

    private CommandNpcIdentityService service(
            CommandPersistenceView view,
            CommandNpcIdentityService.LiveNpcProbe probe
    ) {
        return new CommandNpcIdentityService(view, probe);
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

    private LoadedNpcIdentityIndex.Probe absent(UUID npcUuid) {
        return new LoadedNpcIdentityIndex.Probe(
                npcUuid,
                LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN,
                List.of()
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
}
