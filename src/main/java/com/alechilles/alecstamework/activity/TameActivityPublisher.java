package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes committed wild-to-tamed acquisitions. */
public final class TameActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;
    private final ManagedActivityConfigRegistry managedActivities;

    public TameActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher,
            @Nonnull ManagedActivityConfigRegistry managedActivities
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.managedActivities = Objects.requireNonNull(
                managedActivities, "managedActivities");
    }

    /** Publishes one acquisition after owner and tame state commit. */
    public void publish(
            @Nonnull UUID operationId,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable UUID companionId
    ) {
        if (operationId == null || roleId == null || roleId.isBlank()
                || ownerId == null || companionId == null) {
            return;
        }
        ManagedActivityConfigRegistry.RoleResolution resolution =
                managedActivities.resolveRole(roleId.trim()).orElse(null);
        if (resolution == null || resolution.family() == null
                || resolution.profile().activities().tameSuccess() == null) {
            return;
        }
        try {
            publisher.publish(new TameActivityView(
                    new ActivityHeader(
                            operationId, ActivityIds.TAME_SUCCESS, Instant.now()),
                    resolution.profile().profileId(),
                    Set.of(resolution.family().groupId()),
                    resolution.roleId(),
                    ownerId,
                    companionId,
                    resolution.profile().activities().tameSuccess()
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the committed acquisition.
        }
    }
}
