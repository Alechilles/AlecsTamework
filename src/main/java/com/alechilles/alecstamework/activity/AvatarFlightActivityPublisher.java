package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.AvatarFlightActivityView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes accepted avatar-flight actions after the controller or root accepts them. */
public final class AvatarFlightActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;

    public AvatarFlightActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Returns cached interest without creating an activity payload. */
    public boolean hasInterest(@Nonnull String actionId) {
        return isFlightAction(actionId)
                && publisher.hasInterest(ActivityDomain.AVATAR_FLIGHT, actionId);
    }

    /** Publishes one accepted movement or combat action. */
    public void publish(
            @Nonnull String actionId,
            @Nullable UUID playerId,
            @Nullable String flightConfigId,
            @Nullable String abilitySlot,
            @Nullable String rootInteractionId
    ) {
        if (!hasInterest(actionId) || playerId == null
                || flightConfigId == null || flightConfigId.isBlank()) {
            return;
        }
        try {
            publisher.publish(new AvatarFlightActivityView(
                    new ActivityHeader(
                            UUID.randomUUID(), actionId, Instant.now()),
                    playerId,
                    flightConfigId,
                    abilitySlot,
                    rootInteractionId));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the accepted flight action.
        }
    }

    private static boolean isFlightAction(String actionId) {
        return ActivityIds.FLIGHT_LAUNCH.equals(actionId)
                || ActivityIds.FLIGHT_FLAP.equals(actionId)
                || ActivityIds.FLIGHT_BOOST.equals(actionId)
                || ActivityIds.FLIGHT_AIRBRAKE.equals(actionId)
                || ActivityIds.FLIGHT_COMBAT_ABILITY.equals(actionId);
    }
}
