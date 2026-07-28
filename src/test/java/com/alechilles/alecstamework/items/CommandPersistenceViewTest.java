package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPersistenceViewTest {
    @Test
    void exposesOnlyCanonicalCommandFacingProfileFacts() {
        UUID profileUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID toolUuid = UUID.randomUUID();
        CompanionProfileProjectionState projection =
                new CompanionProfileProjectionState(
                        new ProfileId(profileUuid),
                        new NpcAlias(currentUuid),
                        LifecycleState.DEAD_REVIVABLE,
                        new OwnerId(ownerUuid),
                        "Owner",
                        "Tamed_Chicken",
                        "Chicken",
                        "Cluckles",
                        true,
                        "coop/chicken",
                        2,
                        Set.of(toolUuid),
                        Set.of(
                                TameworkSnapshotCodecs.DEATH,
                                CompanionCaptureRequest.SNAPSHOT_KIND,
                                TameworkSnapshotCodecs.LOST
                        ),
                        600L,
                        100L
                );
        CommandPersistenceView view = new CommandPersistenceView(
                lookup(projection)
        );

        CommandPersistenceView.ProfileSnapshot result =
                view.find(record(currentUuid, profileUuid.toString()))
                        .orElseThrow();

        assertEquals(profileUuid, result.profileId().value());
        assertEquals(currentUuid, result.currentNpcUuid());
        assertEquals(ownerUuid, result.ownerUuid());
        assertEquals(Set.of(toolUuid), result.toolIds());
        assertTrue(result.dead());
        assertFalse(result.captured());
        assertFalse(result.lost());
        assertFalse(result.inCoop());
        assertTrue(result.dormant());
        assertTrue(result.restorable());
        assertEquals(600L, result.restorationAvailableAtMs());
    }

    @Test
    void absenceDoesNotInventLifecycleState() {
        UUID npcUuid = UUID.randomUUID();
        CommandPersistenceView view = new CommandPersistenceView(
                new CommandPersistenceView.ProjectionLookup() {
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
        );
        LinkedNpcRecord record = record(npcUuid, null);

        assertTrue(view.find(record).isEmpty());
        assertEquals(npcUuid, view.profileId(record).value());
        assertFalse(view.find(null).isPresent());
    }

    @Test
    void lifecycleAloneControlsEveryCommandStatus() {
        UUID profileUuid = UUID.randomUUID();
        UUID npcUuid = UUID.randomUUID();
        Set<com.alechilles.alecstamework.companion.snapshot.SnapshotKind>
                misleadingSnapshots = Set.of(
                TameworkSnapshotCodecs.DEATH,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                TameworkSnapshotCodecs.COOP,
                TameworkSnapshotCodecs.LOST
        );

        for (LifecycleState state : LifecycleState.values()) {
            CompanionProfileProjectionState projection =
                    new CompanionProfileProjectionState(
                            new ProfileId(profileUuid),
                            new NpcAlias(npcUuid),
                            state,
                            null,
                            null,
                            "Tamed_Chicken",
                            "Chicken",
                            null,
                            true,
                            "misleading-coop",
                            1,
                            Set.of(),
                            misleadingSnapshots,
                            100L
                    );
            CommandPersistenceView.ProfileSnapshot result =
                    new CommandPersistenceView(lookup(projection))
                            .find(record(
                                    npcUuid, profileUuid.toString()
                            ))
                            .orElseThrow();

            assertEquals(
                    state == LifecycleState.DEAD_REVIVABLE,
                    result.dead()
            );
            assertEquals(
                    state == LifecycleState.CAPTURED,
                    result.captured()
            );
            assertEquals(state == LifecycleState.COOP, result.inCoop());
            assertEquals(state == LifecycleState.LOST, result.lost());
            assertEquals(
                    state != LifecycleState.ACTIVE
                            && state != LifecycleState.UNLOADED,
                    result.blocksLiveAction()
            );
        }
    }

    private CommandPersistenceView.ProjectionLookup lookup(
            CompanionProfileProjectionState projection
    ) {
        return new CommandPersistenceView.ProjectionLookup() {
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
        };
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
}
