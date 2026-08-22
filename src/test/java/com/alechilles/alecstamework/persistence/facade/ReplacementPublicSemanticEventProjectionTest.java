package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.PaidCommandRevivedEvent;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptResolutionEventCodec;
import com.alechilles.alecstamework.companion.capture
        .CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.capture
        .CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecycleEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionRevivalOutcome;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.companion.revival.PaidRevivalEventCodec;
import com.alechilles.alecstamework.companion.revival.PaidRevivalOutcome;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checkpointed, join-free public semantic event delivery contracts. */
class ReplacementPublicSemanticEventProjectionTest {
    private static final long EMITTED_AT = -1_500;

    @AfterEach
    void clearActivityRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void mapsCaptureV3AndPaidRevivalV2WithoutProjectionJoins() {
        ArrayList<TameworkEvent> events = new ArrayList<>();
        ReplacementPublicSemanticEventProjection projection =
                new ReplacementPublicSemanticEventProjection(
                        events::add, () -> EMITTED_AT
                );

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                projection.apply(
                        captureEvent(31),
                        ProjectionPublicationContext.LIVE_COMMIT
                )
        );
        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                projection.apply(
                        paidEvent(32),
                        ProjectionPublicationContext.LIVE_COMMIT
                )
        );

        CaptureAttemptResolvedEvent capture = assertInstanceOf(
                CaptureAttemptResolvedEvent.class, events.get(0)
        );
        PaidCommandRevivedEvent revival = assertInstanceOf(
                PaidCommandRevivedEvent.class, events.get(1)
        );
        assertEquals(
                CaptureTameAndLinkTestFixtures.ATTEMPT,
                capture.attemptId()
        );
        assertEquals(EMITTED_AT, capture.emittedAtMs());
        assertFalse(revival.recovered());
        assertEquals(EMITTED_AT, revival.emittedAtMs());
    }

    @Test
    void recoveryContextIsMappedAndReplayIsIdempotent() {
        ArrayList<TameworkEvent> events = new ArrayList<>();
        ReplacementPublicSemanticEventProjection projection =
                new ReplacementPublicSemanticEventProjection(
                        events::add, () -> EMITTED_AT
                );
        ProjectionEvent event = paidEvent(41);

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                projection.apply(
                        event,
                        ProjectionPublicationContext.RECOVERY_CONVERGENCE
                )
        );
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                projection.apply(
                        event,
                        ProjectionPublicationContext.RECOVERY_CONVERGENCE
                )
        );
        assertEquals(1, events.size());
        assertTrue(((PaidCommandRevivedEvent) events.getFirst()).recovered());
    }

    @Test
    void sinkCanBeTheTameworkEventBus() throws Exception {
        TameworkEventBus bus = new TameworkEventBus(null);
        AtomicReference<PaidCommandRevivedEvent> delivered =
                new AtomicReference<>();
        try (AutoCloseable ignored = bus.subscribe(
                PaidCommandRevivedEvent.class, delivered::set
        )) {
            ReplacementPublicSemanticEventProjection projection =
                    new ReplacementPublicSemanticEventProjection(
                            bus::publishPersistenceEvent,
                            () -> EMITTED_AT
                    );
            projection.apply(
                    paidEvent(51),
                    ProjectionPublicationContext.LIVE_COMMIT
            );
        }
        assertFalse(delivered.get().recovered());
    }

    @Test
    void paidProvisionedRevivalPublishesOneV2ActivityForOneOperation() {
        ArrayList<TameworkEvent> events = new ArrayList<>();
        ArrayList<ActivityView> activities = new ArrayList<>();
        ActivityRuntime.install(
                activities::add, new ManagedActivityConfigRegistry());
        ReplacementPublicSemanticEventProjection projection =
                new ReplacementPublicSemanticEventProjection(
                        events::add, () -> EMITTED_AT);

        assertEquals(ProjectionApplyOutcome.APPLIED, projection.apply(
                paidEvent(61), ProjectionPublicationContext.LIVE_COMMIT));
        assertEquals(ProjectionApplyOutcome.APPLIED, projection.apply(
                provisionedEvent(62),
                ProjectionPublicationContext.LIVE_COMMIT));

        assertEquals(2, events.size());
        assertEquals(1, activities.size());
    }

    private ProjectionEvent captureEvent(long sequence) {
        CompanionCaptureRequest request =
                CaptureTameAndLinkTestFixtures.request();
        long resolvedAtMs = -2_000;
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                CaptureTameAndLinkTestFixtures.OPERATION,
                CaptureAttemptResolutionEventCodec.EVENT_TYPE,
                "capture-attempt:"
                        + request.resolution().attemptId(),
                1,
                CaptureAttemptResolutionEventCodec.VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        CaptureTameAndLinkTestFixtures.OPERATION,
                        new IdempotencyKey("capture-public-event"),
                        request,
                        resolvedAtMs
                ),
                resolvedAtMs
        );
    }

    private ProjectionEvent paidEvent(long sequence) {
        ProjectionEventDraft draft = PaidRevivalEventCodec.draft(
                OperationId.parse(
                        "60000000-0000-0000-0000-000000000211"
                ),
                paidOutcome()
        );
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

    private ProjectionEvent provisionedEvent(long sequence) {
        ProvisioningOrigin origin = new ProvisioningOrigin(
                "hydragon", "paid-revival-provisioned");
        ProjectionEventDraft draft =
                ProvisionedCompanionLifecycleEventCodec.revivalDraft(
                        OperationId.parse(
                                "60000000-0000-0000-0000-000000000211"),
                        new ProvisionedCompanionRevivalOutcome(
                                origin,
                                origin.profileId(),
                                OwnerId.parse(
                                        "20000000-0000-0000-0000-000000000211"),
                                "Tamed_Wyvern_Mini",
                                NpcAlias.parse(
                                        "30000000-0000-0000-0000-000000000211"),
                                LifecycleState.ACTIVE,
                                CompanionProvisioningProjectionStatus.ACTIVE,
                                new LifecycleRevision(6),
                                new LifecycleRevision(7),
                                -2_000L));
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                draft.operationId(),
                draft.eventType(),
                draft.aggregateId(),
                draft.aggregateRevision(),
                draft.payloadVersion(),
                draft.payloadJson(),
                draft.createdAtMs());
    }

    private PaidRevivalOutcome paidOutcome() {
        return new PaidRevivalOutcome(
                "hydragon",
                "miniwyvern-revive-7",
                OwnerId.parse(
                        "20000000-0000-0000-0000-000000000211"
                ),
                "dragon-horn",
                CommandRosterSlotId.parse(
                        "40000000-0000-0000-0000-000000000211"
                ),
                ProfileId.parse(
                        "10000000-0000-0000-0000-000000000211"
                ),
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
                List.of(new RevivalCostItem("life-essence", 3)),
                "charge-receipt",
                "spawn-receipt",
                null,
                -2_000
        );
    }
}
