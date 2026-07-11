package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Protects canonically owned or transitioning companions from bulk NPC deletion. */
final class NpcCleanOwnershipGuard {
    private final CompanionIdentityResolver identityResolver;
    private final OwnerPopulationIndex populationIndex;
    private final ClaimOccupancyIndex claimIndex;

    NpcCleanOwnershipGuard(@Nonnull CompanionIdentityResolver identityResolver,
                           @Nonnull OwnerPopulationIndex populationIndex,
                           @Nonnull ClaimOccupancyIndex claimIndex) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.populationIndex = Objects.requireNonNull(populationIndex, "populationIndex");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
    }

    boolean readyForDestructiveCleanup() {
        return populationIndex.readiness() == OwnerPopulationReadiness.READY
                && claimIndex.readiness() == ClaimOccupancyReadiness.READY;
    }

    boolean isProtectedOwnedCompanion(@Nullable UUID npcUuid,
                                      @Nullable TameworkOwnerComponent liveOwner,
                                      @Nullable TameworkCommandLinksComponent liveLinks,
                                      @Nullable TameworkNpcNameComponent liveName) {
        if ((liveOwner != null && liveOwner.hasOwner()) || hasDerivedOwnership(liveLinks, liveName)) {
            return true;
        }
        if (!readyForDestructiveCleanup()) {
            return true;
        }
        if (npcUuid == null) {
            return true;
        }
        Optional<String> profileId = identityResolver.resolveProfileId(npcUuid);
        if (profileId.isEmpty()) {
            return false;
        }
        if (populationIndex.hasPendingTransition(profileId.get())) {
            return true;
        }
        Optional<OwnerPopulationEntry> entry = populationIndex.entry(profileId.get());
        return entry.isEmpty() || entry.get().ownerId() != null;
    }

    private static boolean hasDerivedOwnership(@Nullable TameworkCommandLinksComponent links,
                                               @Nullable TameworkNpcNameComponent name) {
        return (links != null && (links.getOwnerId() != null || links.getToolIds().length > 0))
                || (name != null && name.getOwnerId() != null);
    }
}
