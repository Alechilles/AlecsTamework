package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only population-group definitions, counts, and reconciliation authority. */
public interface PopulationGroupApi {
    @Nonnull
    Optional<PopulationGroupDefinitionView> getDefinition(@Nonnull String groupId);

    @Nonnull
    List<PopulationGroupDefinitionView> resolveForRole(@Nonnull String roleId);

    @Nonnull
    Optional<PopulationGroupCountsView> getCounts(@Nonnull UUID ownerUuid,
                                                   @Nonnull String groupId,
                                                   @Nullable String ownershipWorldName);

    @Nonnull
    PopulationGroupReconciliationView getReconciliationStatus();

    /** Compatibility fallback for implementations without group authority. */
    static PopulationGroupApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final PopulationGroupApi INSTANCE = new PopulationGroupApi() {
            @Override
            public Optional<PopulationGroupDefinitionView> getDefinition(String groupId) {
                if (groupId == null) throw new NullPointerException("groupId");
                return Optional.empty();
            }

            @Override
            public List<PopulationGroupDefinitionView> resolveForRole(String roleId) {
                if (roleId == null) throw new NullPointerException("roleId");
                return List.of();
            }

            @Override
            public Optional<PopulationGroupCountsView> getCounts(
                    UUID ownerUuid, String groupId, String ownershipWorldName
            ) {
                if (ownerUuid == null) throw new NullPointerException("ownerUuid");
                if (groupId == null) throw new NullPointerException("groupId");
                return Optional.empty();
            }

            @Override
            public PopulationGroupReconciliationView getReconciliationStatus() {
                return PopulationGroupReconciliationView.unavailable();
            }
        };

        private UnavailableHolder() {
        }
    }
}
