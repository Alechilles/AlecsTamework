package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.api.PaidCommandRevivedEvent;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Self-contained paid-revival outbox replay and public mapping contracts. */
class PaidRevivalPublishedEventMapperTest {
    private static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000211"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000211"
    );
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000211"
    );
    private static final long REVIVED_AT = -2_000;

    @AfterEach
    void clearActivityRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void durableEventMapsWithoutCanonicalReadJoin() {
        List<ActivityView> activities = new ArrayList<>();
        ActivityRuntime.install(
                activities::add,
                new ManagedActivityConfigRegistry(
                        new PopulationGroupConfigRegistry()));
        PaidRevivalOutcome expected = outcome();
        ProjectionEvent event = committed(
                PaidRevivalEventCodec.draft(OPERATION, expected)
        );

        PaidCommandRevivedEvent mapped =
                PaidRevivalPublishedEventMapper.map(
                        event, true, -1_900
                );

        assertEquals(expected, PaidRevivalEventCodec.decode(
                event.payloadVersion(), event.payloadJson()
        ));
        assertEquals(OPERATION.value(), mapped.operationId());
        assertEquals("hydragon", mapped.callerNamespace());
        assertEquals(
                "miniwyvern-revive-7", mapped.idempotencyKey()
        );
        assertEquals(OWNER.value(), mapped.ownerUuid());
        assertEquals(PROFILE.toString(), mapped.profileId());
        assertEquals("dragon-horn", mapped.commandFamilyId());
        assertEquals(
                List.of(
                        new ItemCostComponentView("life-essence", 3),
                        new ItemCostComponentView("gold-bar", 2)
                ),
                mapped.exactCost()
        );
        assertTrue(mapped.recovered());
        assertEquals(REVIVED_AT, mapped.revivedAtMs());
        assertEquals(-1_900, mapped.emittedAtMs());
        RevivalActivityView activity = assertInstanceOf(
                RevivalActivityView.class, activities.getFirst());
        assertEquals(OPERATION.value(), activity.header().operationId());
        assertEquals(OWNER.value(), activity.actorId());
        assertEquals(OWNER.value(), activity.ownerId());
        assertEquals(expected.liveAlias().value(), activity.companionId());
        assertEquals(PROFILE.toString(), activity.profileId());
        assertEquals("paid_command", activity.revivalSource());
        assertEquals("active", activity.resultingLifecycleState());
        assertEquals("settled", activity.paymentOutcome());
        assertTrue(activity.recovered());
    }

    @Test
    void mapperRejectsEnvelopeThatDisagreesWithPayload() {
        ProjectionEvent valid = committed(
                PaidRevivalEventCodec.draft(OPERATION, outcome())
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
                () -> PaidRevivalPublishedEventMapper.map(
                        mismatched, false, -1_900
                )
        );
    }

    @Test
    void legacyPartialEventPayloadIsRejectedInsteadOfJoined() {
        assertEquals(2, PaidRevivalEventCodec.VERSION);
        assertThrows(
                IllegalArgumentException.class,
                () -> PaidRevivalEventCodec.decode(1, "{}")
        );
    }

    private PaidRevivalOutcome outcome() {
        return new PaidRevivalOutcome(
                "hydragon",
                "miniwyvern-revive-7",
                OWNER,
                "dragon-horn",
                CommandRosterSlotId.parse(
                        "40000000-0000-0000-0000-000000000211"
                ),
                PROFILE,
                SnapshotId.parse(
                        "50000000-0000-0000-0000-000000000211"
                ),
                NpcAlias.parse(
                        "30000000-0000-0000-0000-000000000211"
                ),
                "world-target",
                new LifecycleRevision(7),
                "miniwyvern-revive",
                "sha256:recipe-revision",
                List.of(
                        new RevivalCostItem("life-essence", 3),
                        new RevivalCostItem("gold-bar", 2)
                ),
                "charge-receipt",
                "spawn-receipt",
                null,
                REVIVED_AT
        );
    }

    private ProjectionEvent committed(ProjectionEventDraft draft) {
        return new ProjectionEvent(
                new ProjectionSequence(41),
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
