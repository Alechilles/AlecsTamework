package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

    /**
     * Counts loaded NPCs owned by one player whose roles belong to the supplied groups.
     *
     * <p>This read is process-local and best-effort. It does not replace durable
     * population admission.</p>
     */
    @Nonnull
    default OptionalLong getLoadedOwnedCount(
            @Nonnull UUID ownerUuid,
            @Nonnull Set<String> groupIds
    ) {
        if (ownerUuid == null) throw new NullPointerException("ownerUuid");
        if (groupIds == null) throw new NullPointerException("groupIds");
        return OptionalLong.empty();
    }

    /**
     * Counts durable owned profiles whose roles belong to the supplied groups.
     *
     * <p>The count includes unloaded, dead, and lost profiles. Released profiles
     * do not consume owned capacity.</p>
     */
    @Nonnull
    default OptionalLong getDurableOwnedCount(
            @Nonnull UUID ownerUuid,
            @Nonnull Set<String> groupIds
    ) {
        if (ownerUuid == null) throw new NullPointerException("ownerUuid");
        if (groupIds == null) throw new NullPointerException("groupIds");
        return OptionalLong.empty();
    }

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
