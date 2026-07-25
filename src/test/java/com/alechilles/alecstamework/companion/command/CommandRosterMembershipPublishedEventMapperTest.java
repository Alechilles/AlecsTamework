package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipChangedEvent;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicSemanticEventProjection;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay-complete roster event and public mapping contracts. */
class CommandRosterMembershipPublishedEventMapperTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000251"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000251"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "dragon-horn");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "30000000-0000-0000-0000-000000000251"
            );
    private static final OperationId OPERATION = OperationId.parse(
            "40000000-0000-0000-0000-000000000251"
    );

    @Test
    void durableEventMapsWithoutIdentityOrLifecycleReadJoin() {
        CommandRosterMembershipChangeEvidence expected = evidence();
        ProjectionEvent event = committed(
                CommandRosterMembershipChangeCodec.draft(
                        OPERATION, expected, -1_000
                )
        );

        CommandFamilyRosterMembershipChangedEvent mapped =
                CommandRosterMembershipPublishedEventMapper.map(
                        event, -900
                );

        assertEquals(expected, CommandRosterMembershipChangeCodec
                .decodeEvidence(
                        event.payloadVersion(), event.payloadJson()
                ));
        assertEquals(OPERATION.value(), mapped.operationId());
        assertEquals(OWNER.value(), mapped.ownerUuid());
        assertEquals("dragon-horn", mapped.commandFamilyId());
        assertEquals(PROFILE.toString(), mapped.profileId());
        assertEquals(7, mapped.previousMembership().profileRevision());
        assertEquals("Miniwyvern", mapped.currentMembership().roleId());
        assertEquals(
                CommandFamilyRosterMemberState.ROSTER_STORED,
                mapped.currentMembership().state()
        );
        assertTrue(
                mapped.currentMembership().activeForBulkCommands()
        );
        assertEquals(4, mapped.previousRevision());
        assertEquals(5, mapped.currentRevision());
        assertEquals(-1_000, mapped.changedAtMs());
        assertEquals(-900, mapped.emittedAtMs());
    }

    @Test
    void mapperRejectsEnvelopeThatDisagreesWithPayload() {
        ProjectionEvent valid = committed(
                CommandRosterMembershipChangeCodec.draft(
                        OPERATION, evidence(), -1_000
                )
        );
        ProjectionEvent mismatched = new ProjectionEvent(
                valid.sequence(),
                valid.operationId(),
                valid.eventType(),
                "wrong-profile",
                valid.aggregateRevision(),
                valid.payloadVersion(),
                valid.payloadJson(),
                valid.createdAtMs()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> CommandRosterMembershipPublishedEventMapper.map(
                        mismatched, -900
                )
        );
    }

    @Test
    void legacyPartialPayloadIsRejectedInsteadOfJoined() {
        assertEquals(2, CommandRosterMembershipChangeCodec.VERSION);
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandRosterMembershipChangeCodec.decodeEvidence(
                        1, "{}"
                )
        );
    }

    @Test
    void checkpointedPublicObserverRoutesRosterEvent() {
        AtomicReference<com.alechilles.alecstamework.api.TameworkEvent>
                delivered = new AtomicReference<>();
        ReplacementPublicSemanticEventProjection observer =
                new ReplacementPublicSemanticEventProjection(
                        delivered::set, () -> -900
                );
        ProjectionEvent event = committed(
                CommandRosterMembershipChangeCodec.draft(
                        OPERATION, evidence(), -1_000
                )
        );

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                observer.apply(
                        event, ProjectionPublicationContext.LIVE_COMMIT
                )
        );
        assertTrue(delivered.get() instanceof
                CommandFamilyRosterMembershipChangedEvent);
    }

    private CommandRosterMembershipChangeEvidence evidence() {
        return CommandRosterMembershipChangeEvidence.from(
                new CommandRosterMutationOutcome(
                        FAMILY,
                        4,
                        5,
                        membership(2, false, -1_100),
                        membership(3, true, -1_000)
                ),
                new CompanionIdentity(
                        PROFILE,
                        "Ember",
                        "Miniwyvern",
                        null,
                        null,
                        "world-a",
                        -2_000,
                        -1_000,
                        -1_000,
                        7
                ),
                lifecycle(),
                CommandRosterMembershipChangeEvidence.Reason.UPSERTED
        );
    }

    private CommandRosterMembership membership(
            long revision,
            boolean active,
            long updatedAtMs
    ) {
        return new CommandRosterMembership(
                SLOT,
                FAMILY,
                PROFILE,
                revision,
                "favorites",
                active,
                new CommandRosterHome(
                        "world-a", 1.0, 2.0, 3.0
                ),
                -2_000,
                updatedAtMs
        );
    }

    private CompanionLifecycle lifecycle() {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT.toString()
                ),
                new LifecycleRevision(9),
                null,
                -1_000,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private ProjectionEvent committed(ProjectionEventDraft draft) {
        return new ProjectionEvent(
                new ProjectionSequence(51),
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
