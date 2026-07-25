package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps one timed outbox payload to a deterministic public callback without joins. */
public final class TimedSummonPublishedEventMapper {
    private TimedSummonPublishedEventMapper() {
    }

    @Nonnull
    public static CommandTimedSummoningChangedEvent map(
            @Nonnull ProjectionEvent event,
            long emittedAtMs
    ) {
        if (event == null || !TimedSummonLeaseChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            throw new IllegalArgumentException(
                    "Timed summon projection event is required"
            );
        }
        TimedSummonLeaseChangeEvidence evidence =
                TimedSummonLeaseChangeCodec.decodeEvidence(
                        event.payloadVersion(), event.payloadJson()
                );
        requireMatchingEnvelope(event, evidence);
        return new CommandTimedSummoningChangedEvent(
                view(
                        evidence.leaseChange().before(),
                        evidence,
                        evidence.previousLifecycleState(),
                        event.createdAtMs()
                ),
                view(
                        evidence.leaseChange().after(),
                        evidence,
                        evidence.currentLifecycleState(),
                        event.createdAtMs()
                ),
                evidence.reason().publicValue(),
                event.createdAtMs(),
                emittedAtMs
        );
    }

    /** Maps synchronous delivery with emitted time equal to durable time. */
    @Nonnull
    public static CommandTimedSummoningChangedEvent map(
            @Nonnull ProjectionEvent event
    ) {
        return map(event, event.createdAtMs());
    }

    private static void requireMatchingEnvelope(
            ProjectionEvent event,
            TimedSummonLeaseChangeEvidence evidence
    ) {
        TimedSummonLease after = evidence.leaseChange().after();
        if (!event.aggregateId().equals(after.profileId().toString())
                || event.aggregateRevision() != after.leaseRevision()
                || event.createdAtMs() != after.updatedAtMs()) {
            throw new IllegalArgumentException(
                    "Timed summon projection envelope does not match payload"
            );
        }
    }

    @Nullable
    private static CommandTimedSummoningView view(
            @Nullable TimedSummonLease lease,
            TimedSummonLeaseChangeEvidence evidence,
            @Nullable LifecycleState lifecycleState,
            long occurredAtMs
    ) {
        if (lease == null) {
            return null;
        }
        if (lifecycleState == null) {
            throw new IllegalArgumentException(
                    "Timed summon lifecycle state is required"
            );
        }
        return new CommandTimedSummoningView(
                evidence.membership().familyKey().ownerId().value(),
                evidence.membership().familyKey().familyId(),
                lease.profileId().toString(),
                lease.leaseRevision(),
                state(lifecycleState),
                lease.sessionId() == null
                        ? null
                        : lease.sessionId().toString(),
                remaining(lease, occurredAtMs),
                lease.activeSession() && lease.policy().unlimited(),
                lease.cooldownUntilMs() == null
                        ? 0L
                        : lease.cooldownUntilMs(),
                lease.updatedAtMs()
        );
    }

    private static Long remaining(
            TimedSummonLease lease,
            long nowMs
    ) {
        if (!lease.activeSession() || lease.remainingMs() == null) {
            return null;
        }
        long elapsed;
        try {
            elapsed = Math.max(
                    0L,
                    Math.subtractExact(nowMs, lease.checkpointedAtMs())
            );
        } catch (ArithmeticException overflow) {
            elapsed = nowMs >= lease.checkpointedAtMs()
                    ? Long.MAX_VALUE
                    : 0L;
        }
        return Math.max(0L, lease.remainingMs() - Math.min(
                lease.remainingMs(), elapsed
        ));
    }

    private static CommandTimedSummoningState state(
            LifecycleState state
    ) {
        return switch (state) {
            case ACTIVE -> CommandTimedSummoningState.ACTIVE;
            case UNLOADED, CAPTURED, COOP, RELEASED, UNRESOLVED ->
                    CommandTimedSummoningState.UNLOADED;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandTimedSummoningState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandTimedSummoningState.DEAD_REVIVABLE;
            case LOST -> CommandTimedSummoningState.LOST;
        };
    }
}
