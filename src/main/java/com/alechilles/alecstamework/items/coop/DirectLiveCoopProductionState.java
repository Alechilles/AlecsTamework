package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionValue;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/** Profile-scoped managed-coop production watermark over the existing extension authority. */
public final class DirectLiveCoopProductionState {
    private static final String NAMESPACE = "Alechilles:Tamework";
    private static final String DATA_KEY = "managed-coop-production-v1";
    private static final int MAX_CACHED_LIFE_STAGES = 512;
    private static final Logger LOGGER = Logger.getLogger(
            DirectLiveCoopProductionState.class.getName()
    );
    private final PersistenceDomainFacades facades;
    private final ConcurrentHashMap<ProfileId, Boolean> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileId, CachedLifeStage> stageByProfile = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileId, Boolean> activeTimeReads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileId, Watermark> optimisticWatermarks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileId, Boolean> malformedWarnings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileId, Boolean> checkpointWarnings = new ConcurrentHashMap<>();
    private final CoopResidentStateSnapshotCodec snapshots = new CoopResidentStateSnapshotCodec();

    DirectLiveCoopProductionState(@Nonnull PersistenceDomainFacades facades) {
        this.facades = facades;
    }

    /** Returns no watermark for older residents; callers must initialize it at the current clock. */
    @Nonnull
    public Optional<Watermark> watermark(@Nonnull ProfileId profileId) {
        Watermark optimistic = optimisticWatermarks.get(profileId);
        Optional<Watermark> projected = projectedWatermark(profileId);
        if (optimistic == null) return projected;
        if (projected.filter(value -> value.eligibleMs() >= optimistic.eligibleMs()).isPresent()) {
            optimisticWatermarks.remove(profileId, optimistic);
            return projected;
        }
        return Optional.of(optimistic);
    }

    public boolean malformedWatermark(@Nonnull ProfileId profileId) {
        boolean malformed = optimisticWatermarks.get(profileId) == null
                && facades.queries().projectedExtension(key(profileId)).isPresent()
                && projectedWatermark(profileId).isEmpty();
        if (malformed && malformedWarnings.putIfAbsent(profileId, Boolean.TRUE) == null) {
            LOGGER.warning("Managed-coop production is disabled for malformed watermark: profile="
                    + profileId);
        }
        return malformed;
    }

    /** Submits a best-effort durable advance after produce was added to the loaded container. */
    public boolean pending(@Nonnull ProfileId profileId) {
        return pending.containsKey(profileId);
    }

    public void record(@Nonnull ProfileId profileId, long eligibleMs, long expectedRevision) {
        submit(profileId, Math.max(0L, eligibleMs), Math.max(0L, expectedRevision), false);
    }

    private void submit(
            @Nonnull ProfileId profileId,
            long eligibleMs,
            long expectedRevision,
            boolean retry
    ) {
        if (pending.putIfAbsent(profileId, Boolean.TRUE) != null) return;
        submitPending(profileId, eligibleMs, expectedRevision, retry);
    }

