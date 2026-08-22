package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityIds;
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
        // The payload owns domain time. Early replacement writers used the
        // later transaction time in the envelope, which is storage metadata.
        long occurredAtMs = evidence.leaseChange().after().updatedAtMs();
        CommandTimedSummoningChangedEvent mapped =
                new CommandTimedSummoningChangedEvent(
                view(
                        evidence.leaseChange().before(),
                        evidence,
                        evidence.previousLifecycleState(),
                        occurredAtMs
                ),
                view(
                        evidence.leaseChange().after(),
                        evidence,
                        evidence.currentLifecycleState(),
                        occurredAtMs
                ),
                evidence.reason().publicValue(),
                occurredAtMs,
                emittedAtMs
        );
        publishActivity(event, evidence, occurredAtMs);
        return mapped;
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
                || event.aggregateRevision() != after.leaseRevision()) {
            throw new IllegalArgumentException(
                    "Timed summon projection envelope does not match payload"
            );
        }
    }

    private static void publishActivity(
            ProjectionEvent event,
            TimedSummonLeaseChangeEvidence evidence,
            long occurredAtMs
    ) {
        String actionId = actionId(evidence, occurredAtMs);
        if (actionId == null) {
            return;
        }
        TimedSummonLease relevant = ActivityIds.SUMMON_SUCCESS.equals(actionId)
                ? evidence.leaseChange().after()
                : evidence.leaseChange().before();
        ActivityRuntime.publishSummoning(
                event.operationId().value(),
                actionId,
                evidence.membership().familyKey().ownerId().value(),
                evidence.leaseChange().after().profileId().toString(),
                evidence.membership().familyKey().familyId(),
                evidence.leaseChange().after().profileId().value(),
                evidence.reason().publicValue(),
                expiresAt(relevant)
        );
    }

    @Nullable
    private static String actionId(
            TimedSummonLeaseChangeEvidence evidence,
            long occurredAtMs
    ) {
        if (evidence.reason()
                == TimedSummonLeaseChangeEvidence.Reason.SUMMON_STARTED) {
            return ActivityIds.SUMMON_SUCCESS;
        }
        if (evidence.reason()
                != TimedSummonLeaseChangeEvidence.Reason.STORED
                || evidence.leaseChange().before() == null) {
            return null;
        }
        TimedSummonLease before = evidence.leaseChange().before();
        Long remaining = remaining(before, occurredAtMs);
        return remaining != null && remaining == 0L
                ? ActivityIds.SUMMON_EXPIRED
                : ActivityIds.RECALL;
    }

    @Nullable
    private static Long expiresAt(@Nullable TimedSummonLease lease) {
        if (lease == null || !lease.activeSession()
                || lease.remainingMs() == null
                || lease.checkpointedAtMs() == null) {
            return null;
        }
        return TimedSummonTime.saturatingAdd(
                lease.checkpointedAtMs(), lease.remainingMs());
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
            case UNLOADED, CAPTURED, COOP, RELEASED ->
                    CommandTimedSummoningState.UNLOADED;
            case UNRESOLVED -> CommandTimedSummoningState.UNAVAILABLE;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandTimedSummoningState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandTimedSummoningState.DEAD_REVIVABLE;
            case LOST -> CommandTimedSummoningState.LOST;
        };
    }
}
