package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Public access to the process-local successful-activity feed.
 *
 * <p>The feed attempts live delivery to each active consumer. It does not
 * retain events, replay missed events, or provide a durable checkpoint. A
 * callback can run on the publishing thread and has no game-loop guarantee.
 * Callback failures are isolated from the publisher and other consumers.</p>
 */
public interface ActivityFeedApi {
    /** Subscribes one consumer. At most one active subscription exists per ID. */
    @Nonnull
    ActivityFeedSubscription subscribe(
            @Nonnull String consumerId,
            @Nonnull SuccessfulActivityConsumer consumer
    );

    /** Returns live availability and the last attempted sequence for one consumer. */
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
