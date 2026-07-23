package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Idempotent after-commit bridge from canonical profile evidence to released API events.
 *
 * <p>The payload is deliberately self-contained. This consumer never performs a persistence read,
 * so it cannot deadlock the read executor or observe state newer than the event being delivered.</p>
 */
public final class CompanionProfileObserverProjection implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("public_profile_observer");

    private final Consumer<NpcProfileChangedEvent> listener;
    private final Map<String, Long> appliedRevisions = new HashMap<>();

    public CompanionProfileObserverProjection(
            @Nonnull Consumer<NpcProfileChangedEvent> listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("Profile observer listener is required");
        }
        this.listener = listener;
    }

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Profile projection event is required");
        }
        if (!CompanionProfileProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        event.payloadVersion(),
                        event.payloadJson()
                );
        if (!event.aggregateId().equals(
                CompanionProfileProjectionChangeCodec.aggregateId(change)
        ) || event.aggregateRevision() != change.sourceRevision()) {
            throw new IllegalArgumentException(
                    "profile_projection_event_identity_mismatch"
            );
        }
        long applied = appliedRevisions.getOrDefault(event.aggregateId(), -1L);
        if (applied >= event.aggregateRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        listener.accept(new NpcProfileChangedEvent(
                change.profileId().toString(),
                CompanionProfileApiMapper.diff(change.before(), change.after()),
                change.before() == null
                        ? null
                        : CompanionProfileApiMapper.map(change.before()),
                change.after() == null
                        ? null
                        : CompanionProfileApiMapper.map(change.after()),
                change.changedAtMs()
        ));
        appliedRevisions.put(event.aggregateId(), event.aggregateRevision());
        return ProjectionApplyOutcome.APPLIED;
    }
}
