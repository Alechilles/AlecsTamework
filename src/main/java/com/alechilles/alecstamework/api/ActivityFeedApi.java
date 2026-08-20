package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Public access to the durable, at-least-once successful-activity feed.
 *
 * <p>Delivery is serialized for each consumer ID. The feed delivers records in sequence order;
 * a later record does not pass a failed sequence. A callback can run on any platform executor;
 * it has no game-loop or thread-affinity guarantee.
 *
 * <p>A callback that throws, returns a null stage or result, completes exceptionally, or exceeds
 * the coordinator's bounded callback timeout is treated as {@link ActivityConsumeResult#RETRY}.
 * The feed does not advance the checkpoint in those cases. A subscription close is idempotent,
 * prevents new callbacks, and permits an in-flight callback to finish.
 */
public interface ActivityFeedApi {
    /**
     * Subscribes one consumer to ordered activity delivery. At most one active subscription exists
     * for a consumer ID.
     */
    @Nonnull
    ActivityFeedSubscription subscribe(
            @Nonnull String consumerId,
            @Nonnull SuccessfulActivityConsumer consumer
    );

    /** Returns availability and checkpoint state for one consumer. */
    @Nonnull
    ActivityFeedStatus status(@Nonnull String consumerId);

    /** Returns a singleton feed that fails closed while preserving safe lifecycle calls. */
    @Nonnull
    static ActivityFeedApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation for older and degraded API compositions. */
    final class UnavailableHolder {
        private static final ActivityFeedApi INSTANCE = new ActivityFeedApi() {
            @Override
            public ActivityFeedSubscription subscribe(
                    String consumerId,
                    SuccessfulActivityConsumer consumer
            ) {
                String id = requireText(consumerId, "consumerId");
                Objects.requireNonNull(consumer, "consumer");
                return new ActivityFeedSubscription() {
                    @Override
                    public String consumerId() {
                        return id;
                    }

                    @Override
                    public void close() {
                        // No runtime subscription exists in the unavailable facade.
                    }
                };
            }

            @Override
            public ActivityFeedStatus status(String consumerId) {
                requireText(consumerId, "consumerId");
                return ActivityFeedStatus.unavailable();
            }
        };

        private UnavailableHolder() {
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required.");
            }
            return normalized;
        }
    }
}
