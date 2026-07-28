package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionDeathRecordedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionRevivedEvent;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicSemanticEventProjection;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Codec, envelope, and compatibility contracts for provisioned lifecycle events. */
class ProvisionedCompanionLifecycleEventCodecTest {
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("hydragon", "soul-bond:owner");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final NpcAlias OLD_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias NEW_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");

    @Test
    void deathRoundTripMapsEverySelfContainedPublicFact() {
        ProvisionedCompanionDeathOutcome outcome =
                new ProvisionedCompanionDeathOutcome(
                        ORIGIN,
                        ORIGIN.profileId(),
                        OWNER,
                        "Tamed_Wyvern_Mini",
                        OLD_ALIAS,
                        LifecycleState.DEAD_REVIVABLE,
                        CompanionProvisioningProjectionStatus.UNAVAILABLE,
                        new LifecycleRevision(4),
                        new LifecycleRevision(5),
                        -3_000
                );
        ProjectionEvent event = committed(
                ProvisionedCompanionLifecycleEventCodec.deathDraft(
                        OPERATION, outcome
                )
        );

        assertEquals(
                outcome,
                ProvisionedCompanionLifecycleEventCodec.decodeDeath(
                        event.payloadVersion(), event.payloadJson()
                )
        );
        var mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapDeath(
                        event, true, -2_900
                );
        assertEquals(ORIGIN.callerNamespace(), mapped.callerNamespace());
        assertEquals(ORIGIN.callerKey(), mapped.provisioningKey());
        assertEquals(ORIGIN.profileId().toString(), mapped.profileId());
        assertEquals(OWNER.value(), mapped.ownerUuid());
        assertEquals(OLD_ALIAS.value(), mapped.lastNpcUuid());
        assertEquals(
                PopulationCompanionLifecycle.DEAD_REVIVABLE,
                mapped.lifecycle()
        );
        assertEquals(
                CompanionProvisioningProjectionStatus.UNAVAILABLE,
                mapped.projectionStatus()
        );
        assertTrue(mapped.recovered());
        assertEquals(-3_000, mapped.diedAtMs());
    }

    @Test
    void revivalRoundTripMapsEverySelfContainedPublicFact() {
        ProvisionedCompanionRevivalOutcome outcome =
                new ProvisionedCompanionRevivalOutcome(
                        ORIGIN,
                        ORIGIN.profileId(),
                        OWNER,
                        "Tamed_Wyvern_Mini",
                        NEW_ALIAS,
                        LifecycleState.ACTIVE,
                        CompanionProvisioningProjectionStatus.ACTIVE,
                        new LifecycleRevision(5),
                        new LifecycleRevision(7),
                        -2_000
                );
        ProjectionEvent event = committed(
                ProvisionedCompanionLifecycleEventCodec.revivalDraft(
                        OPERATION, outcome
                )
        );

        assertEquals(
                outcome,
                ProvisionedCompanionLifecycleEventCodec.decodeRevival(
                        event.payloadVersion(), event.payloadJson()
                )
        );
        var mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapRevival(
                        event, false, -1_900
                );
        assertEquals(NEW_ALIAS.value(), mapped.newNpcUuid());
        assertEquals(PopulationCompanionLifecycle.ACTIVE, mapped.lifecycle());
        assertEquals(
                CompanionProvisioningProjectionStatus.ACTIVE,
                mapped.projectionStatus()
        );
        assertEquals(5, mapped.oldProfileRevision());
        assertEquals(7, mapped.newProfileRevision());
        assertEquals(-2_000, mapped.revivedAtMs());
    }

    @Test
    void dormantRevivalCarriesNoAliasAndReportsNoProjectionRequested() {
        ProvisionedCompanionRevivalOutcome outcome =
                new ProvisionedCompanionRevivalOutcome(
                        ORIGIN,
                        ORIGIN.profileId(),
                        OWNER,
                        "Tamed_Wyvern_Mini",
                        null,
                        LifecycleState.PROVISIONED_DORMANT,
                        CompanionProvisioningProjectionStatus.NOT_REQUESTED,
                        new LifecycleRevision(5),
                        new LifecycleRevision(6),
                        -2_000
                );
        ProjectionEvent event = committed(
                ProvisionedCompanionLifecycleEventCodec.revivalDraft(
                        OPERATION, outcome
                )
        );

        assertEquals(
                outcome,
                ProvisionedCompanionLifecycleEventCodec.decodeRevival(
                        event.payloadVersion(), event.payloadJson()
                )
        );
        ProvisionedCompanionRevivedEvent mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapRevival(
                        event, true, -1_900
                );
        assertNull(mapped.newNpcUuid());
        assertEquals(
                PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                mapped.lifecycle()
        );
        assertEquals(
                CompanionProvisioningProjectionStatus.NOT_REQUESTED,
                mapped.projectionStatus()
        );
        assertTrue(mapped.recovered());
    }

