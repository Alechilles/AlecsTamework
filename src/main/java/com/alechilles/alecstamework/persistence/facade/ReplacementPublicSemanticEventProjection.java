package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptPublicEventMapper;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptResolutionEventCodec;
import com.alechilles.alecstamework.companion.capture
        .CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.companion.command
        .CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command
        .CommandRosterMembershipPublishedEventMapper;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonPublishedEventMapper;
import com.alechilles.alecstamework.companion.revival
        .PaidRevivalEventCodec;
import com.alechilles.alecstamework.companion.revival
        .PaidRevivalPublishedEventMapper;
import com.alechilles.alecstamework.companion.provisioning
        .ProvisionedCompanionLifecycleEventCodec;
import com.alechilles.alecstamework.companion.provisioning
        .ProvisionedCompanionLifecyclePublishedEventMapper;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import java.util.function.LongSupplier;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Single checkpointed bridge from self-contained durable feature events to
 * the public Tamework event stream.
 *
 * <p>This consumer performs no canonical reads and has no dependency on other
 * projection consumers. Recovery is supplied explicitly by the publication
 * path rather than inferred from event or operation state.</p>
 */
public final class ReplacementPublicSemanticEventProjection
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("public_feature_event_observer");

    private final ReplacementPublicApiEventSink sink;
    private final LongSupplier clock;
    private long appliedThroughSequence;
    private UUID lastRevivalActivityOperationId;

    public ReplacementPublicSemanticEventProjection(
            @Nonnull ReplacementPublicApiEventSink sink,
            @Nonnull LongSupplier clock
    ) {
        if (sink == null || clock == null) {
            throw new IllegalArgumentException(
                    "Public semantic event dependencies are required"
            );
        }
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    @Nonnull
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.allEvents();
    }

    @Override
    @Nonnull
    public ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) {
        return apply(event, ProjectionPublicationContext.LIVE_COMMIT);
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event,
            @Nonnull ProjectionPublicationContext context
    ) {
        if (event == null || context == null) {
            throw new IllegalArgumentException(
                    "Public semantic projection event and context are required"
            );
        }
        if (event.sequence().value() <= appliedThroughSequence) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        TameworkEvent mapped = map(event, context);
        if (mapped == null) {
            appliedThroughSequence = event.sequence().value();
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        sink.publish(mapped);
        appliedThroughSequence = event.sequence().value();
        return ProjectionApplyOutcome.APPLIED;
    }

    private TameworkEvent map(
            ProjectionEvent event,
            ProjectionPublicationContext context
    ) {
        long emittedAtMs = clock.getAsLong();
        if (CaptureAttemptResolutionEventCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return mapCapture(event, context, emittedAtMs);
        }
        if (PaidRevivalEventCodec.EVENT_TYPE.equals(event.eventType())) {
            return PaidRevivalPublishedEventMapper.map(
                    event,
                    context == ProjectionPublicationContext
                            .RECOVERY_CONVERGENCE,
                    emittedAtMs,
                    claimRevivalActivity(event)
            );
        }
        if (CommandRosterMembershipChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return CommandRosterMembershipPublishedEventMapper.map(
                    event, emittedAtMs
            );
        }
        if (TimedSummonLeaseChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return TimedSummonPublishedEventMapper.map(
                    event, emittedAtMs
            );
        }
        boolean recovered = context == ProjectionPublicationContext
                .RECOVERY_CONVERGENCE;
        if (ProvisionedCompanionLifecycleEventCodec.DEATH_EVENT_TYPE
                .equals(event.eventType())) {
            return ProvisionedCompanionLifecyclePublishedEventMapper
                    .mapDeath(event, recovered, emittedAtMs);
        }
        if (ProvisionedCompanionLifecycleEventCodec.REVIVED_EVENT_TYPE
                .equals(event.eventType())) {
            return ProvisionedCompanionLifecyclePublishedEventMapper
                    .mapRevival(
                            event, recovered, emittedAtMs,
                            claimRevivalActivity(event));
        }
        return null;
    }

    private boolean claimRevivalActivity(ProjectionEvent event) {
        UUID operationId = event.operationId().value();
        if (operationId.equals(lastRevivalActivityOperationId)) {
            return false;
        }
        lastRevivalActivityOperationId = operationId;
        return true;
    }

    private TameworkEvent mapCapture(
            ProjectionEvent event,
            ProjectionPublicationContext context,
            long emittedAtMs
    ) {
        if (event.payloadVersion()
                != CaptureAttemptResolutionEventCodec.VERSION) {
            throw new IllegalArgumentException(
                    "capture_attempt_public_event_requires_version_three"
            );
        }
        CaptureAttemptResolvedEvent resolved =
                CaptureAttemptResolutionEventCodec.decodeEvent(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!resolved.replayComplete()
                || !event.operationId().equals(resolved.operationId())
                || !event.aggregateId().equals(
                        "capture-attempt:"
                                + resolved.resolution().attemptId()
                )
                || event.aggregateRevision() != 1L
                || event.createdAtMs() != resolved.resolvedAtMs()) {
            throw new IllegalArgumentException(
                    "capture_attempt_public_event_envelope_mismatch"
            );
        }
        if (context == ProjectionPublicationContext.LIVE_COMMIT) {
            CaptureAttemptPublicEventMapper.publishTameActivity(resolved);
        }
        return CaptureAttemptPublicEventMapper.map(resolved, emittedAtMs);
    }
}
