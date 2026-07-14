package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Reserves a recovery projection UUID for its durable profile before the entity becomes live. */
final class CommandLostRecoveryAliasLease {
    private final CompanionIdentityResolver identityResolver;
    private final String profileId;
    private final UUID npcUuid;
    private boolean acquired;

    CommandLostRecoveryAliasLease(@Nonnull CompanionIdentityResolver identityResolver,
                                  @Nonnull String profileId,
                                  @Nonnull UUID npcUuid) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
    }

    boolean acquire() {
        if (acquired) {
            return true;
        }
        acquired = identityResolver.retainPreparedAlias(profileId, npcUuid);
        return acquired;
    }

    /** Releases the reservation only while no entity using the UUID became visible. */
    boolean releaseBeforeVisibility() {
        if (!acquired) {
            return true;
        }
        acquired = false;
        return identityResolver.releasePreparedAlias(profileId, npcUuid);
    }
}
