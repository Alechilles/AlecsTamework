package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.api.SummoningActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes low-frequency post-commit lifecycle activities. */
public final class LifecycleActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;
    private final ManagedActivityConfigRegistry managedActivities;

    public LifecycleActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher
    ) {
        this(publisher, new ManagedActivityConfigRegistry());
    }

    public LifecycleActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.managedActivities = Objects.requireNonNull(
                managedActivities, "managedActivities");
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
        publishRevival(
                operationId, actorId, ownerId, companionId, null, profileId,
                source, lifecycleState, paymentOutcome, recovered, occurredAtMs
        );
    }

    /** Publishes one successful revival with optional managed-role context. */
    public void publishRevival(
            @Nonnull UUID operationId,
            @Nullable UUID actorId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId,
            @Nullable String roleId,
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
        ManagedActivityConfigRegistry.RoleResolution resolution =
                managedActivities.resolveRole(roleId).orElse(null);
        String resolvedRoleId = resolution == null
                ? normalize(roleId) : resolution.roleId();
        Set<String> groupIds = resolution == null || resolution.family() == null
                ? Set.of() : Set.of(resolution.family().groupId());
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
                    recovered,
                    resolvedRoleId,
                    groupIds
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the committed revival.
        }
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
