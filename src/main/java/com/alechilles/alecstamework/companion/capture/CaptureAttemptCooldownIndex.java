package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Rebuildable actor/config cooldown view sourced only from capture outbox events. */
public final class CaptureAttemptCooldownIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("capture_attempt_cooldown_index");

    private final Map<Key, CaptureAttemptCooldown> cooldowns =
            new HashMap<>();

    @Override
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.events(Set.of(
                CaptureAttemptResolutionEventCodec.EVENT_TYPE
        ));
    }

    @Override
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Capture cooldown projection event is required"
            );
        }
        if (!CaptureAttemptResolutionEventCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        CaptureAttemptResolvedEvent resolved =
                CaptureAttemptResolutionEventCodec.decodeEvent(
                        event.payloadVersion(), event.payloadJson()
                );
        requireMatchingAggregate(event, resolved.resolution());
        Long until = resolved.resolution().failureCooldownUntilMs();
        if (until == null) {
            return ProjectionApplyOutcome.APPLIED;
        }
        Key key = new Key(
                resolved.actorUuid(),
                resolved.resolution().formula().itemConfigId()
        );
        CaptureAttemptCooldown current = cooldowns.get(key);
        if (current != null
                && current.projectionSequence()
                >= event.sequence().value()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        cooldowns.put(key, new CaptureAttemptCooldown(
                key.actorUuid(),
                key.itemConfigId(),
                resolved.resolution().attemptId(),
                until,
                event.sequence().value()
        ));
        return ProjectionApplyOutcome.APPLIED;
    }

    @Nonnull
    public synchronized Optional<CaptureAttemptCooldown> active(
            @Nonnull UUID actorUuid,
            @Nonnull String itemConfigId,
            @Nonnull UUID currentAttemptId,
            long nowMs
    ) {
        if (actorUuid == null || currentAttemptId == null
                || itemConfigId == null || itemConfigId.isBlank()) {
            throw new IllegalArgumentException(
                    "Capture cooldown query evidence is required"
            );
        }
        CaptureAttemptCooldown cooldown = cooldowns.get(
                new Key(actorUuid, itemConfigId.trim())
        );
        return cooldown == null
                || cooldown.attemptId().equals(currentAttemptId)
                || !cooldown.activeAt(nowMs)
                ? Optional.empty()
                : Optional.of(cooldown);
    }

    private void requireMatchingAggregate(
            ProjectionEvent event,
            CaptureAttemptResolution resolution
    ) {
        if (!event.aggregateId().equals(
                "capture-attempt:" + resolution.attemptId()
        ) || event.aggregateRevision() != 1L) {
            throw new IllegalArgumentException(
                    "capture_attempt_cooldown_event_mismatch"
            );
        }
    }

    private record Key(UUID actorUuid, String itemConfigId) {
    }
}
