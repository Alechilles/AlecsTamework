package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Rejects housed sidecar rows that canonical population and identity state cannot release.
 *
 * <p>This is a scheduling filter, not the release admission authority. The durable release path
 * revalidates the same identity and population revisions before creating a projection.</p>
 */
final class ManagedCoopReleaseEligibility
        implements ManagedCoopRuntimeSweepPlanner.ReleaseEligibilityGateway {
    private final OwnerPopulationIndex owners;
    private final ClaimOccupancyIndex claims;
    private final CompanionIdentityResolver identities;

    ManagedCoopReleaseEligibility(
            @Nonnull OwnerPopulationIndex owners,
            @Nonnull ClaimOccupancyIndex claims,
            @Nonnull CompanionIdentityResolver identities) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public boolean permitsRelease(@Nonnull ResidentRecord resident) {
        Objects.requireNonNull(resident, "resident");
        OwnerPopulationEntry owner = owners.entry(resident.profileId()).orElse(null);
        ClaimOccupancyEntry claim = claims.entry(resident.profileId()).orElse(null);
        UUID currentUuid = identities.currentNpcUuid(resident.profileId()).orElse(null);
        String resolvedProfile = identities.resolveProfileId(resident.residentUuid()).orElse(null);
        return owner != null
                && claim != null
                && owner.lifecycleState() == CompanionLifecycleState.COOP
                && claim.lifecycleState() == CompanionLifecycleState.COOP
                && owner.revision() == claim.revision()
                && Objects.equals(owner.ownerId(), claim.ownerId())
                && resident.residentUuid().equals(currentUuid)
                && resident.profileId().equals(resolvedProfile);
    }
}
