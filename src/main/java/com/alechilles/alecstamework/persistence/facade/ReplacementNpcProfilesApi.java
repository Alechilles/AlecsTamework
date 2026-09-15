package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.OwnedTraitSnapshot;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.api.internal.CompanionProfileApiMapper;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/** Released profile and snapshot reads composed from replacement canonical authorities. */
public final class ReplacementNpcProfilesApi implements NpcProfilesApi {
    private static final int MAX_OWNED_TRAIT_PAGE_SIZE = 64;
    private static final com.alechilles.alecstamework.companion.snapshot
            .SnapshotCodecRegistry SNAPSHOT_CODECS =
            TameworkSnapshotCodecs.create();
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

    @Override
    @Nonnull
    public CompletionStage<Optional<List<OwnedTraitSnapshot>>>
    getOwnedTraitSnapshots(UUID ownerUuid, int offset, int limit) {
        Objects.requireNonNull(ownerUuid, "Owner UUID is required");
        if (offset < 0 || limit < 1 || limit > MAX_OWNED_TRAIT_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Owned trait snapshot page must be within 0..64"
            );
        }
        final List<CompanionProfileProjectionState> page;
        try {
            page = queries.projectedProfileSnapshot().values().stream()
                    .filter(profile -> ownedBy(profile, ownerUuid))
                    .filter(profile -> eligibleLifecycle(profile.lifecycleState()))
                    .sorted(Comparator.comparing(
                            profile -> profile.profileId().toString()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (page.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(List.of()));
        }
        List<CompletionStage<Optional<OwnedTraitSnapshot>>> reads = page.stream()
                .map(profile -> readOwnedTraitSnapshot(ownerUuid, profile))
                .toList();
        CompletableFuture<?>[] futures = reads.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).handle((ignored, failure) -> {
            if (failure != null) {
                return Optional.<List<OwnedTraitSnapshot>>empty();
            }
            ArrayList<OwnedTraitSnapshot> result = new ArrayList<>();
            for (CompletionStage<Optional<OwnedTraitSnapshot>> read : reads) {
                read.toCompletableFuture().join().ifPresent(result::add);
            }
            return Optional.of(List.copyOf(result));
        });
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

    private CompletionStage<Optional<OwnedTraitSnapshot>> readOwnedTraitSnapshot(
            UUID ownerUuid,
            CompanionProfileProjectionState projected
    ) {
        try {
            return queries.findProfile(projected.profileId()).thenApply(read -> {
                if (read instanceof PersistenceReadResult.Failed<?>) {
                    throw new IllegalStateException("owned_trait_snapshot_read_failed");
                }
                if (!(read instanceof PersistenceReadResult.Found<
                        CompanionProfileReadModel> found)) {
                    return Optional.empty();
                }
                CompanionProfileReadModel profile = found.value();
                if (profile.lifecycle().ownerId() == null
                        || !ownerUuid.equals(profile.lifecycle().ownerId().value())
                        || !eligibleLifecycle(profile.lifecycle().state())) {
                    return Optional.empty();
                }
                return Optional.of(mapOwnedTraitSnapshot(profile));
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private OwnedTraitSnapshot mapOwnedTraitSnapshot(
            CompanionProfileReadModel profile
    ) {
        CompanionSnapshot newest = null;
        CoopResidentStateSnapshot state = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            CoopResidentStateSnapshot decoded = decodeFullState(snapshot);
            if (decoded != null && (newest == null
                    || snapshot.createdAtMs() > newest.createdAtMs())) {
                newest = snapshot;
                state = decoded;
            }
        }
        if (state == null) {
            return unavailable(profile);
        }
        long snapshotCreatedAtMs = newest == null ? 0L : newest.createdAtMs();
        if (state.traits() == null) {
            return unavailable(profile, null, snapshotCreatedAtMs);
        }
        TameworkTraitsComponent traits = state.traits();
        TwTraitConfig config;
        try {
            config = TwTraitConfig.resolveById(traits.getConfigId());
        } catch (RuntimeException | LinkageError failure) {
            return unavailable(profile, traits.getConfigId(), snapshotCreatedAtMs);
        }
        if (config == null) {
            return unavailable(
                    profile, traits.getConfigId(), snapshotCreatedAtMs
            );
        }
        Map<String, TwTraitConfig.TraitDefinition> definitions = new java.util.HashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition != null && definition.getId() != null
                    && !definition.getId().isBlank()) {
                definitions.put(
                        definition.getId().trim().toLowerCase(Locale.ROOT),
                        definition
                );
            }
        }
        ArrayList<ProgressionView.TraitValueView> values = new ArrayList<>();
        for (TameworkTraitsComponent.TraitValue value : traits.getTraitValues()) {
            if (value == null || value.getId() == null || value.getId().isBlank()
                    || !Double.isFinite(value.getValue())) {
                return unavailable(
                        profile, traits.getConfigId(), snapshotCreatedAtMs
                );
            }
            TwTraitConfig.TraitDefinition definition = definitions.get(
                    value.getId().trim().toLowerCase(Locale.ROOT)
            );
            if (definition == null) {
                return unavailable(
                        profile, traits.getConfigId(), snapshotCreatedAtMs
                );
            }
            int direction = TraitModifierService.resolveMeritDirection(
                    definition.getEffectKey()
            );
            values.add(new ProgressionView.TraitValueView(
                    value.getId(),
                    value.getValue(),
                    definition.getEffectKey(),
                    definition.getDefaultValue(),
                    definition.getBreedingMin(),
                    definition.getBreedingMax(),
                    direction,
                    TraitModifierService.resolveSignedMerit(
                            definition, value.getValue()),
                    TraitModifierService.resolveSizeMeatHideYieldBonus(
                            definition, value.getValue())
            ));
        }
        return new OwnedTraitSnapshot(
                profile.identity().profileId().toString(),
                profile.identity().roleId(),
                profile.identity().displayName(),
                traits.getConfigId(),
                values,
                true,
                snapshotCreatedAtMs
        );
    }

    private OwnedTraitSnapshot unavailable(CompanionProfileReadModel profile) {
        return unavailable(profile, null, 0L);
    }

    private OwnedTraitSnapshot unavailable(
            CompanionProfileReadModel profile,
            String traitConfigId,
            long snapshotCreatedAtMs
    ) {
        return new OwnedTraitSnapshot(
                profile.identity().profileId().toString(),
                profile.identity().roleId(),
                profile.identity().displayName(),
                traitConfigId,
                List.of(),
                false,
                snapshotCreatedAtMs
        );
    }

    private CoopResidentStateSnapshot decodeFullState(
            CompanionSnapshot snapshot
    ) {
        try {
            var decoded = SNAPSHOT_CODECS.decode(
                    snapshot, CoopResidentStateSnapshot.class
            );
            return decoded instanceof com.alechilles.alecstamework.companion
                    .snapshot.SnapshotDecodeResult.Decoded<
                    CoopResidentStateSnapshot> found ? found.value() : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private boolean ownedBy(
            CompanionProfileProjectionState profile,
            UUID ownerUuid
    ) {
        return profile.ownerId() != null
                && ownerUuid.equals(profile.ownerId().value());
    }

    private boolean eligibleLifecycle(LifecycleState state) {
        return state != LifecycleState.DEAD_REVIVABLE
                && state != LifecycleState.RELEASED;
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
