package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityConsumer;
import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityFeedApi;
import com.alechilles.alecstamework.api.ActivityFeedStatus;
import com.alechilles.alecstamework.api.ActivityFeedSubscription;
import com.alechilles.alecstamework.api.ActivityFilter;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/** Process-local, filtered, best-effort Activity API V2 feed. */
public final class LiveActivityFeed implements ActivityFeedApi, AutoCloseable {
    private static final String DETAIL = "activity-api-v2-live";

    private final AtomicLong sequence = new AtomicLong();
    private final Object mutationLock = new Object();
    private final Map<String, SubscriptionState> subscriptions = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile InterestSnapshot interest = InterestSnapshot.empty();

    LiveActivityFeed() {
    }

    /** Returns whether this feed accepts subscriptions and activities. */
    boolean isOpen() {
        return !closed.get();
    }

    /** Returns whether any active consumer is interested in this domain/action pair. */
    public boolean hasInterest(
            @Nonnull ActivityDomain domain,
            @Nonnull String actionId
    ) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(actionId, "actionId");
        if (closed.get()) {
            return false;
        }
        InterestDomainSnapshot domainInterest = interest.domains.get(domain);
        return domainInterest != null && domainInterest.hasInterest(actionId);
    }

    /** Publishes one typed activity after the cached interest check. */
    void publish(@Nonnull ActivityView activity) {
        Objects.requireNonNull(activity, "activity");
        if (closed.get()) {
            return;
        }
        ActivityHeader header = Objects.requireNonNull(
                activity.header(), "activity.header");
        ActivityDomain domain = Objects.requireNonNull(
                activity.domain(), "activity.domain");
        String actionId = Objects.requireNonNull(
                header.actionId(), "activity.header.actionId");
        InterestDomainSnapshot matching = interest.domains.get(domain);
        if (matching == null || !matching.hasInterest(actionId)) {
            return;
        }
        ActivityView sequenced = activity.withHeader(
                header.withSequence(sequence.incrementAndGet()));
        deliver(matching.wildcardSubscribers, sequenced, domain, actionId);
        List<SubscriptionState> exactSubscribers =
                matching.exactSubscribers.get(actionId);
        if (exactSubscribers != null) {
            deliver(exactSubscribers, sequenced, domain, actionId);
        }
    }

    /** Returns the narrow internal publisher seam for composition wiring. */
    @Nonnull
    Publisher publisher() {
        return new Publisher() {
            @Override
            public void publish(@Nonnull ActivityView activity) {
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
        ActivityFilter normalizedFilter = Objects.requireNonNull(
                filter, "filter");
        Objects.requireNonNull(consumer, "consumer");
        SubscriptionState state = new SubscriptionState(
                id, normalizedFilter, consumer);
        synchronized (mutationLock) {
            if (closed.get()) {
                throw new IllegalStateException("Activity feed is closed.");
            }
            if (subscriptions.containsKey(id)) {
                throw new IllegalStateException(
                        "Activity consumer is already subscribed: " + id);
            }
            subscriptions.put(id, state);
            interest = rebuildInterestIndexLocked();
        }
        return state;
    }

    @Override
    @Nonnull
    public ActivityFeedStatus status(@Nonnull String consumerId) {
        String id = requireConsumerId(consumerId);
        synchronized (mutationLock) {
            if (closed.get()) {
                return ActivityFeedStatus.unavailable();
            }
            SubscriptionState state = subscriptions.get(id);
            return new ActivityFeedStatus(
                    true,
                    state != null && state.isOpen(),
                    state == null ? 0L : state.lastAttemptedSequence(),
                    DETAIL
            );
        }
    }

    @Override
    public void close() {
        synchronized (mutationLock) {
            if (closed.get()) {
                return;
            }
            closed.set(true);
            for (SubscriptionState state : subscriptions.values()) {
                state.deactivate();
            }
            subscriptions.clear();
            interest = InterestSnapshot.empty();
        }
    }

    private InterestSnapshot rebuildInterestIndexLocked() {
        EnumMap<ActivityDomain, MutableInterestDomain> next =
                new EnumMap<>(ActivityDomain.class);
        for (SubscriptionState state : subscriptions.values()) {
            for (ActivityDomain domain : state.filter.domains()) {
                MutableInterestDomain domainInterest = next.computeIfAbsent(
                        domain, ignored -> new MutableInterestDomain());
                if (state.filter.actionIds().isEmpty()) {
                    domainInterest.wildcardSubscribers.add(state);
                } else {
                    for (String actionId : state.filter.actionIds()) {
                        domainInterest.exactSubscribers
                                .computeIfAbsent(actionId, ignored -> new ArrayList<>())
                                .add(state);
                    }
                }
            }
        }

        EnumMap<ActivityDomain, InterestDomainSnapshot> snapshot =
                new EnumMap<>(ActivityDomain.class);
        for (Map.Entry<ActivityDomain, MutableInterestDomain> entry
                : next.entrySet()) {
            MutableInterestDomain mutable = entry.getValue();
            Map<String, List<SubscriptionState>> exact = new HashMap<>();
            for (Map.Entry<String, List<SubscriptionState>> action
                    : mutable.exactSubscribers.entrySet()) {
                exact.put(action.getKey(), List.copyOf(action.getValue()));
            }
            snapshot.put(
                    entry.getKey(),
                    new InterestDomainSnapshot(
                            List.copyOf(mutable.wildcardSubscribers),
                            Collections.unmodifiableMap(exact))
            );
        }
        return new InterestSnapshot(Collections.unmodifiableMap(snapshot));
    }

    private static void deliver(
            List<SubscriptionState> matching,
            ActivityView activity,
            ActivityDomain domain,
            String actionId
    ) {
        for (SubscriptionState subscription : matching) {
            subscription.deliver(activity, domain, actionId);
        }
    }

    private static String requireConsumerId(String consumerId) {
        String normalized = Objects.requireNonNull(consumerId, "consumerId").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("consumerId is required.");
        }
        return normalized;
    }

    /** Narrow internal seam for publishing typed activity views. */
    public interface Publisher {
        /** Publishes one typed activity view. */
        void publish(@Nonnull ActivityView activity);
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
            if (!active.get() || closed.get()) {
                return;
            }
            lastAttemptedSequence.set(activity.header().sequence());
            try {
                consumer.accept(activity);
            } catch (Throwable ignored) {
                // One consumer cannot prevent another consumer or gameplay.
            }
        }

        private void deactivate() {
            active.set(false);
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
            synchronized (mutationLock) {
                if (!active.compareAndSet(true, false)) {
                    return;
                }
                if (subscriptions.remove(consumerId, this)) {
                    interest = rebuildInterestIndexLocked();
                }
            }
        }
    }

    private static final class MutableInterestDomain {
        private final List<SubscriptionState> wildcardSubscribers = new ArrayList<>();
        private final Map<String, List<SubscriptionState>> exactSubscribers =
                new HashMap<>();
    }

    private static final class InterestDomainSnapshot {
        private final List<SubscriptionState> wildcardSubscribers;
        private final Map<String, List<SubscriptionState>> exactSubscribers;

        private InterestDomainSnapshot(
                List<SubscriptionState> wildcardSubscribers,
                Map<String, List<SubscriptionState>> exactSubscribers
        ) {
            this.wildcardSubscribers = wildcardSubscribers;
            this.exactSubscribers = exactSubscribers;
        }

        private boolean hasInterest(String actionId) {
            return !wildcardSubscribers.isEmpty()
                    || exactSubscribers.containsKey(actionId);
        }
    }

    private static final class InterestSnapshot {
        private final Map<ActivityDomain, InterestDomainSnapshot> domains;

        private InterestSnapshot(
                Map<ActivityDomain, InterestDomainSnapshot> domains
        ) {
            this.domains = domains;
        }

        private static InterestSnapshot empty() {
            return new InterestSnapshot(Map.of());
        }
    }
}
