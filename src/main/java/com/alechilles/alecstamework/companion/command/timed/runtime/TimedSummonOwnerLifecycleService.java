package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonProjectionView;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stores a player's active timed companions through the durable roster transition. */
public final class TimedSummonOwnerLifecycleService {
    private final Supplier<CommandTimedSummoningApi> timedSummoning;
    private final Supplier<Map<ProfileId, TimedSummonProjectionView>> projections;

    public TimedSummonOwnerLifecycleService(
            @Nonnull Supplier<CommandTimedSummoningApi> timedSummoning,
            @Nonnull Supplier<Map<ProfileId, TimedSummonProjectionView>> projections
    ) {
        this.timedSummoning = Objects.requireNonNull(
                timedSummoning, "Timed summon API is required"
        );
        this.projections = Objects.requireNonNull(
                projections, "Timed summon projections are required"
        );
    }

    /** Stores eligible active companions when the owner disconnects. */
    public int onOwnerLogout(@Nullable UUID ownerUuid) {
        return store(ownerUuid, Reason.LOGOUT);
    }

    /** Stores eligible active companions when the owner dies. */
    public int onOwnerDeath(@Nullable UUID ownerUuid) {
        return store(ownerUuid, Reason.DEATH);
    }

    /** Stores active roster companions that were left mounted across a server restart. */
    public int onStaleAvatarFlightRecovery(@Nullable UUID ownerUuid) {
        return store(ownerUuid, Reason.AVATAR_FLIGHT_RESTART);
    }

    private int store(@Nullable UUID ownerUuid, Reason reason) {
        if (ownerUuid == null) return 0;
        Map<ProfileId, TimedSummonProjectionView> current;
        CommandTimedSummoningApi api;
        try {
            current = projections.get();
            api = timedSummoning.get();
        } catch (RuntimeException ignored) {
            return 0;
        }
        if (current == null || api == null) return 0;

        int submitted = 0;
        for (TimedSummonProjectionView projection : current.values()) {
            if (!eligible(ownerUuid, projection, reason)) continue;
            try {
                api.dismiss(request(ownerUuid, projection, reason));
                submitted++;
            } catch (RuntimeException ignored) {
                // The durable API reports unavailable work independently.
            }
        }
        return submitted;
    }

    private static boolean eligible(UUID ownerUuid,
                                    TimedSummonProjectionView projection,
                                    Reason reason) {
        if (projection == null
                || !ownerUuid.equals(
                projection.membership().familyKey().ownerId().value())
                || !projection.lease().activeSession()) {
            return false;
        }
        LifecycleState state = projection.lifecycle().state();
        if (state != LifecycleState.ACTIVE && state != LifecycleState.UNLOADED) {
            return false;
        }
        return reason != Reason.LOGOUT
                || projection.lease().policy().autoStoreOnOwnerLogout();
    }

    private static CommandTimedSummoningRequest request(
            UUID ownerUuid,
            TimedSummonProjectionView projection,
            Reason reason
    ) {
        String profileId = projection.lease().profileId().toString();
        String key = "timed-summon:owner-" + reason.key + ":"
                + profileId + ":" + projection.lease().leaseRevision();
        return new CommandTimedSummoningRequest(
                ownerUuid,
                projection.membership().familyKey().familyId(),
                profileId,
                key
        );
    }

    private enum Reason {
        LOGOUT("logout"),
        DEATH("death"),
        AVATAR_FLIGHT_RESTART("avatar-flight-restart");

        private final String key;

        Reason(String key) {
            this.key = key;
        }
    }
}
