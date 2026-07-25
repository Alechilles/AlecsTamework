package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical rebuild, replay, join-lag, metadata, and alias tests. */
class CommandRosterProjectionIndexTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000101");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000101");
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "default");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "50000000-0000-0000-0000-000000000101"
            );
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000101");

    @Test
    void rebuildAndMembershipEventProduceCompleteActionView() {
        CommandRosterProjectionIndex index = rebuilt(active(3));
        CommandRosterActionView initial =
                index.actionSnapshot().get(PROFILE);
        assertEquals("Mini", initial.roleId());
        assertEquals(alias(1).alias(), initial.currentAlias());

        CommandRosterMembership updated = membership(3, true);
        ProjectionEvent event = event(
                1,
                CommandRosterMembershipChangeCodec.draft(
                        OPERATION,
                        new CommandRosterMutationOutcome(
                                FAMILY,
                                4,
                                5,
                                membership(2, false),
                                updated
                        ),
                        -90
                )
        );

        assertEquals(ProjectionApplyOutcome.APPLIED, index.apply(event));
        assertTrue(index.actionSnapshot().get(PROFILE)
                .membership().activeForBulkCommands());
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(event)
        );
        assertEquals(5L, index.familyRevisionSnapshot().get(FAMILY));
    }

    @Test
    void storedLifecycleMustHaveExactlyOneMatchingCanonicalSlot() {
        CommandRosterProjectionIndex index = rebuilt(active(3));
        CompanionLifecycle wrong = stored(
                4,
                CommandRosterSlotId.parse(
                        "50000000-0000-0000-0000-000000000199"
                )
        );

        index.apply(lifecycleEvent(1, active(3), wrong));
        assertEquals(Set.of(PROFILE), index.laggingProfiles());
        assertTrue(index.actionSnapshot().isEmpty());

        CompanionLifecycle corrected = stored(5, SLOT);
        index.apply(lifecycleEvent(2, wrong, corrected));
        assertTrue(index.laggingProfiles().isEmpty());

        CommandRosterProjectionIndex missing =
                new CommandRosterProjectionIndex();
        missing.rebuild(List.of(), List.of(), List.of(corrected));
        assertEquals(Set.of(PROFILE), missing.laggingProfiles());

        CommandRosterMembership membership = membership(2, false);
        CommandRosterProjectionIndex torn =
                new CommandRosterProjectionIndex();
        assertThrows(
                IllegalArgumentException.class,
                () -> torn.rebuild(
                        List.of(new CommandRoster(
                                FAMILY,
                                4,
                                List.of(membership),
                                -200,
                                -100
                        )),
                        List.of(new CommandRosterProjectionSeed(
                                membership,
                                identity(),
                                alias(1),
                                active(3)
                        )),
                        List.of(corrected)
                )
        );
    }

    @Test
    void metadataAndAliasEventsRefreshOnlyTheirCanonicalEvidence() {
        CommandRosterProjectionIndex index = rebuilt(active(3));

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(profileEvent(
                        1,
                        CompanionProfileProjectionChange.Source.METADATA,
                        5,
                        profileState("Mini", alias(1).alias()),
                        profileState("Guard", alias(1).alias())
                ))
        );
        NpcAlias nextAlias =
                NpcAlias.parse(
                        "60000000-0000-0000-0000-000000000102"
                );
        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(profileEvent(
                        2,
                        CompanionProfileProjectionChange.Source.ALIAS,
                        2,
                        profileState("Guard", alias(1).alias()),
                        profileState("Guard", nextAlias)
                ))
        );

        CommandRosterActionView view =
                index.actionSnapshot().get(PROFILE);
        assertEquals("Guard", view.roleId());
        assertEquals(5, view.metadataRevision());
        assertEquals(nextAlias, view.currentAlias());
    }

    private CommandRosterProjectionIndex rebuilt(
            CompanionLifecycle lifecycle
    ) {
        CommandRosterMembership membership = membership(2, false);
        CommandRoster roster = new CommandRoster(
                FAMILY, 4, List.of(membership), -200, -100
        );
        CommandRosterProjectionSeed seed =
                new CommandRosterProjectionSeed(
                        membership, identity(), alias(1), lifecycle
                );
        CommandRosterProjectionIndex index =
                new CommandRosterProjectionIndex();
        index.rebuild(
                List.of(roster), List.of(seed), List.of(lifecycle)
        );
        return index;
    }

    private CompanionIdentity identity() {
        return new CompanionIdentity(
                PROFILE,
                "Companion",
                "Mini",
                null,
                null,
                "world-a",
                -200,
                -100,
                -100,
                4
        );
    }

    private CompanionAlias alias(long generation) {
        return new CompanionAlias(
                NpcAlias.parse(
                        "60000000-0000-0000-0000-000000000101"
                ),
                PROFILE,
                generation,
                CompanionAlias.State.CURRENT,
                null,
                -100,
                null
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
                null,
                -200,
                -100
        );
    }

    private CompanionLifecycle active(long revision) {
        return lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity("entity", "world-a"),
                revision
        );
    }

    private CompanionLifecycle stored(
            long revision,
            CommandRosterSlotId slotId
    ) {
        return lifecycle(
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        slotId.toString()
                ),
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

    private ProjectionEvent lifecycleEvent(
            long sequence,
            CompanionLifecycle before,
            CompanionLifecycle after
    ) {
        CompanionLifecycleProjectionChange change =
                new CompanionLifecycleProjectionChange(before, after);
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OPERATION,
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE,
                PROFILE.toString(),
                after.revision().value(),
                CompanionLifecycleProjectionChangeCodec.PAYLOAD_VERSION,
                CompanionLifecycleProjectionChangeCodec.encode(change),
                after.stateChangedAtMs()
        );
    }

    private ProjectionEvent profileEvent(
            long sequence,
            CompanionProfileProjectionChange.Source source,
            long revision,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after
    ) {
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        source,
                        PROFILE,
                        revision,
                        before,
                        after,
                        -90
                );
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OPERATION,
                CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                CompanionProfileProjectionChangeCodec.aggregateId(change),
                revision,
                CompanionProfileProjectionChangeCodec.VERSION,
                CompanionProfileProjectionChangeCodec.encode(change),
                -90
        );
    }

    private CompanionProfileProjectionState profileState(
            String roleId,
            NpcAlias alias
    ) {
        return new CompanionProfileProjectionState(
                PROFILE,
                alias,
                LifecycleState.ACTIVE,
                OWNER,
                null,
                roleId,
                "Companion",
                "entity",
                true,
                null,
                null,
                Set.of(),
                Set.of(),
                -100
        );
    }

    private ProjectionEvent event(
            long sequence,
            ProjectionEventDraft draft
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                draft.operationId(),
                draft.eventType(),
                draft.aggregateId(),
                draft.aggregateRevision(),
                draft.payloadVersion(),
                draft.payloadJson(),
                draft.createdAtMs()
        );
    }
}