    private void submitPending(
            @Nonnull ProfileId profileId,
            long eligibleMs,
            long expectedRevision,
            boolean retry
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("eligibleMs", eligibleMs);
        ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                key(profileId), ProfileExtensionMutationAction.PUT, expectedRevision,
                json.toString(), System.currentTimeMillis()
        );
        IdempotencyKey idempotency = new IdempotencyKey(
                "managed-coop-produce:" + profileId + ":" + expectedRevision + ":" + eligibleMs
                        + ":" + retry
        );
        // Keep the advanced local value until the durable projection catches up. Its revision is
        // deliberately the last known durable revision, never a guessed next revision.
        optimisticWatermarks.put(profileId, new Watermark(eligibleMs, expectedRevision));
        facades.operations().mutateExtension(OperationId.create(), idempotency, mutation)
                .completion().whenComplete((result, failure) -> {
                    Watermark settled = settle(profileId, eligibleMs);
                    boolean persisted = projectedWatermark(profileId)
                            .map(value -> value.eligibleMs() >= eligibleMs)
                            .orElse(false);
                    boolean published = failure == null && result != null
                            && result.status() == com.alechilles.alecstamework.persistence.operation
                                    .OperationWorkflowResult.Status.PUBLISHED;
                    if (!retry && (!published || !persisted)) {
                        // Keep the profile pending while retrying so no sweep can write over this
                        // local watermark between completion and the replacement checkpoint.
                        submitPending(profileId, eligibleMs, settled.revision(), true);
                        return;
                    }
                    if (retry && (!published || !persisted)
                            && checkpointWarnings.putIfAbsent(profileId, Boolean.TRUE) == null) {
                        String message = "Managed-coop production watermark remains undurable: profile="
                                + profileId;
                        if (failure == null) LOGGER.warning(message);
                        else LOGGER.log(Level.WARNING, message, failure);
                    }
                    pending.remove(profileId);
                });
    }

    @Nonnull
    private Watermark settle(@Nonnull ProfileId profileId, long eligibleMs) {
        Optional<Watermark> projected = projectedWatermark(profileId);
        if (projected.filter(value -> value.eligibleMs() >= eligibleMs).isPresent()) {
            optimisticWatermarks.remove(profileId);
            return projected.orElseThrow();
        }
        Watermark settled = projected
                .map(value -> new Watermark(eligibleMs, value.revision()))
                .orElseGet(() -> new Watermark(eligibleMs, 0L));
        optimisticWatermarks.put(profileId, settled);
        return settled;
    }

    /** Returns a cached active-animal clock and requests a snapshot refresh without blocking. */
    @Nonnull
    public Optional<Long> activeTime(@Nonnull ProfileId profileId, @Nonnull SnapshotId expectedSnapshotId) {
        CachedLifeStage cached = stageByProfile.get(profileId);
        if (cached != null && cached.snapshotId().equals(expectedSnapshotId)) {
            return Optional.of(AnimalProgressionService.activeTimeMs(cached.lifeStage()));
        }
        trimLifeStageCache();
        if (activeTimeReads.putIfAbsent(profileId, Boolean.TRUE) == null) {
            facades.queries().findProfile(profileId).whenComplete((read, failure) -> {
                activeTimeReads.remove(profileId);
                CachedLifeStage decoded = decodeLifeStage(read);
                if (decoded != null) {
                    stageByProfile.put(profileId, decoded);
                }
            });
        }
        return Optional.empty();
    }

    public boolean deathDue(@Nonnull ProfileId profileId, String roleId) {
        CachedLifeStage cached = stageByProfile.get(profileId);
        return AnimalProgressionService.deathDue(cached == null ? null : cached.lifeStage(), roleId);
    }

    @Nonnull
    private static ProfileExtensionKey key(@Nonnull ProfileId profileId) {
        return new ProfileExtensionKey(profileId, NAMESPACE, DATA_KEY);
    }

    @Nonnull
    private static Optional<Watermark> decode(@Nonnull ProfileExtensionProjectionValue value) {
        try {
            JsonObject json = JsonParser.parseString(value.jsonPayload()).getAsJsonObject();
            if (!json.has("eligibleMs")) return Optional.empty();
            return Optional.of(new Watermark(Math.max(0L, json.get("eligibleMs").getAsLong()), value.revision()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void trimLifeStageCache() {
        if (stageByProfile.size() >= MAX_CACHED_LIFE_STAGES) {
            // Entries are only a non-blocking snapshot-read cache. Clearing stale departed
            // residents is safer than retaining an unbounded history of profiles.
            stageByProfile.clear();
        }
    }

    @Nonnull
    private Optional<Watermark> projectedWatermark(@Nonnull ProfileId profileId) {
        return facades.queries().projectedExtension(key(profileId))
                .flatMap(DirectLiveCoopProductionState::decode);
    }

    private CachedLifeStage decodeLifeStage(PersistenceReadResult<CompanionProfileReadModel> read) {
        if (!(read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found)) return null;
        return found.value().currentSnapshots().stream()
                .filter(snapshot -> TameworkSnapshotCodecs.COOP.equals(snapshot.kind()))
                .findFirst()
                .map(snapshot -> {
                    var decoded = snapshots.decode(snapshot.payloadJson()).snapshotOrNull();
                    return decoded == null || decoded.lifeStage() == null ? null
                            : new CachedLifeStage(snapshot.snapshotId(), decoded.lifeStage());
                })
                .orElse(null);
    }

    public record Watermark(long eligibleMs, long revision) { }
    private record CachedLifeStage(SnapshotId snapshotId, TameworkLifeStageComponent lifeStage) { }
}
