package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityConsumer;
import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.ActivityFeedStatus;
import com.alechilles.alecstamework.api.ActivityFeedSubscription;
import com.alechilles.alecstamework.api.ActivityFilter;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.api.SuccessfulActivityView;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/** Process-local, filtered, best-effort Activity API V2 feed. */
public final class LiveActivityFeed implements ActivityFeedApi, AutoCloseable {
    private static final String DETAIL = "activity-api-v2-live";

    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, SubscriptionState> subscriptions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile InterestSnapshot interest = InterestSnapshot.empty();

    /** Returns whether this feed accepts subscriptions and activities. */
    public boolean isOpen() {
        return !closed.get();
    }

    /** Returns whether any active consumer is interested in this domain/action pair. */
    public boolean hasInterest(
            @Nonnull ActivityDomain domain,
            @Nonnull String actionId
    ) {
        Objects.requireNonNull(domain, "domain");
        String normalized = requireActionId(actionId);
        return !closed.get() && interest.matches(domain, normalized);
    }

    /** Publishes one typed activity after the cached interest check. */
    public void publish(@Nonnull ActivityView activity) {
        Objects.requireNonNull(activity, "activity");
        if (closed.get()) {
            return;
        }
        ActivityHeader header = Objects.requireNonNull(activity.header(), "activity.header");
        ActivityDomain domain = Objects.requireNonNull(activity.domain(), "activity.domain");
        String actionId = header.actionId();
        InterestSnapshot currentInterest = interest;
        if (!currentInterest.matches(domain, actionId)) {
            return;
        }
        ActivityView sequenced = activity.withHeader(
                header.withSequence(sequence.incrementAndGet()));
        for (SubscriptionState subscription : subscriptions.values()) {
            subscription.deliver(sequenced, domain, actionId);
        }
    }

    /** Transitional bridge for the unreleased producer while it is migrated to V2. */
    public void publish(@Nonnull SuccessfulActivityView activity) {
        Objects.requireNonNull(activity, "activity");
        try {
            publish(new ManagedActivityView(
                    new ActivityHeader(
                            activity.operationId(),
                            0L,
                            activity.activityId(),
                            activity.committedAt()
                    ),
                    activity.profileId(),
                    activity.groupIds(),
                    activity.sourceRoleId(),
                    activity.ownerId(),
                    activity.companionId(),
                    activity.activityId(),
                    activity.itemQuantities(),
                    java.util.List.of(),
                    null,
                    null
            ));
        } catch (IllegalArgumentException ignored) {
            // The old producer is removed with its unreleased contract.
        }
    }

    /** Returns the narrow internal publisher seam. */
    @Nonnull
    public Publisher publisher() {
        return new Publisher() {
            @Override
            public void publish(@Nonnull ActivityView activity) {
                LiveActivityFeed.this.publish(activity);
            }

            @Override
            public void publish(@Nonnull SuccessfulActivityView activity) {
                LiveActivityFeed.this.publish(activity);
            }
        };
    }

    @Override
    @Nonnull
    public ActivityFeedSubscription subscribe(
            @Nonnull String consumerId,
            @Nonnull ActivityFilter filter,
            @Nonnull ActivityConsumer consumer
    ) {
        String id = requireConsumerId(consumerId);
        ActivityFilter normalizedFilter = Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(consumer, "consumer");
        if (closed.get()) {
            throw new IllegalStateException("Activity feed is closed.");
        }
        SubscriptionState state = new SubscriptionState(id, normalizedFilter, consumer);
        if (subscriptions.putIfAbsent(id, state) != null) {
            throw new IllegalStateException(
                    "Activity consumer is already subscribed: " + id);
        }
        rebuildInterestIndex();
        if (closed.get() && subscriptions.remove(id, state)) {
            rebuildInterestIndex();
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
                state == null ? 0L : state.lastAttemptedSequence(),
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
            interest = InterestSnapshot.empty();
        }
    }

    private void rebuildInterestIndex() {
        EnumMap<ActivityDomain, InterestDomain> next =
                new EnumMap<>(ActivityDomain.class);
        for (SubscriptionState state : subscriptions.values()) {
            for (ActivityDomain domain : state.filter.domains()) {
                InterestDomain domainInterest = next.computeIfAbsent(
                        domain, ignored -> new InterestDomain());
                if (state.filter.actionIds().isEmpty()) {
                    domainInterest.wildcard = true;
                } else {
                    domainInterest.actions.addAll(state.filter.actionIds());
                }
            }
        }
        EnumMap<ActivityDomain, InterestDomainSnapshot> snapshot =
                new EnumMap<>(ActivityDomain.class);
        for (Map.Entry<ActivityDomain, InterestDomain> entry : next.entrySet()) {
            snapshot.put(
                    entry.getKey(),
                    new InterestDomainSnapshot(
                            entry.getValue().wildcard,
                            Set.copyOf(entry.getValue().actions))
            );
        }
        interest = new InterestSnapshot(Collections.unmodifiableMap(snapshot));
    }

    private static String requireConsumerId(String consumerId) {
        String normalized = Objects.requireNonNull(consumerId, "consumerId").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("consumerId is required.");
        }
        return normalized;
    }

    private static String requireActionId(String actionId) {
        String normalized = Objects.requireNonNull(actionId, "actionId").trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1
                || normalized.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("actionId must be namespaced.");
        }
        return normalized;
    }

    /** Narrow internal seam for publishing typed activity views. */
    @FunctionalInterface
    public interface Publisher {
        /** Transitional overload for the unreleased producer. */
        void publish(@Nonnull SuccessfulActivityView activity);

        /** Publishes one typed activity view. */
        default void publish(@Nonnull ActivityView activity) {
            throw new UnsupportedOperationException("Use ActivityView.");
        }
    }

    private final class SubscriptionState implements ActivityFeedSubscription {
        private final String consumerId;
        private final ActivityFilter filter;
        private final ActivityConsumer consumer;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicLong lastAttemptedSequence = new AtomicLong();

        private SubscriptionState(
                String consumerId,
                ActivityFilter filter,
                ActivityConsumer consumer
        ) {
            this.consumerId = consumerId;
            this.filter = filter;
            this.consumer = consumer;
        }

        private void deliver(
                ActivityView activity,
                ActivityDomain domain,
                String actionId
        ) {
            if (!active.get() || closed.get()
                    || !filter.matches(domain, actionId)) {
                return;
            }
            lastAttemptedSequence.set(activity.header().sequence());
            try {
                consumer.accept(activity);
            } catch (Throwable ignored) {
                // One consumer cannot prevent another consumer or gameplay.
            }
        }

        private boolean isOpen() {
            return active.get() && !closed.get();
        }

        private long lastAttemptedSequence() {
            return lastAttemptedSequence.get();
        }

        @Override
        @Nonnull
        public String consumerId() {
            return consumerId;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)
                    && subscriptions.remove(consumerId, this)) {
                rebuildInterestIndex();
            }
        }
    }

    private static final class InterestDomain {
        private boolean wildcard;
        private final Set<String> actions = new HashSet<>();
    }

    private record InterestDomainSnapshot(boolean wildcard, Set<String> actions) {
        private boolean matches(String actionId) {
            return wildcard || actions.contains(actionId);
        }
    }

    private record InterestSnapshot(Map<ActivityDomain, InterestDomainSnapshot> domains) {
        private static InterestSnapshot empty() {
            return new InterestSnapshot(Map.of());
        }

        private boolean matches(ActivityDomain domain, String actionId) {
            InterestDomainSnapshot domainInterest = domains.get(domain);
            return domainInterest != null && domainInterest.matches(actionId);
        }
    }
}
