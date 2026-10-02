package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.PopulationGroupCountsView;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigDefinition;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Population group definitions from the current config and counts from the companion index.
 * Every read is synchronous and lock-free. Counts use the same buckets as
 * {@link CompanionAdmission}: owned is every non-released record, deployed is a LIVE record
 * (loaded or not), and a per-world group counts a record in the world it is in or was tamed in.
 * A count is empty only for an unknown group or a per-world group asked without a world.
 */
public final class IndexPopulationGroupApi implements PopulationGroupApi {
    private final CompanionIndex index;
    private final Supplier<PopulationGroupConfigIndex> groups;

    /** {@code groups} returns the current population-group config; null is read as no groups. */
    public IndexPopulationGroupApi(@Nonnull CompanionIndex index,
                                   @Nonnull Supplier<PopulationGroupConfigIndex> groups) {
        this.index = Objects.requireNonNull(index, "index");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Nonnull
    public Optional<PopulationGroupDefinitionView> getDefinition(@Nonnull String groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return config().getDefinition(groupId.trim()).map(IndexPopulationGroupApi::definition);
    }

    @Override
    @Nonnull
    public List<PopulationGroupDefinitionView> resolveForRole(@Nonnull String roleId) {
        Objects.requireNonNull(roleId, "roleId");
        return config().resolveForRole(roleId.trim()).stream().map(IndexPopulationGroupApi::definition).toList();
    }

    @Override
    @Nonnull
    public Optional<PopulationGroupCountsView> getCounts(@Nonnull UUID ownerUuid,
                                                         @Nonnull String groupId,
                                                         @Nullable String ownershipWorldName) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupId, "groupId");
        PopulationGroupConfigDefinition configured = config().getDefinition(groupId.trim()).orElse(null);
        if (configured == null) {
            return Optional.empty();
        }
        PopulationGroupPolicy policy = configured.policy();
        boolean perWorld = policy.scope()
                == com.alechilles.alecstamework.companion.population.group.PopulationGroupScope.PER_WORLD;
        String world = ownershipWorldName == null || ownershipWorldName.isBlank() ? null : ownershipWorldName.trim();
        if (perWorld && world == null) {
            return Optional.empty();
        }
        Set<String> roles = configured.roleIds();
        Predicate<CompanionRecord> bucket = record -> record.countsAsOwned() && roles.contains(record.roleId())
                && (!perWorld || CompanionAdmission.scopeWorld(record).equals(world));
        long owned = index.count(ownerUuid, bucket);
        long deployed = index.count(ownerUuid, bucket.and(CompanionRecord::countsAsDeployed));
        return Optional.of(new PopulationGroupCountsView(
                ownerUuid,
                policy.groupId(),
                apiScope(policy),
                perWorld ? world : null,
                owned,
                0L,
                deployed,
                0L,
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                policy.maxOwnedPerOwner() > 0 && owned > policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner() > 0 && deployed > policy.maxActivePerOwner(),
                0L
        ));
    }

    @Override
    @Nonnull
    public OptionalLong getDurableOwnedCount(@Nonnull UUID ownerUuid, @Nonnull Set<String> groupIds) {
        return count(ownerUuid, groupIds, CompanionRecord::countsAsOwned);
    }

    @Override
    @Nonnull
    public OptionalLong getDurableDeployableCount(@Nonnull UUID ownerUuid, @Nonnull Set<String> groupIds) {
        return count(ownerUuid, groupIds, record -> record.countsAsOwned() && record.countsAsDeployed());
    }

    /** Counts each record once, however many of the groups its role belongs to. */
    private OptionalLong count(UUID ownerUuid, Set<String> groupIds, Predicate<CompanionRecord> counted) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(groupIds, "groupIds");
        PopulationGroupConfigIndex config = config();
        Set<String> roles = new HashSet<>();
        for (String groupId : groupIds) {
            PopulationGroupConfigDefinition configured =
                    groupId == null ? null : config.getDefinition(groupId.trim()).orElse(null);
            if (configured == null) {
                return OptionalLong.empty();
            }
            roles.addAll(configured.roleIds());
        }
        return OptionalLong.of(index.count(ownerUuid, counted.and(record -> roles.contains(record.roleId()))));
    }

    private PopulationGroupConfigIndex config() {
        PopulationGroupConfigIndex current = groups.get();
        return current == null ? PopulationGroupConfigIndex.empty() : current;
    }

    private static PopulationGroupDefinitionView definition(PopulationGroupConfigDefinition configured) {
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

    private static PopulationGroupScope apiScope(PopulationGroupPolicy policy) {
        return PopulationGroupScope.valueOf(policy.scope().name());
    }
}
