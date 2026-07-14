package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.NpcProfileIdentityLifecycle;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Bridges asynchronous profile writes to the world-safe companion identity cache. */
final class CompanionProfileIdentityLifecycle implements NpcProfileIdentityLifecycle {
    private static final String RESERVATION_PREFIX = "npc-profile-upsert:";

    private final CompanionIdentityResolver identityResolver;

    CompanionProfileIdentityLifecycle(@Nonnull CompanionIdentityResolver identityResolver) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
    }

    @Override
    @Nonnull
    public Reservation reserve(@Nonnull UUID npcUuid) {
        CompanionIdentityResolver.Resolution resolution = identityResolver.resolveOrRetainForProfileWrite(
                npcUuid,
                RESERVATION_PREFIX + npcUuid
        );
        return new Reservation(resolution.profileId(), npcUuid, resolution.provisional());
    }

    @Override
    public void committed(@Nonnull Reservation reservation, @Nonnull UUID durableCurrentNpcUuid) {
        identityResolver.publishDurableAlias(
                reservation.profileId(), reservation.npcUuid(), durableCurrentNpcUuid);
    }

    @Override
    public void aborted(@Nonnull Reservation reservation) {
        if (reservation.provisional()) {
            identityResolver.releaseProvisional(reservation.profileId(), reservation.npcUuid());
        }
    }
}
