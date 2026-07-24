package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exposes the small, synchronous canonical profile view needed by command gameplay.
 *
 * <p>The replacement profile projection is the only durable status and identity authority.
 * Command-item metadata remains an immutable cache that this view may redirect to the current
 * runtime alias. Storage reads and legacy repository models deliberately do not cross this
 * boundary.</p>
 */
final class CommandPersistenceView {
    private final ProjectionLookup projections;

    CommandPersistenceView(@Nonnull PersistenceDomainFacades persistence) {
        this(new ProjectionLookup() {
            @Override
            public Optional<CompanionProfileProjectionState> find(
                    ProfileId profileId
            ) {
                return persistence.queries().projectedProfile(profileId);
            }

            @Override
            public Optional<CompanionProfileProjectionState> find(
                    NpcAlias alias
            ) {
                return persistence.queries().projectedProfile(alias);
            }
        });
    }

    CommandPersistenceView(@Nonnull ProjectionLookup projections) {
        this.projections = Objects.requireNonNull(
                projections, "Profile projections are required"
        );
    }

    /**
     * Resolves one command record by stable profile first and current alias second.
     *
     * <p>Records created before their first projection use the NPC UUID as their deterministic
     * profile ID. Absence is not interpreted as a lifecycle state.</p>
     */
    @Nonnull
    Optional<ProfileSnapshot> find(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return Optional.empty();
        }
        ProfileId explicit = parseProfileId(record.profileId);
        if (explicit != null) {
            Optional<CompanionProfileProjectionState> byProfile =
                    safeFind(explicit);
            if (byProfile.isPresent()) {
                return byProfile.map(ProfileSnapshot::from);
            }
        }
        Optional<CompanionProfileProjectionState> byAlias =
                safeFind(new NpcAlias(record.npcUuid));
        if (byAlias.isPresent()) {
            return byAlias.map(ProfileSnapshot::from);
        }
        if (explicit == null) {
            return safeFind(new ProfileId(record.npcUuid))
                    .map(ProfileSnapshot::from);
        }
        return Optional.empty();
    }

    /** Resolves a deterministic stable profile identity for one command record. */
    @Nullable
    ProfileId profileId(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return null;
        }
        ProfileId explicit = parseProfileId(record.profileId);
        if (explicit != null) {
            return explicit;
        }
        return find(record)
                .map(ProfileSnapshot::profileId)
                .orElseGet(() -> new ProfileId(record.npcUuid));
    }

    @Nonnull
    private Optional<CompanionProfileProjectionState> safeFind(
            ProfileId profileId
    ) {
        try {
            return projections.find(profileId);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    @Nonnull
    private Optional<CompanionProfileProjectionState> safeFind(
            NpcAlias alias
    ) {
        try {
            return projections.find(alias);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    @Nullable
    private static ProfileId parseProfileId(@Nullable String raw) {
        String normalized = LinkedNpcRecordCodec.normalizeProfileId(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return ProfileId.parse(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Immutable command-facing subset of one canonical profile projection. */
    record ProfileSnapshot(
            @Nonnull ProfileId profileId,
            @Nullable UUID currentNpcUuid,
            @Nullable UUID ownerUuid,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable String customName,
            @Nonnull Set<UUID> toolIds,
            @Nonnull LifecycleState lifecycleState
    ) {
        ProfileSnapshot {
            Objects.requireNonNull(profileId, "Profile ID is required");
            Objects.requireNonNull(
                    lifecycleState, "Lifecycle state is required"
            );
            toolIds = Set.copyOf(toolIds);
        }

        boolean dead() {
            return lifecycleState == LifecycleState.DEAD_REVIVABLE;
        }

        boolean captured() {
            return lifecycleState == LifecycleState.CAPTURED;
        }

        boolean inCoop() {
            return lifecycleState == LifecycleState.COOP;
        }

        boolean lost() {
            return lifecycleState == LifecycleState.LOST;
        }

        boolean dormant() {
            return dead() || captured() || inCoop() || lost();
        }

        boolean restorable() {
            return dead() || lost();
        }

        boolean blocksLiveAction() {
            return lifecycleState != LifecycleState.ACTIVE
                    && lifecycleState != LifecycleState.UNLOADED;
        }

        @Nonnull
        private static ProfileSnapshot from(
                CompanionProfileProjectionState projection
        ) {
            return new ProfileSnapshot(
                    projection.profileId(),
                    projection.currentAlias() == null
                            ? null
                            : projection.currentAlias().value(),
                    projection.ownerId() == null
                            ? null
                            : projection.ownerId().value(),
                    projection.roleId(),
                    projection.displayName(),
                    projection.customName(),
                    projection.toolIds(),
                    projection.lifecycleState()
            );
        }
    }

    /** Adapter seam for deterministic projection tests. */
    interface ProjectionLookup {
        @Nonnull
        Optional<CompanionProfileProjectionState> find(
                @Nonnull ProfileId profileId
        );

        @Nonnull
        Optional<CompanionProfileProjectionState> find(
                @Nonnull NpcAlias alias
        );
    }
}
