package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ProvisionedCompanionDeathRecordedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionRevivedEvent;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import javax.annotation.Nonnull;

/** Maps self-contained provisioned lifecycle outbox evidence without state joins. */
public final class ProvisionedCompanionLifecyclePublishedEventMapper {
    private ProvisionedCompanionLifecyclePublishedEventMapper() {
    }

    @Nonnull
    public static ProvisionedCompanionDeathRecordedEvent mapDeath(
            @Nonnull ProjectionEvent event,
            boolean recovered,
            long emittedAtMs
    ) {
        requireType(
                event,
                ProvisionedCompanionLifecycleEventCodec.DEATH_EVENT_TYPE
        );
        ProvisionedCompanionDeathOutcome outcome =
                ProvisionedCompanionLifecycleEventCodec.decodeDeath(
                        event.payloadVersion(), event.payloadJson()
                );
        requireEnvelope(
                event,
                ProvisionedCompanionLifecycleEventCodec.deathAggregate(
                        outcome.profileId()
                ),
                outcome.newLifecycleRevision().value(),
                outcome.diedAtMs()
        );
        return new ProvisionedCompanionDeathRecordedEvent(
                event.operationId().value(),
                outcome.origin().callerNamespace(),
                outcome.origin().callerKey(),
                outcome.profileId().toString(),
                outcome.ownerId().value(),
                outcome.roleId(),
                lifecycle(outcome.lifecycle()),
                outcome.projectionStatus(),
                outcome.lastAlias().value(),
                outcome.oldLifecycleRevision().value(),
                outcome.newLifecycleRevision().value(),
                recovered,
                outcome.diedAtMs(),
                emittedAtMs
        );
    }

    @Nonnull
    public static ProvisionedCompanionRevivedEvent mapRevival(
            @Nonnull ProjectionEvent event,
            boolean recovered,
            long emittedAtMs
    ) {
        return mapRevival(event, recovered, emittedAtMs, true);
    }

    @Nonnull
    public static ProvisionedCompanionRevivedEvent mapRevival(
            @Nonnull ProjectionEvent event,
            boolean recovered,
            long emittedAtMs,
            boolean publishActivity
    ) {
        requireType(
                event,
                ProvisionedCompanionLifecycleEventCodec.REVIVED_EVENT_TYPE
        );
        ProvisionedCompanionRevivalOutcome outcome =
                ProvisionedCompanionLifecycleEventCodec.decodeRevival(
                        event.payloadVersion(), event.payloadJson()
                );
        requireEnvelope(
                event,
                ProvisionedCompanionLifecycleEventCodec.revivalAggregate(
                        outcome.profileId()
                ),
                outcome.newLifecycleRevision().value(),
                outcome.revivedAtMs()
        );
        if (publishActivity) {
            ActivityRuntime.publishRevival(
                    event.operationId().value(),
                    null,
                    outcome.ownerId().value(),
                    outcome.newAlias() == null
                            ? outcome.profileId().value()
                            : outcome.newAlias().value(),
                    outcome.roleId(),
                    outcome.profileId().toString(),
                    "provisioned",
                    outcome.lifecycle().name().toLowerCase(
                            java.util.Locale.ROOT),
                    null,
                    recovered,
                    outcome.revivedAtMs()
            );
        }
        return new ProvisionedCompanionRevivedEvent(
                event.operationId().value(),
                outcome.origin().callerNamespace(),
                outcome.origin().callerKey(),
                outcome.profileId().toString(),
                outcome.ownerId().value(),
                outcome.roleId(),
                outcome.newAlias() == null
                        ? null
                        : outcome.newAlias().value(),
                lifecycle(outcome.lifecycle()),
                outcome.projectionStatus(),
                outcome.oldLifecycleRevision().value(),
                outcome.newLifecycleRevision().value(),
                recovered,
                outcome.revivedAtMs(),
                emittedAtMs
        );
    }

    private static void requireType(
            ProjectionEvent event,
            ProjectionEventType type
    ) {
        if (event == null || !type.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "Exact provisioned lifecycle event is required"
            );
        }
    }

    private static void requireEnvelope(
            ProjectionEvent event,
            String aggregateId,
            long aggregateRevision,
            long createdAtMs
    ) {
        if (!aggregateId.equals(event.aggregateId())
                || aggregateRevision != event.aggregateRevision()
                || createdAtMs != event.createdAtMs()) {
            throw new IllegalArgumentException(
                    "Provisioned lifecycle envelope does not match payload"
            );
        }
    }

    private static PopulationCompanionLifecycle lifecycle(
            LifecycleState lifecycle
    ) {
        return switch (lifecycle) {
            case ACTIVE -> PopulationCompanionLifecycle.ACTIVE;
            case UNLOADED -> PopulationCompanionLifecycle.UNLOADED;
            case CAPTURED -> PopulationCompanionLifecycle.CAPTURED;
            case COOP -> PopulationCompanionLifecycle.COOP;
            case ROSTER_STORED ->
                    PopulationCompanionLifecycle.ROSTER_STORED;
            case PROVISIONED_DORMANT ->
                    PopulationCompanionLifecycle.PROVISIONED_DORMANT;
            case DEAD_REVIVABLE ->
                    PopulationCompanionLifecycle.DEAD_REVIVABLE;
            case LOST -> PopulationCompanionLifecycle.LOST;
            case RELEASED -> PopulationCompanionLifecycle.RELEASED;
            case UNRESOLVED ->
                    PopulationCompanionLifecycle.UNKNOWN_DORMANT;
        };
    }
}
