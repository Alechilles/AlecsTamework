package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.internal.CompanionProfileApiMapper;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/** Released profile and snapshot reads composed from replacement canonical authorities. */
public final class ReplacementNpcProfilesApi implements NpcProfilesApi {
    private final PublicPersistenceQueries queries;
    private final long readTimeoutMs;

    public ReplacementNpcProfilesApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull Duration readTimeout
    ) {
        if (queries == null || readTimeout == null
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException(
                "Profile queries and positive timeout are required"
            );
        }
        this.queries = queries;
        readTimeoutMs = readTimeout.toMillis();
    }

    @Override
    public Optional<String> resolveProfileId(UUID npcUuid) {
        return stateByAlias(npcUuid)
                .map(state -> state.profileId().toString())
                .or(() -> readByAlias(npcUuid)
                        .map(model ->
                                model.identity().profileId().toString()));
    }

    @Override
    public Optional<NpcProfileView> getByProfileId(String profileId) {
        ProfileId parsed = parseProfile(profileId);
        if (parsed == null) {
            return Optional.empty();
        }
        return queries.projectedProfile(parsed)
                .map(CompanionProfileApiMapper::map);
    }

    @Override
    public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
        return stateByAlias(npcUuid)
                .map(CompanionProfileApiMapper::map)
                .or(() -> readByAlias(npcUuid).map(this::map));
    }

    @Override
    public Optional<String> getActiveSnapshot(
            String profileId,
            String snapshotType
    ) {
        if (snapshotType == null || snapshotType.isBlank()) {
            return Optional.empty();
        }
        String normalized = snapshotType.trim();
        return readByProfile(profileId)
                .flatMap(model -> model.currentSnapshots().stream()
                        .filter(snapshot -> snapshot.kind().value().equals(normalized))
                        .map(CompanionSnapshot::payloadJson)
                        .findFirst());
    }

    @Override
    public Set<String> listActiveSnapshotTypes(String profileId) {
        ProfileId parsed = parseProfile(profileId);
        if (parsed == null) {
            return Set.of();
        }
        LinkedHashSet<String> types = new LinkedHashSet<>();
        queries.projectedProfile(parsed).stream()
                .flatMap(state -> state.activeSnapshotKinds().stream())
                .map(kind -> kind.value())
                .sorted()
                .forEach(types::add);
        return Set.copyOf(types);
    }

    private Optional<CompanionProfileReadModel> readByProfile(String profileId) {
        ProfileId parsed = parseProfile(profileId);
        return parsed == null
                ? Optional.empty()
                : found(await(queries.findProfile(parsed)));
    }

    private Optional<CompanionProfileReadModel> readByAlias(UUID npcUuid) {
        return npcUuid == null
                ? Optional.empty()
                : found(await(queries.findProfile(
                        new NpcAlias(npcUuid)
                )));
    }

    private Optional<CompanionProfileProjectionState> stateByAlias(
            UUID npcUuid
    ) {
        return npcUuid == null
                ? Optional.empty()
                : queries.projectedProfile(new NpcAlias(npcUuid));
    }

    private NpcProfileView map(CompanionProfileReadModel model) {
        return CompanionProfileApiMapper.map(
                CompanionProfileProjectionState.compose(
                        model.identity(),
                        model.currentAlias(),
                        model.lifecycle(),
                        model.toolLinks(),
                        model.currentSnapshots(),
                        model.currentCoopSlot()
                )
        );
    }

    private ProfileId parseProfile(String profileId) {
        try {
            return profileId == null ? null : ProfileId.parse(profileId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private <T> Optional<T> found(PersistenceReadResult<T> result) {
        return result instanceof PersistenceReadResult.Found<T> found
                ? Optional.of(found.value())
                : Optional.empty();
    }

    private <T> PersistenceReadResult<T> await(
            CompletionStage<PersistenceReadResult<T>> stage
    ) {
        try {
            return stage.toCompletableFuture().get(
                    readTimeoutMs,
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception failure) {
            return null;
        }
    }
}
