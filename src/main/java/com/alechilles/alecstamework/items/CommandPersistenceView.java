package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionValue;
import com.alechilles.alecstamework.items.persistence.checkpoint.ReplacementCompanionEntityCheckpointSink;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private final SnapshotLookup snapshots;
    @Nullable
    private final ProjectionLookup projections;
    @Nullable
    private final CompanionQueries companions;
    private CommandSavedNpcPanelCache savedPanels;
    private java.util.function.Function<ProfileId, ProfileExtensionProjectionValue> checkpointLookup = ignored -> null;

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
        checkpointLookup = id -> persistence.queries().projectedExtensions(id,
                ReplacementCompanionEntityCheckpointSink.NAMESPACE).values().stream()
                .max(Comparator.comparingLong(ProfileExtensionProjectionValue::updatedAtMs)).orElse(null);
        savedPanels = new CommandSavedNpcPanelCache(id -> persistence.queries().findProfile(id).thenApply(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) return null;
            ProfileExtensionProjectionValue checkpoint = checkpointLookup.apply(id);
            return CommandSavedNpcPanelSnapshot.decode(found.value(), checkpoint == null ? null : checkpoint.jsonPayload());
        }));
    }

    CommandPersistenceView(@Nonnull ProjectionLookup projections) {
        this.projections = Objects.requireNonNull(
                projections, "Profile projections are required"
        );
        this.companions = null;
        this.snapshots = new SnapshotLookup() {
            @Override
            public Optional<ProfileSnapshot> find(ProfileId profileId) {
                return projections.find(profileId).map(ProfileSnapshot::from);
            }

            @Override
            public Optional<ProfileSnapshot> find(NpcAlias alias) {
                return projections.find(alias).map(ProfileSnapshot::from);
            }
        };
    }

    /**
     * Reads profiles from the companion index. The saved panel for unloaded companions comes
     * from the record's in-memory summary, so it needs no cache or refresh signals.
     */
    CommandPersistenceView(@Nonnull CompanionQueries companions) {
        this.companions = Objects.requireNonNull(companions, "Companion queries are required");
        this.projections = null;
        this.snapshots = new SnapshotLookup() {
            @Override
            public Optional<ProfileSnapshot> find(ProfileId profileId) {
                return Optional.ofNullable(companions.get(profileId.value())).map(CommandPersistenceView::from);
            }

            @Override
            public Optional<ProfileSnapshot> find(NpcAlias alias) {
                return Optional.ofNullable(companions.byNpcUuid(alias.value())).map(CommandPersistenceView::from);
            }
        };
    }

    CommandSavedNpcPanelSnapshot savedPanel(LinkedNpcRecord record, UUID viewer) {
        if (companions != null) {
            return find(record).map(profile -> companions.get(profile.profileId().value()))
                    .map(CommandSavedNpcPanelSnapshot::fromSummary).orElse(null);
        }
        if (savedPanels == null) return null;
        ProfileId id = find(record).map(ProfileSnapshot::profileId).orElse(null);
        CompanionProfileProjectionState projection = id == null ? null : safeProjection(id).orElse(null);
        if (projection == null) return null;
        ProfileExtensionProjectionValue checkpoint = checkpointLookup.apply(id);
        return savedPanels.peek(id, viewer, new CommandSavedNpcPanelCache.Revision(
                projection.lastUpdatedAtMs(), checkpoint == null ? null : checkpoint.key().toString(),
                checkpoint == null ? 0L : checkpoint.revision()));
    }

    LinkedPanelRefreshSignalSource savedPanelSignals(UUID owner) {
        return savedPanels == null ? LinkedPanelRefreshSignalSource.none()
                : savedPanels.signals(owner);
    }

    void close() { if (savedPanels != null) savedPanels.close(); }

    /**
     * Panel membership for one tool. On the companion index, item metadata decides: generic items
     * keep their selection only there (commit 11106c9a4), so a record without the tool id still
     * belongs. On the old projection, a known profile's durable links decide.
     */
    List<LinkedNpcRecord> linkedRecordsForTool(List<LinkedNpcRecord> records, @Nullable String toolId) {
        if (companions != null) {
            return records;
        }
        final UUID tool;
        try {
            tool = UUID.fromString(toolId);
        } catch (IllegalArgumentException | NullPointerException invalidTool) {
            return records;
        }
        return records.stream().filter(record -> find(record)
                .map(profile -> profile.toolIds().contains(tool)).orElse(true)).toList();
    }

    /**
     * Resolves one command record by stable profile first and known alias second.
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
            Optional<ProfileSnapshot> byProfile = safeFind(explicit);
            if (byProfile.isPresent()) {
                return byProfile;
            }
        }
        Optional<ProfileSnapshot> byAlias = safeFind(new NpcAlias(record.npcUuid));
        if (byAlias.isPresent()) {
            return byAlias;
        }
        if (explicit == null) {
            return safeFind(new ProfileId(record.npcUuid));
        }
        return Optional.empty();
    }

    /** Confirms that a legacy profile field contains an alias of this profile. */
    boolean isKnownAliasForProfile(
            @Nullable String rawCandidate,
            @Nonnull ProfileId profileId
    ) {
        ProfileId candidate = parseProfileId(rawCandidate);
        if (candidate == null || profileId == null
                || safeFind(candidate).isPresent()) {
            return false;
        }
        return safeFind(new NpcAlias(candidate.value()))
                .map(ProfileSnapshot::profileId)
                .filter(profileId::equals)
                .isPresent();
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
    private Optional<ProfileSnapshot> safeFind(ProfileId profileId) {
        try {
            return snapshots.find(profileId);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    @Nonnull
    private Optional<ProfileSnapshot> safeFind(NpcAlias alias) {
        try {
            return snapshots.find(alias);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    @Nonnull
    private Optional<CompanionProfileProjectionState> safeProjection(ProfileId profileId) {
        try {
            return projections == null ? Optional.empty() : projections.find(profileId);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    /**
     * Maps one index record to the command-facing snapshot. The location kind decides the
     * lifecycle state; tool ids that are not UUIDs are skipped.
     */
    @Nonnull
    static ProfileSnapshot from(@Nonnull CompanionRecord record) {
        Set<UUID> tools = new HashSet<>();
        for (String raw : record.toolIds()) {
            try {
                tools.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // A malformed tool id links nothing.
            }
        }
        return new ProfileSnapshot(new ProfileId(record.profileId()), record.currentNpcUuid(), record.ownerUuid(),
                record.roleId(), record.displayName(), record.summary().customName(), tools,
                lifecycleState(record.location()), record.reviveAvailableAtMs());
    }

    @Nonnull
    private static LifecycleState lifecycleState(@Nonnull CompanionLocation location) {
        return switch (location.kind()) {
            case LIVE -> LifecycleState.ACTIVE;
            case ITEM -> LifecycleState.CAPTURED;
            case COOP -> LifecycleState.COOP;
            case STORED -> location.reason() == StoredReason.PROVISIONED
                    ? LifecycleState.PROVISIONED_DORMANT : LifecycleState.ROSTER_STORED;
            case DEAD -> LifecycleState.DEAD_REVIVABLE;
            case LOST -> LifecycleState.LOST;
            case RELEASED -> LifecycleState.RELEASED;
        };
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
            @Nonnull LifecycleState lifecycleState,
            long restorationAvailableAtMs
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
        static ProfileSnapshot from(
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
                    projection.lifecycleState(),
                    projection.restorationAvailableAtMs()
            );
        }
    }

    /** Profile lookups by id and by NPC alias, from whichever source backs this view. */
    private interface SnapshotLookup {
        @Nonnull
        Optional<ProfileSnapshot> find(@Nonnull ProfileId profileId);

        @Nonnull
        Optional<ProfileSnapshot> find(@Nonnull NpcAlias alias);
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