    @Test
    void strictDecodeAndMapperRejectMalformedOrMismatchedEvidence() {
        ProvisionedCompanionDeathOutcome outcome =
                new ProvisionedCompanionDeathOutcome(
                        ORIGIN,
                        ORIGIN.profileId(),
                        OWNER,
                        "Tamed_Wyvern_Mini",
                        OLD_ALIAS,
                        LifecycleState.DEAD_REVIVABLE,
                        CompanionProvisioningProjectionStatus.UNAVAILABLE,
                        new LifecycleRevision(1),
                        new LifecycleRevision(2),
                        -100
                );
        ProjectionEventDraft draft =
                ProvisionedCompanionLifecycleEventCodec.deathDraft(
                        OPERATION, outcome
                );
        JsonObject extra = JsonParser.parseString(
                draft.payloadJson()
        ).getAsJsonObject();
        extra.addProperty("unexpected", true);

        assertThrows(
                IllegalArgumentException.class,
                () -> ProvisionedCompanionLifecycleEventCodec.decodeDeath(
                        2, draft.payloadJson()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ProvisionedCompanionLifecycleEventCodec.decodeDeath(
                        draft.payloadVersion(), extra.toString()
                )
        );
        ProjectionEvent wrongEnvelope = new ProjectionEvent(
                new ProjectionSequence(1),
                draft.operationId(),
                draft.eventType(),
                draft.aggregateId() + "-wrong",
                draft.aggregateRevision(),
                draft.payloadVersion(),
                draft.payloadJson(),
                draft.createdAtMs()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ProvisionedCompanionLifecyclePublishedEventMapper
                        .mapDeath(wrongEnvelope, false, -90)
        );
    }

    @Test
    void originalPublicConstructorsRemainSourceCompatible() {
        var death = new com.alechilles.alecstamework.api
                .ProvisionedCompanionDeathRecordedEvent(
                OPERATION.value(),
                ORIGIN.callerNamespace(),
                ORIGIN.callerKey(),
                ORIGIN.profileId().toString(),
                OWNER.value(),
                "Tamed_Wyvern_Mini",
                OLD_ALIAS.value(),
                1,
                2,
                false,
                -100,
                -90
        );
        var revived = new com.alechilles.alecstamework.api
                .ProvisionedCompanionRevivedEvent(
                OPERATION.value(),
                ORIGIN.callerNamespace(),
                ORIGIN.callerKey(),
                ORIGIN.profileId().toString(),
                OWNER.value(),
                "Tamed_Wyvern_Mini",
                NEW_ALIAS.value(),
                PopulationCompanionLifecycle.ACTIVE,
                2,
                3,
                false,
                -80,
                -70
        );

        assertEquals(
                CompanionProvisioningProjectionStatus.UNAVAILABLE,
                death.projectionStatus()
        );
        assertEquals(
                CompanionProvisioningProjectionStatus.ACTIVE,
                revived.projectionStatus()
        );
    }

    @Test
    void checkpointedObserverRoutesBothLifecycleEventsAsRecovered() {
        ArrayList<com.alechilles.alecstamework.api.TameworkEvent> events =
                new ArrayList<>();
        ReplacementPublicSemanticEventProjection observer =
                new ReplacementPublicSemanticEventProjection(
                        events::add, () -> -1_900
                );
        ProvisionedCompanionDeathOutcome death =
                new ProvisionedCompanionDeathOutcome(
                        ORIGIN, ORIGIN.profileId(), OWNER,
                        "Tamed_Wyvern_Mini", OLD_ALIAS,
                        LifecycleState.DEAD_REVIVABLE,
                        CompanionProvisioningProjectionStatus.UNAVAILABLE,
                        new LifecycleRevision(4),
                        new LifecycleRevision(5),
                        -3_000
                );
        ProvisionedCompanionRevivalOutcome revival =
                new ProvisionedCompanionRevivalOutcome(
                        ORIGIN, ORIGIN.profileId(), OWNER,
                        "Tamed_Wyvern_Mini", NEW_ALIAS,
                        LifecycleState.ACTIVE,
                        CompanionProvisioningProjectionStatus.ACTIVE,
                        new LifecycleRevision(5),
                        new LifecycleRevision(7),
                        -2_000
                );

        observer.apply(
                committed(
                        ProvisionedCompanionLifecycleEventCodec.deathDraft(
                                OPERATION, death
                        ),
                        1
                ),
                ProjectionPublicationContext.RECOVERY_CONVERGENCE
        );
        observer.apply(
                committed(
                        ProvisionedCompanionLifecycleEventCodec.revivalDraft(
                                OPERATION, revival
                        ),
                        2
                ),
                ProjectionPublicationContext.RECOVERY_CONVERGENCE
        );

        assertTrue(events.get(0) instanceof
                ProvisionedCompanionDeathRecordedEvent);
        assertTrue(((ProvisionedCompanionDeathRecordedEvent) events.get(0))
                .recovered());
        assertTrue(events.get(1) instanceof
                ProvisionedCompanionRevivedEvent);
        assertTrue(((ProvisionedCompanionRevivedEvent) events.get(1))
                .recovered());
    }

    private ProjectionEvent committed(ProjectionEventDraft draft) {
        return committed(draft, 1);
    }

    private ProjectionEvent committed(
            ProjectionEventDraft draft,
            long sequence
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
