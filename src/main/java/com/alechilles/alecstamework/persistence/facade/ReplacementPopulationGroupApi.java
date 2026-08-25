package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.PopulationGroupCountsView;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupLifecycleClassifier;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigDefinition;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Read-only population-group API composed from config policy and replacement
 * projection evidence.
 */
public final class ReplacementPopulationGroupApi
        implements PopulationGroupApi {
    private final PersistenceBootstrap persistence;
    private final PublicPersistenceQueries queries;
    private final PopulationGroupConfigRegistry configs;
    private final LongSupplier clock;
    private final OwnerPopulationLiveIndex liveIndex;

    public ReplacementPopulationGroupApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PopulationGroupConfigRegistry configs,
            @Nonnull LongSupplier clock
    ) {
        this(persistence, queries, configs, clock, null);
    }

    public ReplacementPopulationGroupApi(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PopulationGroupConfigRegistry configs,
            @Nonnull LongSupplier clock,
            @Nullable OwnerPopulationLiveIndex liveIndex
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.queries = Objects.requireNonNull(queries, "queries");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.liveIndex = liveIndex;
    }

    @Override
    @Nonnull
    public OptionalLong getLoadedOwnedCount(
            @Nonnull UUID ownerUuid,
            @Nonnull Set<String> groupIds
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupIds, "groupIds");
        if (liveIndex == null) {
            return OptionalLong.empty();
        }
        Optional<Set<String>> roles = resolveRoleIds(groupIds);
        return roles.isEmpty() ? OptionalLong.empty() : OptionalLong.of(
                liveIndex.countOwnedRoles(ownerUuid, roles.orElseThrow())
        );
    }

    @Override
    @Nonnull
    public OptionalLong getDurableOwnedCount(
            @Nonnull UUID ownerUuid,
            @Nonnull Set<String> groupIds
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupIds, "groupIds");
        if (!projectionReadable()) {
            return OptionalLong.empty();
        }
        Optional<Set<String>> roles = resolveRoleIds(groupIds);
        if (roles.isEmpty()) {
            return OptionalLong.empty();
        }
        Set<String> roleIds = roles.orElseThrow();
        long count = queries.projectedProfileSnapshot().values().stream()
                .filter(profile -> profile.ownerId() != null
                        && ownerUuid.equals(profile.ownerId().value()))
                .filter(profile -> profile.roleId() != null
                        && roleIds.contains(profile.roleId()))
                .filter(profile -> PopulationGroupLifecycleClassifier
                        .consumesOwned(profile.lifecycleState()))
                .count();
        return OptionalLong.of(count);
    }

    public boolean supportsLoadedOwnedCounts() {
        return liveIndex != null;
    }

    private Optional<Set<String>> resolveRoleIds(Set<String> groupIds) {
        PopulationGroupConfigIndex snapshot = configs.snapshot();
        LinkedHashSet<String> roleIds = new LinkedHashSet<>();
        for (String groupId : groupIds) {
            if (groupId == null || groupId.isBlank()) {
                return Optional.empty();
            }
            Optional<PopulationGroupConfigDefinition> definition =
                    snapshot.getDefinition(groupId.trim());
            if (definition.isEmpty()) {
                return Optional.empty();
            }
            roleIds.addAll(definition.orElseThrow().roleIds());
        }
        return Optional.of(Set.copyOf(roleIds));
    }

    @Override
    @Nonnull
    public Optional<PopulationGroupDefinitionView> getDefinition(
            @Nonnull String groupId
    ) {
        Objects.requireNonNull(groupId, "groupId");
        String normalized = groupId.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return configs.snapshot().getDefinition(normalized)
                .map(this::definition);
    }

    @Override
    @Nonnull
    public List<PopulationGroupDefinitionView> resolveForRole(
            @Nonnull String roleId
    ) {
        Objects.requireNonNull(roleId, "roleId");
        String normalized = roleId.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return configs.snapshot().resolveForRole(normalized).stream()
                .map(this::definition)
                .toList();
    }

    @Override
    @Nonnull
    public Optional<PopulationGroupCountsView> getCounts(
            @Nonnull UUID ownerUuid,
            @Nonnull String groupId,
            @Nullable String ownershipWorldName
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupId, "groupId");
        String normalized = groupId.trim();
        if (normalized.isEmpty() || !projectionReadable()) {
            return Optional.empty();
        }
        Optional<PopulationGroupConfigDefinition> configured =
                configs.snapshot().getDefinition(normalized);
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        PopulationGroupPolicy policy = configured.orElseThrow().policy();
        String world = normalize(ownershipWorldName);
        if (policy.scope()
                == com.alechilles.alecstamework.companion.population.group
                .PopulationGroupScope.PER_WORLD && world == null) {
            return Optional.empty();
        }
        PopulationGroupBucket bucket = new PopulationGroupBucket(
                new OwnerId(ownerUuid),
                policy.groupId(),
                policy.scope(),
                policy.scope()
                        == com.alechilles.alecstamework.companion.population
                        .group.PopulationGroupScope.PER_WORLD
                        ? world
                        : null
        );
        PopulationGroupCounts counts =
                queries.projectedPopulationGroupCounts(bucket);
        return Optional.of(new PopulationGroupCountsView(
                ownerUuid,
                policy.groupId(),
                apiScope(policy),
                bucket.ownerWorldKey(),
                counts.committedOwned(),
                counts.pendingOwned(),
                counts.committedActive(),
                counts.pendingActive(),
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                exceeds(
                        counts.committedOwned(),
                        counts.pendingOwned(),
                        policy.maxOwnedPerOwner()
                ),
                exceeds(
                        counts.committedActive(),
                        counts.pendingActive(),
                        policy.maxActivePerOwner()
                ),
                policy.policyRevision()
        ));
    }

    @Override
    @Nonnull
    public PopulationGroupReconciliationView getReconciliationStatus() {
        PopulationGroupConfigIndex config = configs.snapshot();
        PersistenceReadinessLevel readiness = persistence.readiness(
                PublicPersistenceFeatureRegistry.POPULATION_GROUPS
        );
        long pending = queries.projectedLaggingPopulationGroupProfiles()
                .size();
        long classified = queries
                .projectedPopulationGroupAssignments().size();
        PopulationGroupReconciliationView.Readiness publicReadiness =
                publicReadiness(readiness, pending);
        return new PopulationGroupReconciliationView(
                publicReadiness,
                reason(publicReadiness),
                config.revision(),
                classified,
                pending,
                0L,
                clock.getAsLong()
        );
    }

    @Nonnull
    private PopulationGroupDefinitionView definition(
            PopulationGroupConfigDefinition configured
    ) {
        PopulationGroupPolicy policy = configured.policy();
        return new PopulationGroupDefinitionView(
                configured.configId(),
                policy.policyRevision(),
                policy.groupId(),
                configured.roleIds(),
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                apiScope(policy)
        );
    }

    private com.alechilles.alecstamework.api.PopulationGroupScope apiScope(
            PopulationGroupPolicy policy
    ) {
        return com.alechilles.alecstamework.api.PopulationGroupScope.valueOf(
                policy.scope().name()
        );
    }

    private boolean projectionReadable() {
        PersistenceReadinessLevel readiness = persistence.readiness(
                PublicPersistenceFeatureRegistry.POPULATION_GROUPS
        );
        return readiness == PersistenceReadinessLevel.PROJECTION_READY
                || readiness == PersistenceReadinessLevel
                .WORLD_EVIDENCE_PENDING
                || readiness == PersistenceReadinessLevel.MUTATION_READY;
    }

    private PopulationGroupReconciliationView.Readiness publicReadiness(
            PersistenceReadinessLevel readiness,
            long pending
    ) {
        if (readiness == PersistenceReadinessLevel.MUTATION_READY
                && pending == 0L) {
            return PopulationGroupReconciliationView.Readiness.READY;
        }
        return switch (readiness) {
            case CANONICAL_READ_ONLY, RECOVERING, PROJECTION_READY,
                    WORLD_EVIDENCE_PENDING, MUTATION_READY ->
                    PopulationGroupReconciliationView.Readiness.RECONCILING;
            case CLOSED, QUARANTINED, GLOBAL_READ_ONLY ->
                    PopulationGroupReconciliationView.Readiness.DEGRADED;
        };
    }

    private String reason(
            PopulationGroupReconciliationView.Readiness readiness
    ) {
        return switch (readiness) {
            case READY -> "population-group-authority-ready";
            case RECONCILING -> "population-group-reconciliation-pending";
            case DEGRADED -> "population-group-authority-degraded";
            case UNAVAILABLE -> "population-group-authority-unavailable";
        };
    }

    private boolean exceeds(long committed, long pending, long limit) {
        return limit > 0L && committed + pending > limit;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
