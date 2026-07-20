package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Gates an explicit companion relocation before it can acquire leases or durable admission. */
final class CommandRelocationPersistenceGate {
    private static final Set<String> REQUIRED_COVERAGE = Set.of(
            PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key(),
            PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key(),
            PersistenceEvidenceDimension.OPERATION_JOURNAL.key());

    private final PersistenceMutationAvailabilityService availability;
    private final PersistenceScopeFactory scopes;
    private final Function<UUID, String> profileIdLoader;

    CommandRelocationPersistenceGate(@Nonnull PersistenceMutationAvailabilityService availability,
                                     @Nonnull PersistenceScopeFactory scopes,
                                     @Nonnull NpcProfileRepository profiles) {
        this(availability, scopes, npcUuid -> {
            NpcProfileRepository.ProfileRecord profile = profiles.loadProfileByNpcUuid(npcUuid);
            return profile != null ? profile.profileId() : null;
        });
    }

    CommandRelocationPersistenceGate(@Nonnull PersistenceMutationAvailabilityService availability,
                                     @Nonnull PersistenceScopeFactory scopes,
                                     @Nonnull Function<UUID, String> profileIdLoader) {
        this.availability = Objects.requireNonNull(availability, "availability");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.profileIdLoader = Objects.requireNonNull(profileIdLoader, "profileIdLoader");
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision decide(@Nonnull UUID npcUuid,
                                                   @Nullable String knownProfileId,
                                                   @Nullable UUID ownerUuid,
                                                   @Nonnull String destinationWorld,
                                                   @Nullable String sourceWorld,
                                                   @Nonnull String operationKind,
                                                   boolean liveProjectionExists) {
        String profileId = resolveProfileId(npcUuid, knownProfileId);
        if (profileId == null) {
            return deny("canonical_profile_unavailable_for_relocation");
        }
        if (ownerUuid == null) {
            return deny("owner_identity_unavailable_for_relocation");
        }
        List<PersistenceScope> exactScopes = buildScopes(
                profileId, ownerUuid, destinationWorld, sourceWorld);
        return availability.decide(new PersistenceMutationContext(
                PersistenceDomain.RECALL_RELOCATION,
                operationKind,
                exactScopes,
                REQUIRED_COVERAGE,
                PersistenceMutationDelta.ZERO,
                null,
                null,
                true,
                liveProjectionExists));
    }

    @Nullable
    private String resolveProfileId(UUID npcUuid, @Nullable String knownProfileId) {
        if (knownProfileId != null && !knownProfileId.isBlank()) {
            return knownProfileId.trim();
        }
        try {
            String loaded = profileIdLoader.apply(npcUuid);
            return loaded == null || loaded.isBlank() ? null : loaded.trim();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private List<PersistenceScope> buildScopes(String profileId,
                                               UUID ownerUuid,
                                               String destinationWorld,
                                               @Nullable String sourceWorld) {
        ArrayList<PersistenceScope> exact = new ArrayList<>();
        exact.add(scopes.profile(profileId));
        exact.add(scopes.ownerGlobal(ownerUuid));
        addWorldScopes(exact, ownerUuid, destinationWorld);
        if (sourceWorld != null && !sourceWorld.isBlank()
                && !sourceWorld.trim().equals(destinationWorld.trim())) {
            addWorldScopes(exact, ownerUuid, sourceWorld);
        }
        return List.copyOf(exact);
    }

    private void addWorldScopes(List<PersistenceScope> exact, UUID ownerUuid, String worldName) {
        exact.add(scopes.ownerWorld(ownerUuid, worldName));
        exact.add(scopes.world(worldName));
    }

    private PersistenceMutationAvailabilityDecision deny(String reason) {
        return new PersistenceMutationAvailabilityDecision(
                PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY, reason, null);
    }
}
