package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public observer bridge tests for mapping, independent revisions, and retry safety. */
class CompanionProfileObserverProjectionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final NpcAlias HISTORICAL_ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000003");

    @Test
    void mapsSelfContainedEvidenceAndKeepsRevisionDomainsIndependent() {
        ArrayList<NpcProfileChangedEvent> events = new ArrayList<>();
        CompanionProfileObserverProjection projection =
                new CompanionProfileObserverProjection(events::add);
        CompanionProfileProjectionState created = state(null, "First", -90);
        CompanionProfileProjectionState aliased = state(ALIAS, "First", -80);

        ProjectionEvent metadata = event(
                1,
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.METADATA,
                        PROFILE,
                        0,
                        null,
                        created,
                        -90
                )
        );
        ProjectionEvent alias = event(
                2,
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.ALIAS,
                        PROFILE,
                        0,
                        created,
                        aliased,
                        -80
                )
        );

        assertEquals(ProjectionApplyOutcome.APPLIED, projection.apply(metadata));
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                projection.apply(metadata)
        );
        assertEquals(ProjectionApplyOutcome.APPLIED, projection.apply(alias));

        assertEquals(2, events.size());
        NpcProfileChangedEvent createdEvent = events.getFirst();
        assertEquals(
                Set.of(
                        ProfileChangeType.CREATED,
                        ProfileChangeType.OWNER,
                        ProfileChangeType.ROLE,
                        ProfileChangeType.DISPLAY_NAME,
                        ProfileChangeType.TAMED,
                        ProfileChangeType.TOOL_LINKS,
                        ProfileChangeType.ACTIVE_SNAPSHOTS
                ),
                createdEvent.changeTypes()
        );
        assertNull(createdEvent.before());
        assertEquals(OWNER.value(), createdEvent.after().ownerUuid());
        assertEquals("Owner", createdEvent.after().ownerName());
        assertEquals(
                Set.of("40000000-0000-0000-0000-000000000001"),
                createdEvent.after().toolIds()
        );
        assertEquals(
                Set.of("capture"),
                createdEvent.after().activeSnapshotTypes()
        );
        assertEquals(
                Set.of(ProfileChangeType.CURRENT_NPC_UUID),
                events.get(1).changeTypes()
        );
        assertEquals(ALIAS.value(), events.get(1).after().currentNpcUuid());
    }

    @Test
    void listenerFailureDoesNotAdvanceInMemoryRevision() {
        AtomicInteger attempts = new AtomicInteger();
        CompanionProfileObserverProjection projection =
                new CompanionProfileObserverProjection(event -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("try_again");
                    }
                });
        ProjectionEvent event = event(
                1,
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.METADATA,
                        PROFILE,
                        0,
                        null,
                        state(null, "First", -90),
                        -90
                )
        );

        assertThrows(IllegalStateException.class, () -> projection.apply(event));
        assertEquals(ProjectionApplyOutcome.APPLIED, projection.apply(event));
        assertEquals(2, attempts.get());
    }

    @Test
    void rebuildAndAfterCommitEventsMaintainNonBlockingLookup() {
        CompanionProfileObserverProjection projection =
                new CompanionProfileObserverProjection(event -> {
                });
        CompanionProfileProjectionState initial =
                state(ALIAS, "First", -90);
        projection.rebuild(List.of(initial), List.of(new CompanionAlias(
                HISTORICAL_ALIAS,
                PROFILE,
                0,
                CompanionAlias.State.RETIRED,
                null,
                -100,
                -90L
        )));

        assertEquals(initial, projection.find(PROFILE).orElseThrow());
        assertEquals(initial, projection.find(ALIAS).orElseThrow());
        assertEquals(
                initial,
                projection.findKnownAlias(HISTORICAL_ALIAS).orElseThrow()
        );
        assertEquals(initial, projection.snapshot().get(PROFILE));

        NpcAlias replacement =
                NpcAlias.parse("30000000-0000-0000-0000-000000000002");
        CompanionProfileProjectionState rotated =
                state(replacement, "Second", -80);
        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                projection.apply(event(
                        3,
                        new CompanionProfileProjectionChange(
                                CompanionProfileProjectionChange.Source.ALIAS,
                                PROFILE,
                                1,
                                initial,
                                rotated,
                                -80
                        )
                ))
        );

        assertTrue(projection.find(ALIAS).isEmpty());
        assertEquals(
                rotated,
                projection.findKnownAlias(ALIAS).orElseThrow()
        );
        assertEquals(rotated, projection.find(replacement).orElseThrow());
        assertEquals("Second", projection.find(PROFILE)
                .orElseThrow().displayName());
    }

    private CompanionProfileProjectionState state(
            NpcAlias alias,
            String displayName,
            long updatedAtMs
    ) {
        return new CompanionProfileProjectionState(
                PROFILE,
                alias,
                LifecycleState.CAPTURED,
                OWNER,
                "Owner",
                "role",
                displayName,
                null,
                true,
                null,
                null,
                Set.of(UUID.fromString(
                        "40000000-0000-0000-0000-000000000001"
                )),
                Set.of(new SnapshotKind("capture")),
                updatedAtMs
        );
    }

    private ProjectionEvent event(
            long sequence,
            CompanionProfileProjectionChange change
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OperationId.parse(String.format(
                        "50000000-0000-0000-0000-%012d",
                        sequence
                )),
                CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                CompanionProfileProjectionChangeCodec.aggregateId(change),
                change.sourceRevision(),
                CompanionProfileProjectionChangeCodec.VERSION,
                CompanionProfileProjectionChangeCodec.encode(change),
                change.changedAtMs()
        );
    }
}
