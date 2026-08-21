package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityConsumeResult;
import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.ActivityFeedStatus;
import com.alechilles.alecstamework.api.ActivityFeedSubscription;
import com.alechilles.alecstamework.api.SuccessfulActivityConsumer;
import com.alechilles.alecstamework.api.SuccessfulActivityView;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/**
 * Process-local successful-activity feed used by the live Tamework
 * composition.
 *
 * <p>The feed does not retain events. It invokes active consumers when a
 * publisher submits an activity and isolates callback failures from other
 * consumers and the publisher. A consumer can receive an activity more than
 * once when the caller retries a live action; consumers must keep their own
 * durable boundary when that distinction matters.</p>
 */
public final class LiveActivityFeed implements ActivityFeedApi, AutoCloseable {
    private static final String DETAIL = "activity-feed-live";

    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, SubscriptionState> subscriptions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Returns whether this feed still accepts live subscriptions and events. */
    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * Publishes one activity to the consumers active at the time of the call.
     * The supplied sequence is ignored; this feed owns the process-local
     * sequence and assigns it to the delivered view.
     */
    public void publish(@Nonnull SuccessfulActivityView activity) {
        Objects.requireNonNull(activity, "activity");
        if (closed.get()) {
            return;
        }
        SuccessfulActivityView sequenced = withNextSequence(activity);
        for (SubscriptionState subscription : subscriptions.values()) {
            subscription.deliver(sequenced);
        }
    }

    /**
     * Returns a narrow publisher seam for internal runtime integrations.
     * The seam does not expose subscription management.
     */
    @Nonnull
    public Publisher publisher() {
        return this::publish;
    }

    @Override
    @Nonnull
    public ActivityFeedSubscription subscribe(
            @Nonnull String consumerId,
            @Nonnull SuccessfulActivityConsumer consumer
    ) {
        String id = requireConsumerId(consumerId);
        Objects.requireNonNull(consumer, "consumer");
        if (closed.get()) {
            throw new IllegalStateException("Activity feed is closed.");
        }
        SubscriptionState state = new SubscriptionState(id, consumer);
        if (subscriptions.putIfAbsent(id, state) != null) {
            throw new IllegalStateException(
                    "Activity consumer is already subscribed: " + id
            );
        }
        if (closed.get() && subscriptions.remove(id, state)) {
            throw new IllegalStateException("Activity feed is closed.");
        }
        return state;
    }

    @Override
    @Nonnull
    public ActivityFeedStatus status(@Nonnull String consumerId) {
        String id = requireConsumerId(consumerId);
        SubscriptionState state = subscriptions.get(id);
        if (closed.get()) {
            return ActivityFeedStatus.unavailable();
        }
        return new ActivityFeedStatus(
                true,
                state != null && state.isOpen(),
                state == null ? 0L : state.lastDeliveredSequence(),
                DETAIL
        );
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (SubscriptionState state : subscriptions.values()) {
                state.close();
            }
            subscriptions.clear();
        }
    }

    private SuccessfulActivityView withNextSequence(
            SuccessfulActivityView activity
    ) {
        long assigned = sequence.incrementAndGet();
        return new SuccessfulActivityView(
                activity.operationId(),
                assigned,
                activity.ownerId(),
                activity.companionId(),
                activity.sourceRoleId(),
                activity.groupIds(),
                activity.profileId(),
                activity.activityId(),
                activity.itemQuantities(),
                activity.committedAt()
        );
    }

    private static String requireConsumerId(String consumerId) {
        String normalized = Objects.requireNonNull(consumerId, "consumerId")
                .trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("consumerId is required.");
        }
        return normalized;
    }

    /** Narrow internal seam for publishing live activity views. */
    @FunctionalInterface
    public interface Publisher {
        /** Publishes one live activity view. */
        void publish(@Nonnull SuccessfulActivityView activity);
    }

    private final class SubscriptionState
            implements ActivityFeedSubscription {
        private final String consumerId;
        private final SuccessfulActivityConsumer consumer;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicLong lastDeliveredSequence = new AtomicLong();

        private SubscriptionState(
                String consumerId,
                SuccessfulActivityConsumer consumer
        ) {
            this.consumerId = consumerId;
            this.consumer = consumer;
        }

        private void deliver(SuccessfulActivityView activity) {
            if (!active.get() || closed.get()) {
                return;
            }
            lastDeliveredSequence.set(activity.globalSequence());
            try {
                CompletionStage<ActivityConsumeResult> result =
                        consumer.consume(activity);
                if (result != null) {
                    result.whenComplete((ignored, failure) -> {
                        // Live delivery has no checkpoint or retry authority.
                        // Completion is intentionally observed only to isolate
                        // exceptional stages from the publisher thread.
                    });
                }
            } catch (Throwable ignored) {
                // One consumer must not prevent other live consumers from
                // receiving the same activity.
            }
        }

        private boolean isOpen() {
            return active.get() && !closed.get();
        }

        private long lastDeliveredSequence() {
            return lastDeliveredSequence.get();
        }

        @Override
        @Nonnull
        public String consumerId() {
            return consumerId;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                subscriptions.remove(consumerId, this);
            }
        }
    }
}
