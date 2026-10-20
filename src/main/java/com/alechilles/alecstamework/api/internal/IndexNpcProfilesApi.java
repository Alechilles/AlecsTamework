package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.OwnedTraitSnapshot;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Public profile reads over the companion index. Every read is synchronous and lock-free, so
 * it is safe from any thread. A profile id is the record's UUID as text; a blank or
 * unparsable id, and a released companion, read as "no profile".
 */
public final class IndexNpcProfilesApi implements NpcProfilesApi {
    private static final int MAX_OWNED_TRAIT_PAGE_SIZE = 64;

    private final CompanionQueries queries;
    private final Function<String, TwTraitConfig> traitConfigs;

    public IndexNpcProfilesApi(@Nonnull CompanionQueries queries) {
        this(queries, TwTraitConfig::resolveById);
    }

    IndexNpcProfilesApi(@Nonnull CompanionQueries queries,
                        @Nonnull Function<String, TwTraitConfig> traitConfigs) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.traitConfigs = Objects.requireNonNull(traitConfigs, "traitConfigs");
    }

    @Override
    public Optional<String> resolveProfileId(UUID npcUuid) {
        return Optional.ofNullable(recordByNpc(npcUuid)).map(record -> record.profileId().toString());
    }

    @Override
    public Optional<NpcProfileView> getByProfileId(String profileId) {
        return Optional.ofNullable(record(profileId)).map(CompanionRecordApiMapper::toProfileView);
    }

    @Override
    public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
        return Optional.ofNullable(recordByNpc(npcUuid)).map(CompanionRecordApiMapper::toProfileView);
    }

    @Override
    public Optional<String> getActiveSnapshot(String profileId, String snapshotType) {
        CompanionRecord record = record(profileId);
        if (record == null || snapshotType == null) {
            return Optional.empty();
        }
        String requested = snapshotType.trim().toLowerCase(Locale.ROOT);
        return requested.equals(CompanionRecordApiMapper.snapshotType(record))
                ? Optional.ofNullable(CompanionRecordApiMapper.snapshotJson(record))
                : Optional.empty();
    }

    @Override
    public Set<String> listActiveSnapshotTypes(String profileId) {
        CompanionRecord record = record(profileId);
        String type = record == null ? null : CompanionRecordApiMapper.snapshotType(record);
        return type == null ? Set.of() : Set.of(type);
    }

    /**
     * Answers from the index, so the stage is already complete. Dead and released companions
     * are excluded; captured, stored, housed, lost and live ones are included.
     */
    @Override
    public CompletionStage<Optional<List<OwnedTraitSnapshot>>> getOwnedTraitSnapshots(
            UUID ownerUuid, int offset, int limit) {
        Objects.requireNonNull(ownerUuid, "Owner UUID is required");
        if (offset < 0 || limit < 1 || limit > MAX_OWNED_TRAIT_PAGE_SIZE) {
            throw new IllegalArgumentException("Owned trait snapshot page must be within 0..64");
        }
        List<OwnedTraitSnapshot> page = queries.owned(ownerUuid).stream()
                .filter(record -> record.location().kind() != LocationKind.DEAD)
                .sorted(Comparator.comparing(record -> record.profileId().toString()))
                .skip(offset)
                .limit(limit)
                .map(record -> CompanionRecordApiMapper.toOwnedTraitSnapshot(record, traitConfigs))
                .toList();
        return CompletableFuture.completedFuture(Optional.of(page));
    }

    /** The position stored on the record, for a companion whose body is not loaded. */
    @Nullable
    Vector3View lastKnownPosition(@Nullable String profileId) {
        CompanionRecord record = record(profileId);
        return record == null ? null : CompanionRecordApiMapper.lastKnownPosition(record);
    }

    @Nullable
    private CompanionRecord record(@Nullable String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(profileId.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return visible(queries.get(id));
    }

    @Nullable
    private CompanionRecord recordByNpc(@Nullable UUID npcUuid) {
        return npcUuid == null ? null : visible(queries.byNpcUuid(npcUuid));
    }

    @Nullable
    private static CompanionRecord visible(@Nullable CompanionRecord record) {
        return record != null && record.location().kind() != LocationKind.RELEASED ? record : null;
    }
}
