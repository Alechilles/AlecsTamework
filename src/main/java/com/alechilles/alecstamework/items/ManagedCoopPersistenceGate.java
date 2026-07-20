package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds exact managed-coop mutation contexts without participating in coop authority. */
final class ManagedCoopPersistenceGate {
    private static final Set<String> REQUIRED_COVERAGE = Set.of(
            PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key(),
            PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key(),
            PersistenceEvidenceDimension.MANAGED_COOP_CATALOG.key(),
            PersistenceEvidenceDimension.OPERATION_JOURNAL.key());

    private final PersistenceMutationAvailabilityService availability;
    private final PersistenceScopeFactory scopes;

    ManagedCoopPersistenceGate(@Nonnull PersistenceMutationAvailabilityService availability,
                               @Nonnull PersistenceScopeFactory scopes) {
        this.availability = availability;
        this.scopes = scopes;
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision automation(@Nonnull ManagedCoopContext context) {
        return decide(PersistenceDomain.MANAGED_COOP_AUTOMATION, "automation",
                context, null, null, PersistenceMutationDelta.ZERO, false, false);
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision intake(@Nonnull ManagedCoopContext context,
                                                    @Nullable String profileId,
                                                    @Nullable Integer slot,
                                                    boolean sourceExists) {
        return decide(PersistenceDomain.MANAGED_COOP_INTAKE, "intake",
                context, profileId, slot, PersistenceMutationDelta.ZERO, sourceExists, false);
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision release(@Nonnull ManagedCoopContext context,
                                                     @Nonnull ResidentRecord resident) {
        return decide(PersistenceDomain.MANAGED_COOP_RELEASE, "release",
                context, resident.profileId(), resident.residentSlot(),
                PersistenceMutationDelta.ZERO, true, false);
    }

    @Nonnull
    private PersistenceMutationAvailabilityDecision decide(
            PersistenceDomain domain,
            String operation,
            ManagedCoopContext context,
            @Nullable String profileId,
            @Nullable Integer slot,
            PersistenceMutationDelta delta,
            boolean sourceExists,
            boolean liveProjectionExists) {
        List<PersistenceScope> exact = new ArrayList<>();
        String authority = context.authorityKey().authorityId();
        exact.add(scopes.coopAuthority(authority));
        exact.add(scopes.world(context.worldName()));
        if (profileId != null && !profileId.isBlank()) exact.add(scopes.profile(profileId));
        if (slot != null) exact.add(scopes.coopSlot(authority, slot));
        return availability.decide(new PersistenceMutationContext(
                domain, operation, exact, REQUIRED_COVERAGE, delta,
                null, null, sourceExists, liveProjectionExists));
    }
}
