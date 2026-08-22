package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.api.SummoningActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes low-frequency post-commit lifecycle activities. */
public final class LifecycleActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;

    public LifecycleActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Publishes one successful revival. */
    public void publishRevival(
            @Nonnull UUID operationId,
            @Nullable UUID actorId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable String profileId,
            @Nullable String source,
            @Nullable String lifecycleState,
            @Nullable String paymentOutcome,
            boolean recovered,
            long occurredAtMs
    ) {
        if (operationId == null || ownerId == null || companionId == null
                || profileId == null || source == null
                || lifecycleState == null) {
            return;
        }
        try {
            publisher.publish(new RevivalActivityView(
                    new ActivityHeader(
                            operationId, ActivityIds.REVIVE_SUCCESS,
                            Instant.ofEpochMilli(occurredAtMs)),
                    actorId,
                    ownerId,
                    companionId,
                    profileId,
                    source,
                    lifecycleState,
                    paymentOutcome,
                    recovered
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the committed revival.
        }
    }

    /** Publishes one accepted summon, recall, or expiry transition. */
    public void publishSummoning(
            @Nonnull UUID operationId,
            @Nonnull String actionId,
            @Nullable UUID ownerId,
            @Nullable String profileId,
            @Nullable String commandFamilyId,
            @Nullable UUID companionId,
            @Nullable String lifecycleSource,
            @Nullable Long expiresAtMs,
            long occurredAtMs
    ) {
        if (operationId == null || !isSummoningAction(actionId)
                || ownerId == null || profileId == null
                || commandFamilyId == null || lifecycleSource == null) {
            return;
        }
        try {
            publisher.publish(new SummoningActivityView(
                    new ActivityHeader(
                            operationId, actionId,
                            Instant.ofEpochMilli(occurredAtMs)),
                    ownerId,
                    profileId,
                    commandFamilyId,
                    companionId,
                    lifecycleSource,
                    expiresAtMs
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the committed lifecycle transition.
        }
    }

    private static boolean isSummoningAction(String actionId) {
        return ActivityIds.SUMMON_SUCCESS.equals(actionId)
                || ActivityIds.RECALL.equals(actionId)
                || ActivityIds.SUMMON_EXPIRED.equals(actionId);
    }
}
