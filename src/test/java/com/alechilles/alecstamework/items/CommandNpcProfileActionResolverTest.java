package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNpcProfileActionResolverTest {
    @Test
    void relocationRedirectsToCanonicalCurrentAlias() {
        UUID profileUuid = UUID.randomUUID();
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver =
                resolver(profileUuid, currentUuid, Set.of());

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(
                        record(staleUuid, profileUuid.toString())
                );

        assertEquals(
                CommandNpcProfileActionResolver.ResolutionStatus.RESOLVED,
                target.status()
        );
        assertEquals(currentUuid, target.targetNpcUuid());
        assertTrue(target.redirected());
        assertEquals(profileUuid.toString(), target.resolvedRecord().profileId);
    }

    @Test
    void dormantCanonicalStatusBlocksRelocation() {
        UUID profileUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                profileUuid,
                currentUuid,
                Set.of(TameworkSnapshotCodecs.DEATH)
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveRelocation(
                        record(currentUuid, profileUuid.toString())
                );

        assertEquals(
                CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED,
                target.status()
        );
        assertEquals("profile_is_dead", target.reason());
        assertFalse(target.isActionable());
    }

    @Test
    void liveAliasCannotBecomeLost() {
        UUID profileUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver = resolver(
                profileUuid,
                currentUuid,
                Set.of(),
                this::oneLocation
        );

        CommandNpcProfileActionResolver.ActionTarget target =
                resolver.resolveLostTransition(currentUuid);

        assertEquals(
                CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED,
                target.status()
        );
        assertEquals("profile_alias_is_live", target.reason());
    }

    @Test
    void canonicalizationUpdatesOnlyIdentityFields() {
        UUID profileUuid = UUID.randomUUID();
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver =
                resolver(profileUuid, currentUuid, Set.of());

        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                resolver.canonicalizeRecords(List.of(record(staleUuid, null)));

        assertTrue(canonical.safeToPersist());
        assertTrue(canonical.identityChanged());
        assertEquals(currentUuid, canonical.records().get(0).npcUuid);
        assertEquals(
                profileUuid.toString(),
                canonical.records().get(0).profileId
        );
    }

    private CommandNpcProfileActionResolver resolver(
            UUID profileUuid,
            UUID currentUuid,
            Set<SnapshotKind> snapshots
    ) {
        return resolver(profileUuid, currentUuid, snapshots, this::absent);
    }

    private CommandNpcProfileActionResolver resolver(
            UUID profileUuid,
            UUID currentUuid,
            Set<SnapshotKind> snapshots,
            CommandNpcIdentityService.LiveNpcProbe probe
    ) {
        CompanionProfileProjectionState projection =
                new CompanionProfileProjectionState(
                        new ProfileId(profileUuid),
                        new NpcAlias(currentUuid),
                        null,
                        null,
                        "Tamed_Chicken",
                        "Chicken",
                        null,
                        true,
                        null,
                        null,
                        Set.of(),
                        snapshots,
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
        return new CommandNpcProfileActionResolver(
                new CommandNpcIdentityService(view, probe)
        );
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                null,
                "default",
                null,
                "Chicken",
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
}
