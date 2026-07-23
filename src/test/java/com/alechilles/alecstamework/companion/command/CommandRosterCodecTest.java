package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Durable command request and post-commit event round-trip tests. */
class CommandRosterCodecTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000091");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000091");
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "default");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "50000000-0000-0000-0000-000000000091"
            );

    @Test
    void membershipRequestAndChangeRoundTripWithoutHiddenEvidence() {
        CommandRosterMembershipRequest request =
                new CommandRosterMembershipRequest(
                        CommandRosterMembershipRequest.Action.UPSERT,
                        PROFILE,
                        FAMILY,
                        SLOT,
                        4,
                        2L,
                        7,
                        "Mini",
                        new LifecycleRevision(3),
                        "world-a",
                        "favorites",
                        true,
                        new CommandRosterHome(
                                "world-a", 1.25, 2.5, -3.75
                        ),
                        -100
                );
        assertEquals(
                request,
                CommandRosterMembershipDefinition.INSTANCE.decode(
                        CommandRosterMembershipDefinition.INSTANCE
                                .encode(request)
                )
        );

        CommandRosterMutationOutcome outcome =
                new CommandRosterMutationOutcome(
                        FAMILY,
                        4,
                        5,
                        membership(2, false),
                        membership(3, true)
                );
        assertEquals(
                outcome,
                CommandRosterMembershipChangeCodec.decode(
                        CommandRosterMembershipChangeCodec.VERSION,
                        CommandRosterMembershipChangeCodec.encode(outcome)
                )
        );
    }

    @Test
    void transitionRoundTripsExactLifecycleAndPolicySnapshot() {
        CompanionLifecycle active = active(4);
        CommandRosterTransitionRequest request =
                new CommandRosterTransitionRequest(
                        FAMILY,
                        SLOT,
                        3,
                        new PopulationGroupTransitionAdmissionRequest(
                                stored(3),
                                active,
                                8,
                                11,
                                List.of(new PopulationGroupPolicy(
                                        "mod:mini",
                                        PopulationGroupScope.GLOBAL,
                                        4,
                                        2,
                                        11
                                )),
                                active.stateChangedAtMs()
                        )
                );

        assertEquals(
                request,
                CommandRosterTransitionDefinition.INSTANCE.decode(
                        CommandRosterTransitionDefinition.INSTANCE
                                .encode(request)
                )
        );
    }

    @Test
    void transitionCannotSmuggleOwnerWorldOrReconciliationChanges() {
        CompanionLifecycle before = stored(3);
        CompanionLifecycle active = active(4);
        CompanionLifecycle changedOwnerWorld = new CompanionLifecycle(
                active.profileId(),
                active.ownerId(),
                active.state(),
                active.location(),
                active.revision(),
                null,
                active.stateChangedAtMs(),
                active.lastReconciledGeneration(),
                null,
                "world-b"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandRosterTransitionRequest(
                        FAMILY,
                        SLOT,
                        3,
                        new PopulationGroupTransitionAdmissionRequest(
                                before,
                                changedOwnerWorld,
                                8,
                                11,
                                List.of(new PopulationGroupPolicy(
                                        "mod:mini",
                                        PopulationGroupScope.GLOBAL,
                                        4,
                                        2,
                                        11
                                )),
                                changedOwnerWorld.stateChangedAtMs()
                        )
                )
        );
    }

    private CommandRosterMembership membership(
            long revision,
            boolean activeForBulkCommands
    ) {
        return new CommandRosterMembership(
                SLOT,
                FAMILY,
                PROFILE,
                revision,
                "favorites",
                activeForBulkCommands,
                new CommandRosterHome("world-a", 1, 2, 3),
                -200,
                -100
        );
    }

    private CompanionLifecycle stored(long revision) {
        return lifecycle(
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT.toString()
                ),
                revision
        );
    }

    private CompanionLifecycle active(long revision) {
        return lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity("entity", "world-a"),
                revision
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleLocation location,
            long revision
    ) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                -100 + revision,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }
}
