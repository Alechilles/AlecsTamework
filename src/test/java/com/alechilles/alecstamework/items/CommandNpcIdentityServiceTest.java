package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                projection(profileUuid, currentUuid, LifecycleState.ACTIVE),
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
                projection(actualProfile, currentUuid, LifecycleState.ACTIVE),
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
        CommandPersistenceView empty = new CommandPersistenceView(
                new EmptyProjectionLookup()
        );
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
                projection(profileUuid, currentUuid, LifecycleState.ACTIVE),
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
                projection(
                        profileUuid,
                        currentUuid,
                        LifecycleState.DEAD_REVIVABLE
                ),
                this::absent
        );

        CommandNpcIdentityService.IdentityResolution resolution =
                service.resolve(record(currentUuid, profileUuid.toString()));

        assertTrue(resolution.durableState().dead());
        assertFalse(resolution.durableState().captured());
        assertFalse(resolution.durableState().lost());
        assertFalse(resolution.durableState().inCoop());
    }

    private CommandNpcIdentityService service(
            CompanionProfileProjectionState projection,
            CommandNpcIdentityService.LiveNpcProbe probe
    ) {
        CommandPersistenceView view = new CommandPersistenceView(
                new SingleProjectionLookup(projection)
        );
        return new CommandNpcIdentityService(view, probe);
    }

    private CompanionProfileProjectionState projection(
            UUID profileUuid,
            UUID currentUuid,
            LifecycleState lifecycleState
    ) {
        return new CompanionProfileProjectionState(
                new ProfileId(profileUuid),
                new NpcAlias(currentUuid),
                lifecycleState,
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
                LoadedNpcIdentityIndex.ProbeStatus.ABSENT,
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

    private record SingleProjectionLookup(
            CompanionProfileProjectionState projection
    ) implements CommandPersistenceView.ProjectionLookup {
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

    private static final class EmptyProjectionLookup
            implements CommandPersistenceView.ProjectionLookup {
        @Override
        public Optional<CompanionProfileProjectionState> find(
                ProfileId profileId
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<CompanionProfileProjectionState> find(
                NpcAlias alias
        ) {
            return Optional.empty();
        }
    }
}
