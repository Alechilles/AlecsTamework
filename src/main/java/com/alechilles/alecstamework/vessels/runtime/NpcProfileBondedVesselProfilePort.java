package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;

/** Canonical profile/read-revision adapter backed by profile SQLite and the reconciled owner index. */
public final class NpcProfileBondedVesselProfilePort
        implements ProductionBondedVesselMutationAuthority.CanonicalProfilePort {
    private final NpcProfileRepository profiles;
    private final OwnerPopulationIndex populations;
    private final Executor executor;

    public NpcProfileBondedVesselProfilePort(
            @Nonnull NpcProfileRepository profiles,
            @Nonnull OwnerPopulationIndex populations,
            @Nonnull Executor executor) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.populations = Objects.requireNonNull(populations, "populations");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot> load(
            @Nonnull String profileId) {
        String normalized = requireText(profileId);
        return CompletableFuture.supplyAsync(() -> {
            NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(normalized);
            OwnerPopulationEntry population = populations.entry(normalized).orElse(null);
            if (profile == null || population == null || profile.ownerUuid() == null
                    || profile.roleId() == null || profile.roleId().isBlank()
                    || population.ownerId() == null
                    || !profile.ownerUuid().equals(population.ownerId())) {
                return null;
            }
            return new ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot(
                    profile.profileId(), profile.ownerUuid(), profile.roleId(),
                    population.revision(), population.lifecycleState(), profile.currentNpcUuid());
        }, executor);
    }

    @Nonnull
    @Override
    public ProductionBondedVesselMutationAuthority.ProfileReadiness readiness() {
        OwnerPopulationReadiness readiness = populations.readiness();
        boolean ready = readiness == OwnerPopulationReadiness.READY;
        return new ProductionBondedVesselMutationAuthority.ProfileReadiness(
                ready, ready ? "canonical-profile-authority-ready"
                        : "canonical-profile-owner-index-" + readiness.name().toLowerCase());
    }

    private static String requireText(String value) {
        String normalized = Objects.requireNonNull(value, "profileId").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("profileId is required");
        return normalized;
    }
}
