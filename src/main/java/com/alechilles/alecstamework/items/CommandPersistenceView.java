package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.migrate.LegacyItemAdoption;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import java.util.HashSet;
import java.util.List;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exposes the small, synchronous canonical profile view needed by command gameplay.
 *
 * <p>The companion index is the only durable status and identity authority. Command-item
 * metadata remains an immutable cache that this view may redirect to the current runtime
 * alias.</p>
 */
final class CommandPersistenceView {
    private final SnapshotLookup snapshots;
    private final CompanionQueries companions;

    /**
     * Reads profiles from the companion index. The saved panel for unloaded companions comes
     * from the record's in-memory summary, so it needs no cache or refresh signals.
     */
    CommandPersistenceView(@Nonnull CompanionQueries companions) {
        this(companions, LegacyItemAdoption::profileOf);
    }

    /**
     * @param legacyProfile the profile an imported 3.x/4.x world knew an NPC UUID for, or null
     *                      (plan 7 R19): a link saved by an older version may name a body the
     *                      companion has since left
     */
    CommandPersistenceView(@Nonnull CompanionQueries companions, @Nonnull Function<UUID, UUID> legacyProfile) {
        this.companions = Objects.requireNonNull(companions, "Companion queries are required");
        Objects.requireNonNull(legacyProfile, "Legacy profile lookup is required");
        this.snapshots = new SnapshotLookup() {
            @Override
            public Optional<ProfileSnapshot> find(ProfileId profileId) {
                return Optional.ofNullable(companions.get(profileId.value())).map(CommandPersistenceView::from);
            }

            @Override
            public Optional<ProfileSnapshot> find(NpcAlias alias) {
                CompanionRecord current = companions.byNpcUuid(alias.value());
                if (current == null) {
                    UUID legacy = legacyProfile.apply(alias.value());
                    current = legacy == null ? null : companions.get(legacy);
                }
                return Optional.ofNullable(current).map(CommandPersistenceView::from);
            }
        };
    }

    /**
     * True for a companion imported from 3.x or 4.x whose body has not been seen or located yet:
     * its recorded position (exactly 0, 0, 0) and world are placeholders and must not be shown.
     */
    boolean neverSighted(@Nullable LinkedNpcRecord record) {
        return find(record).map(profile -> companions.get(profile.profileId().value()))
                .map(CompanionRecord::neverSighted).orElse(false);
    }

    /**
     * The index's last recorded place of a companion that is out in the world, for a card whose
     * body is not loaded. Empty when the record is not out, or is an import not located yet.
     */
    Optional<CompanionLocation> livePlace(@Nullable LinkedNpcRecord record) {
        return find(record).map(profile -> companions.get(profile.profileId().value()))
                .filter(current -> current.location().kind() == LocationKind.LIVE && !current.neverSighted()
                        && current.location().world() != null)
                .map(CompanionRecord::location);
    }

    /** The saved panel of an unloaded companion, from its record's in-memory summary. */
    CommandSavedNpcPanelSnapshot savedPanel(LinkedNpcRecord record, UUID viewer) {
        return find(record).map(profile -> companions.get(profile.profileId().value()))
                .map(CommandSavedNpcPanelSnapshot::fromSummary).orElse(null);
    }

    /**
     * Panel membership for one tool. Item metadata decides: generic items keep their selection
     * only there (commit 11106c9a4), so a record without the tool id still belongs.
     */
    List<LinkedNpcRecord> linkedRecordsForTool(List<LinkedNpcRecord> records, @Nullable String toolId) {
        return records;
    }

    /**
     * Resolves one command record by stable profile first and known alias second. On the
     * companion index a known alias is the record's current body, else a body an imported world
     * knew for it.
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
                lifecycleState(record.location()), record.reviveAvailableAtMs(), record.diedAtMs());
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

    /** Immutable command-facing subset of one companion record. */
    record ProfileSnapshot(
            @Nonnull ProfileId profileId,
            @Nullable UUID currentNpcUuid,
            @Nullable UUID ownerUuid,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable String customName,
            @Nonnull Set<UUID> toolIds,
            @Nonnull LifecycleState lifecycleState,
            long restorationAvailableAtMs,
            long diedAtMs
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

        /** Full revive cooldown of the current death (wall clock), or 0 when the death time is unknown. */
        long restorationCooldownMs() {
            return dead() && diedAtMs > 0L && restorationAvailableAtMs >= diedAtMs
                    ? restorationAvailableAtMs - diedAtMs : 0L;
        }

        boolean blocksLiveAction() {
            return lifecycleState != LifecycleState.ACTIVE
                    && lifecycleState != LifecycleState.UNLOADED;
        }
    }

    /** Profile lookups by id and by NPC alias. */
    private interface SnapshotLookup {
        @Nonnull
        Optional<ProfileSnapshot> find(@Nonnull ProfileId profileId);

        @Nonnull
        Optional<ProfileSnapshot> find(@Nonnull NpcAlias alias);
    }
}
