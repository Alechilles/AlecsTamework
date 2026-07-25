package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Replay-complete timed event policy, status, and public mapping contracts. */
class TimedSummonPublishedEventMapperTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000252"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000252"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "dragon-horn");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "30000000-0000-0000-0000-000000000252"
            );
    private static final OperationId OPERATION = OperationId.parse(
            "40000000-0000-0000-0000-000000000252"
    );

    @Test
    void durableEventMapsPolicySessionStatusAndLimitsWithoutReads() {
        TimedSummonLeaseChangeEvidence expected = evidence();
        ProjectionEvent event = committed(
                TimedSummonLeaseChangeCodec.draft(
                        OPERATION, expected
                )
        );

        CommandTimedSummoningChangedEvent mapped =
                TimedSummonPublishedEventMapper.map(event, -900);

        assertEquals(expected, TimedSummonLeaseChangeCodec
                .decodeEvidence(
                        event.payloadVersion(), event.payloadJson()
                ));
        assertEquals("stored", mapped.reason());
        assertEquals(-1_000, mapped.occurredAtMs());
        assertEquals(-900, mapped.emittedAtMs());
        assertEquals(
                CommandTimedSummoningState.ACTIVE,
                mapped.previous().state()
        );
        assertEquals(4_000L, mapped.previous().remainingMs());
        assertEquals(
                CommandTimedSummoningState.ROSTER_STORED,
                mapped.current().state()
        );
        assertNull(mapped.current().summonSessionId());
        assertNull(mapped.current().remainingMs());
        assertEquals(4_000L, mapped.current().cooldownUntilMs());
    }

    @Test
    void mapperRejectsEnvelopeThatDisagreesWithPayload() {
        ProjectionEvent valid = committed(
                TimedSummonLeaseChangeCodec.draft(
                        OPERATION, evidence()
                )
        );
        ProjectionEvent mismatched = new ProjectionEvent(
                valid.sequence(),
                valid.operationId(),
                valid.eventType(),
                valid.aggregateId(),
                valid.aggregateRevision() + 1,
                valid.payloadVersion(),
                valid.payloadJson(),
                valid.createdAtMs()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> TimedSummonPublishedEventMapper.map(mismatched)
        );
    }

    @Test
    void legacyLeaseOnlyPayloadIsRejectedInsteadOfJoined() {
        assertEquals(2, TimedSummonLeaseChangeCodec.VERSION);
        assertThrows(
                IllegalArgumentException.class,
                () -> TimedSummonLeaseChangeCodec.decodeEvidence(
                        1, "{}"
                )
        );
    }

    @Test
    void checkpointedPublicObserverRecoversTimestampOnlyLegacyEvent() {
        AtomicReference<com.alechilles.alecstamework.api.TameworkEvent>
                delivered = new AtomicReference<>();
        ReplacementPublicSemanticEventProjection observer =
                new ReplacementPublicSemanticEventProjection(
                        delivered::set, () -> -900
                );
        ProjectionEvent currentEvent = committed(
                TimedSummonLeaseChangeCodec.draft(
                        OPERATION, evidence()
                )
        );
        // Regression: New World startup catch-up failed on a pre-fix capture
        // whose transaction timestamp followed its canonical lease timestamp.
        ProjectionEvent legacyTimestampEvent = new ProjectionEvent(
                currentEvent.sequence(),
                currentEvent.operationId(),
                currentEvent.eventType(),
                currentEvent.aggregateId(),
                currentEvent.aggregateRevision(),
                currentEvent.payloadVersion(),
                currentEvent.payloadJson(),
                -500
        );

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                observer.apply(
                        legacyTimestampEvent,
                        ProjectionPublicationContext.RECOVERY_CONVERGENCE
                )
        );
        assertEquals(
                CommandTimedSummoningChangedEvent.class,
                delivered.get().getClass()
        );
        CommandTimedSummoningChangedEvent mapped =
                (CommandTimedSummoningChangedEvent) delivered.get();
        assertEquals(-1_000, mapped.occurredAtMs());
        assertEquals(-900, mapped.emittedAtMs());
        assertEquals(4_000L, mapped.previous().remainingMs());
    }

    private TimedSummonLeaseChangeEvidence evidence() {
        TimedSummonLease before = new TimedSummonLease(
                PROFILE,
                1,
                TimedSummonSessionId.parse(
                        "50000000-0000-0000-0000-000000000252"
                ),
                5_000L,
                null,
                policy(),
                Set.of(),
                -2_000L,
                -3_000,
                -2_000
        );
        TimedSummonLease after = new TimedSummonLease(
                PROFILE,
                2,
                null,
                null,
                4_000L,
                policy(),
                Set.of(),
                null,
                -3_000,
                -1_000
        );
        return TimedSummonLeaseChangeEvidence.from(
                new TimedSummonLeaseChange(before, after),
                membership(),
                identity(),
                lifecycle(
                        LifecycleState.ACTIVE,
                        LifecycleLocation.liveEntity(
                                "live-alias", "world-a"
                        ),
                        7
                ),
                lifecycle(
                        LifecycleState.ROSTER_STORED,
                        LifecycleLocation.keyed(
                                LifecycleLocationKind.COMMAND_ROSTER,
                                SLOT.toString()
                        ),
                        8
                ),
                TimedSummonLeaseChangeEvidence.Reason.STORED
        );
    }

    private TimedSummonPolicy policy() {
        return new TimedSummonPolicy(
                "miniwyvern-timed",
                3L,
                10_000,
                5_000,
                true,
                List.of(5_000L)
        );
    }

    private CommandRosterMembership membership() {
        return new CommandRosterMembership(
                SLOT,
                FAMILY,
                PROFILE,
                4,
                null,
                true,
                null,
                -3_000,
                -1_000
        );
    }

    private CompanionIdentity identity() {
        return new CompanionIdentity(
                PROFILE,
                "Ember",
                "Miniwyvern",
                null,
                null,
                "world-a",
                -3_000,
                -1_000,
                -1_000,
                6
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
                -1_000,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private ProjectionEvent committed(ProjectionEventDraft draft) {
        return new ProjectionEvent(
                new ProjectionSequence(52),
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
