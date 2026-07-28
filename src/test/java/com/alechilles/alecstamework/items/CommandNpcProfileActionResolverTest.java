package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.joml.Vector3d;
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
                resolver(profileUuid, currentUuid, LifecycleState.ACTIVE);

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
                LifecycleState.DEAD_REVIVABLE
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
                LifecycleState.ACTIVE,
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
    void canonicalizationUpdatesStaleAliasAndPreservesNonIdentityFields() {
        UUID profileUuid = UUID.randomUUID();
        UUID staleUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver resolver =
                resolver(profileUuid, currentUuid, LifecycleState.ACTIVE);
        LinkedNpcRecord source = new LinkedNpcRecord(
                staleUuid,
                profileUuid.toString(),
                new Vector3d(1.25, -2.5, 3.75),
                "default",
                new Vector3d(4.5, 5.25, -6.75),
                "Clucky",
                "npc.chicken.clucky",
                "Tamed_Chicken",
                "Stay",
                false,
                true,
                "backyard-flock"
        );

        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                resolver.canonicalizeRecords(List.of(source));

        assertTrue(canonical.safeToPersist());
        assertTrue(canonical.identityChanged());
        LinkedNpcRecord result = canonical.records().getFirst();
        assertEquals(currentUuid, result.npcUuid);
        assertEquals(
                profileUuid.toString(),
                result.profileId
        );
        assertEquals(source.lastKnownPosition, result.lastKnownPosition);
        assertEquals(source.lastKnownWorldName, result.lastKnownWorldName);
        assertEquals(source.homePosition, result.homePosition);
        assertEquals(source.cachedDisplayName, result.cachedDisplayName);
        assertEquals(source.cachedNameKey, result.cachedNameKey);
        assertEquals(source.cachedRoleId, result.cachedRoleId);
        assertEquals(source.cachedCommandState, result.cachedCommandState);
        assertEquals(source.active, result.active);
        assertEquals(source.breedingEnabled, result.breedingEnabled);
        assertEquals(source.groupId, result.groupId);
    }

    private CommandNpcProfileActionResolver resolver(
            UUID profileUuid,
            UUID currentUuid,
            LifecycleState lifecycleState
    ) {
        return resolver(
                profileUuid,
                currentUuid,
                lifecycleState,
                this::absent
        );
    }

    private CommandNpcProfileActionResolver resolver(
            UUID profileUuid,
            UUID currentUuid,
            LifecycleState lifecycleState,
            CommandNpcIdentityService.LiveNpcProbe probe
    ) {
        CompanionProfileProjectionState projection =
                new CompanionProfileProjectionState(
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
