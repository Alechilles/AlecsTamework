package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical profile/read-revision adapter backed by profile SQLite and the reconciled owner index. */
public final class NpcProfileBondedVesselProfilePort
        implements ProductionBondedVesselMutationAuthority.CanonicalProfilePort {
    private final NpcProfileRepository profiles;
    private final OwnerPopulationIndex populations;
    private final Executor executor;
    @Nullable private final PopulationGroupRepository groups;
    @Nullable private final BondedVesselRepository vessels;
    @Nullable private final ItemFeatureRegistry itemFeatures;
    private final Consumer<String> diagnostics;

    public NpcProfileBondedVesselProfilePort(
            @Nonnull NpcProfileRepository profiles,
            @Nonnull OwnerPopulationIndex populations,
            @Nonnull Executor executor) {
        this(profiles, populations, executor, null, null, null, ignored -> { });
    }

    public NpcProfileBondedVesselProfilePort(
            @Nonnull NpcProfileRepository profiles,
            @Nonnull OwnerPopulationIndex populations,
            @Nonnull Executor executor,
            @Nullable PopulationGroupRepository groups,
            @Nullable BondedVesselRepository vessels,
            @Nullable ItemFeatureRegistry itemFeatures) {
        this(profiles, populations, executor, groups, vessels, itemFeatures, ignored -> { });
    }

    public NpcProfileBondedVesselProfilePort(
            @Nonnull NpcProfileRepository profiles,
            @Nonnull OwnerPopulationIndex populations,
            @Nonnull Executor executor,
            @Nullable PopulationGroupRepository groups,
            @Nullable BondedVesselRepository vessels,
            @Nullable ItemFeatureRegistry itemFeatures,
            @Nonnull Consumer<String> diagnostics) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.populations = Objects.requireNonNull(populations, "populations");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.groups = groups;
        this.vessels = vessels;
        this.itemFeatures = itemFeatures;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot> load(
            @Nonnull String profileId) {
        String normalized = requireText(profileId);
        return CompletableFuture.supplyAsync(() -> {
            NpcProfileRepository.ProfileRecord profile = profiles.loadProfileById(normalized);
            OwnerPopulationEntry population = populations.entry(normalized).orElse(null);
            String roleId = resolveRole(normalized, profile);
            if (profile == null || population == null || profile.ownerUuid() == null
                    || roleId == null
                    || population.ownerId() == null
                    || !profile.ownerUuid().equals(population.ownerId())) {
                diagnostics.accept("Bonded canonical profile unavailable profile=" + normalized
                        + " profilePresent=" + (profile != null)
                        + " populationPresent=" + (population != null)
                        + " ownerPresent=" + (profile != null && profile.ownerUuid() != null)
                        + " populationOwnerPresent=" + (population != null
                        && population.ownerId() != null)
                        + " role=" + roleId);
                return null;
            }
            return new ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot(
                    profile.profileId(), profile.ownerUuid(), roleId,
                    population.revision(), population.lifecycleState(), profile.currentNpcUuid());
        }, executor);
    }

    @Nullable
    private String resolveRole(
            String profileId, @Nullable NpcProfileRepository.ProfileRecord profile) {
        String direct = normalize(profile == null ? null : profile.roleId());
        if (direct != null) return direct;
        if (groups == null) return null;
        PopulationGroupClassificationRecord classification;
        try {
            classification = groups.findClassification(profileId);
        } catch (Exception failure) {
            diagnostics.accept("Bonded captured-role classification lookup failed profile="
                    + profileId + " failure=" + failure.getClass().getSimpleName());
            return null;
        }
        String classified = normalize(classification == null ? null : classification.roleId());
        if (classified == null) return null;
        String repaired = classified;
        try {
            repaired = resolveCapturedRole(profileId, classified);
        } catch (Exception failure) {
            diagnostics.accept("Bonded captured-role mapping lookup failed profile="
                    + profileId + " classifiedRole=" + classified
                    + " failure=" + failure.getClass().getSimpleName());
        }
        if (profile != null && profile.currentNpcUuid() != null) {
            profiles.upsertSnapshotAsync(new NpcProfileRepository.ProfileUpdate(
                    profile.currentNpcUuid(), null, null, repaired, null,
                    null, null, null, null, null, null));
        }
        diagnostics.accept("Bonded captured-role recovered profile=" + profileId
                + " classifiedRole=" + classified + " canonicalRole=" + repaired);
        return repaired;
    }

    private String resolveCapturedRole(String profileId, String classifiedRole) throws Exception {
        if (vessels == null || itemFeatures == null) return classifiedRole;
        BondedVesselBindingRecord binding = vessels.findBindingByProfile(profileId);
        if (binding == null) return classifiedRole;
        var vessel = itemFeatures.getVesselByConfigId(binding.configId()).orElse(null);
        ItemFeatureConfig config = vessel == null || vessel.emptyItemId() == null
                ? null : itemFeatures.get(vessel.emptyItemId());
        if (config == null || !config.isCaptureTamesTarget()) return classifiedRole;
        return canonicalCapturedRole(
                null, classifiedRole, config.resolveCaptureTamedRole(classifiedRole));
    }

    static String canonicalCapturedRole(
            @Nullable String profileRole,
            @Nullable String classifiedRole,
            @Nullable String mappedTamedRole) {
        String direct = normalize(profileRole);
        if (direct != null) return direct;
        String mapped = normalize(mappedTamedRole);
        if (mapped != null) return mapped;
        return normalize(classifiedRole);
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

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
