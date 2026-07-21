package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationGroupApi;
import com.alechilles.alecstamework.api.PopulationGroupCountsView;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only public projection of authoritative group policy and durable counts. */
public final class PopulationGroupApiDelegate implements PopulationGroupApi {
    private final PopulationGroupRegistry registry;
    private final PopulationGroupRepository repository;
    private final BooleanSupplier reconciliationReady;

    public PopulationGroupApiDelegate(@Nonnull PopulationGroupRegistry registry,
                                      @Nonnull PopulationGroupRepository repository,
                                      @Nonnull BooleanSupplier reconciliationReady) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reconciliationReady = Objects.requireNonNull(reconciliationReady, "reconciliationReady");
    }

    @Override
    public Optional<PopulationGroupDefinitionView> getDefinition(String groupId) {
        return groupId == null ? Optional.empty() : registry.snapshot().getDefinition(groupId.trim());
    }

    @Override
    public List<PopulationGroupDefinitionView> resolveForRole(String roleId) {
        return roleId == null ? List.of() : registry.snapshot().resolveForRole(roleId.trim());
    }

    @Override
    public Optional<PopulationGroupCountsView> getCounts(
            UUID ownerUuid, String groupId, @Nullable String ownershipWorldName) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (groupId == null || groupId.isBlank() || !reconciliationReady.getAsBoolean()) {
            return Optional.empty();
        }
        Optional<PopulationGroupDefinitionView> definition =
                registry.snapshot().getDefinition(groupId.trim());
        if (definition.isEmpty()) return Optional.empty();
        PopulationGroupDefinitionView policy = definition.get();
        PopulationGroupCountEvidenceRecord.ScopeKind scopeKind =
                policy.scope() == PopulationGroupScope.PER_WORLD
                        ? PopulationGroupCountEvidenceRecord.ScopeKind.PER_WORLD
                        : PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL;
        try {
            PopulationGroupRepository.Counts counts = repository.count(
                    ownerUuid, policy.groupId(), scopeKind, ownershipWorldName);
            return Optional.of(new PopulationGroupCountsView(
                    ownerUuid, policy.groupId(), policy.scope(), ownershipWorldName,
                    counts.committedOwned(), counts.pendingOwned(),
                    counts.committedActive(), counts.pendingActive(),
                    policy.maxOwnedPerOwner(), policy.maxActivePerOwner(),
                    exceeds(counts.committedOwned(), counts.pendingOwned(), policy.maxOwnedPerOwner()),
                    exceeds(counts.committedActive(), counts.pendingActive(), policy.maxActivePerOwner()),
                    registry.snapshot().revision()));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    @Override
    public PopulationGroupReconciliationView getReconciliationStatus() {
        long now = System.currentTimeMillis();
        if (!reconciliationReady.getAsBoolean()) {
            return new PopulationGroupReconciliationView(
                    PopulationGroupReconciliationView.Readiness.RECONCILING,
                    "population-group-reconciliation-pending", registry.snapshot().revision(),
                    0, 0, 0, now);
        }
        try {
            long classified = repository.loadAllClassifications().size();
            long pending = repository.loadRecoverableOperations().size();
            return new PopulationGroupReconciliationView(
                    PopulationGroupReconciliationView.Readiness.READY,
                    "population-group-authority-ready", registry.snapshot().revision(),
                    classified, pending, 0, now);
        } catch (Exception failure) {
            return new PopulationGroupReconciliationView(
                    PopulationGroupReconciliationView.Readiness.DEGRADED,
                    "population-group-diagnostics-unavailable", registry.snapshot().revision(),
                    0, 0, 0, now);
        }
    }

    private static boolean exceeds(long committed, long pending, long limit) {
        return limit > 0L && committed + pending > limit;
    }
}
