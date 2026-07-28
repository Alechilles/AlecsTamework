package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves or inserts one exact companion projection on the current world thread.
 *
 * <p>The projection marker is a receipt for the deterministic entity UUID and all durable state
 * installed before the entity entered its store. Post-add display, health, and attachment work is
 * deliberately best-effort presentation: it is not part of the durable receipt and cannot turn a
 * verified projection into an unconfirmed persistence operation.</p>
 */
public final class HytaleCompanionProjectionSpawnExecutor {
    private final PlannedNpcProjectionSpawner spawner;
    private final PlannedNpcProjectionPostAddService postAdd;

    /** Creates the production executor around the single planned-projection spawner. */
    public HytaleCompanionProjectionSpawnExecutor() {
        this(
                new PlannedNpcProjectionSpawner(),
                new PlannedNpcProjectionPostAddService()
        );
    }

    HytaleCompanionProjectionSpawnExecutor(
            @Nonnull PlannedNpcProjectionSpawner spawner,
            @Nonnull PlannedNpcProjectionPostAddService postAdd
    ) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.postAdd = Objects.requireNonNull(postAdd, "postAdd");
    }

    /**
     * Resolves the exact receipt before invoking the lazy snapshot decoder or attempting insertion.
     */
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull ProjectionCommand command,
            @Nonnull ProjectionStateResolver stateResolver
    ) {
        if (world == null || store == null || command == null || stateResolver == null) {
            return LiveOperationResult.unknown(
                    code(command, "request_invalid"),
                    null
            );
        }
        try {
            store.assertThread();
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    code(command, "world_thread_unavailable"),
                    failure
            );
        }
        return execute(
                command,
                stateResolver,
                new HytaleCompanionProjectionAttemptGateway(
                        world,
                        store,
                        spawner,
                        postAdd
                )
        );
    }

    @Nonnull
    LiveOperationResult execute(
            @Nonnull ProjectionCommand command,
            @Nonnull ProjectionStateResolver stateResolver,
            @Nonnull AttemptGateway attempts
    ) {
        ReceiptResult initial = safeProbe(command, attempts);
        if (initial.status() == ReceiptStatus.MATCH) {
            return LiveOperationResult.confirmed(
                    code(command, "spawn_receipt_confirmed")
            );
        }
        if (initial.status() == ReceiptStatus.CONFLICT) {
            return LiveOperationResult.unknown(
                    code(command, "spawn_receipt_conflict"),
                    initial.cause()
            );
        }

        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                safeResolve(command, stateResolver);
        if (decoded instanceof SnapshotDecodeResult.Failed<?> failed) {
            return LiveOperationResult.unknown(
                    code(command, failed.code()),
                    failed.cause()
            );
        }
        CoopResidentStateSnapshot snapshot =
                ((SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot>) decoded)
                        .value();
        String invariantFailure = snapshotInvariant(command, snapshot);
        if (invariantFailure != null) {
            return LiveOperationResult.unknown(
                    code(command, invariantFailure),
                    null
            );
        }

        SpawnAttempt spawn = safeSpawn(command, snapshot, attempts);
        if (spawn.status() == SpawnStatus.SPAWNED) {
            return LiveOperationResult.confirmed(
                    code(command, "spawned")
            );
        }

        ReceiptResult afterFailure = safeProbe(command, attempts);
        if (afterFailure.status() == ReceiptStatus.MATCH) {
            return LiveOperationResult.confirmed(
                    code(command, "spawn_receipt_confirmed")
            );
        }
        if (afterFailure.status() == ReceiptStatus.CONFLICT) {
            return LiveOperationResult.unknown(
                    code(command, "spawn_receipt_conflict"),
                    afterFailure.cause()
            );
        }
        if (spawn.status() == SpawnStatus.ROLE_NOT_FOUND
                || spawn.status() == SpawnStatus.SPAWN_FAILED) {
            return LiveOperationResult.retryable(
                    code(command, lower(spawn.status())),
                    spawn.cause()
            );
        }
        return LiveOperationResult.unknown(
                code(command, lower(spawn.status())),
                spawn.cause()
        );
    }

    @Nonnull
    private ReceiptResult safeProbe(
            ProjectionCommand command,
            AttemptGateway attempts
    ) {
        try {
            ReceiptResult result = attempts.probe(
                    command,
                    command.projectionMarker()
            );
            return result == null || result.status() == null
                    ? ReceiptResult.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptResult.conflict(failure);
        }
    }

    @Nonnull
    private SnapshotDecodeResult<CoopResidentStateSnapshot> safeResolve(
            ProjectionCommand command,
            ProjectionStateResolver resolver
    ) {
        try {
            SnapshotDecodeResult<CoopResidentStateSnapshot> result =
                    resolver.resolve();
            return result == null
                    ? failed("projection_state_missing", null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return failed("projection_state_resolution_failed", failure);
        }
    }

    @Nonnull
    private SpawnAttempt safeSpawn(
            ProjectionCommand command,
            CoopResidentStateSnapshot snapshot,
            AttemptGateway attempts
    ) {
        try {
            SpawnAttempt result = attempts.spawn(
                    command,
                    snapshot,
                    command.projectionMarker()
            );
            return result == null || result.status() == null
                    ? SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, failure);
        }
    }

    @Nonnull
    private SnapshotDecodeResult.Failed<CoopResidentStateSnapshot> failed(
            String code,
            @Nullable Throwable cause
    ) {
        return new SnapshotDecodeResult.Failed<>(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                code,
                cause
        );
    }

    @Nullable
    private String snapshotInvariant(
            ProjectionCommand command,
            CoopResidentStateSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.npcUuid() == null
                || snapshot.roleId() == null || snapshot.roleId().isBlank()) {
            return "projection_state_incomplete";
        }
        if (command.sourceNpcUuid() != null
                && !command.sourceNpcUuid().equals(snapshot.npcUuid())) {
            return "projection_source_identity_mismatch";
        }
        return null;
    }

    static boolean receiptMatches(
            UUID plannedNpcUuid,
            @Nullable UUID componentUuid,
            @Nullable NPCEntity npc,
            TameworkProjectionIdentityComponent expected,
            @Nullable TameworkProjectionIdentityComponent actual
    ) {
        return npc != null
                && plannedNpcUuid.equals(componentUuid)
                && plannedNpcUuid.equals(npc.getUuid())
                && markersEqual(expected, actual);
    }

    static boolean markersEqual(
            @Nonnull TameworkProjectionIdentityComponent expected,
            @Nullable TameworkProjectionIdentityComponent actual
    ) {
        return actual != null
                && Objects.equals(expected.getProfileId(), actual.getProfileId())
                && Objects.equals(expected.getOperationId(), actual.getOperationId())
                && Objects.equals(expected.getProjectionKind(), actual.getProjectionKind())
                && Objects.equals(expected.getSlotKey(), actual.getSlotKey())
                && Objects.equals(expected.getSourceNpcUuid(), actual.getSourceNpcUuid())
                && expected.getGeneration() == actual.getGeneration();
    }

    private static String code(
            @Nullable ProjectionCommand command,
            String suffix
    ) {
        String prefix = command == null ? "projection" : command.operationCode();
        return prefix + "_" + suffix;
    }

    private static String lower(SpawnStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Immutable receipt identity and frozen placement for one projection insertion. */
    public record ProjectionCommand(
            @Nonnull String operationCode,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId,
            @Nonnull String projectionKind,
            @Nonnull NpcAlias targetAlias,
            @Nullable UUID sourceNpcUuid,
            @Nonnull String spawnReceiptKey,
            long generation,
            @Nonnull CompanionSpawnPlacement placement
    ) {
        public ProjectionCommand {
            operationCode = requireText(operationCode, "Projection operation code");
            projectionKind = requireText(projectionKind, "Projection kind");
            spawnReceiptKey = requireText(spawnReceiptKey, "Projection receipt");
            if (profileId == null || operationId == null || targetAlias == null
                    || placement == null || generation < 0) {
                throw new IllegalArgumentException(
                        "Complete nonnegative projection command is required"
                );
            }
        }

        @Nonnull
        TameworkProjectionIdentityComponent projectionMarker() {
            return new TameworkProjectionIdentityComponent(
                    profileId.toString(),
                    operationId.toString(),
                    projectionKind,
                    spawnReceiptKey,
                    sourceNpcUuid,
                    generation
            );
        }

        private static String requireText(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " is required");
            }
            return value.trim();
        }

    }

    /** Lazy full-state decoder that is never invoked when an exact receipt already exists. */
    @FunctionalInterface
    public interface ProjectionStateResolver {
        @Nonnull
        SnapshotDecodeResult<CoopResidentStateSnapshot> resolve();
    }

    enum ReceiptStatus {
        MATCH,
        ABSENT,
        CONFLICT
    }

    record ReceiptResult(
            @Nonnull ReceiptStatus status,
            @Nullable Throwable cause
    ) {
        static ReceiptResult match() {
            return new ReceiptResult(ReceiptStatus.MATCH, null);
        }

        static ReceiptResult absent() {
            return new ReceiptResult(ReceiptStatus.ABSENT, null);
        }

        static ReceiptResult conflict(@Nullable Throwable cause) {
            return new ReceiptResult(ReceiptStatus.CONFLICT, cause);
        }
    }

    enum SpawnStatus {
        SPAWNED,
        INVALID_REQUEST,
        ROLE_NOT_FOUND,
        SPAWN_FAILED,
        IDENTITY_MISMATCH
    }

    record SpawnAttempt(
            @Nonnull SpawnStatus status,
            @Nullable Throwable cause
    ) {
        static SpawnAttempt spawned() {
            return new SpawnAttempt(SpawnStatus.SPAWNED, null);
        }

        static SpawnAttempt failed(
                SpawnStatus status,
                @Nullable Throwable cause
        ) {
            return new SpawnAttempt(status, cause);
        }
    }

    interface AttemptGateway {
        @Nonnull
        ReceiptResult probe(
                @Nonnull ProjectionCommand command,
                @Nonnull TameworkProjectionIdentityComponent expectedMarker
        );

        @Nonnull
        SpawnAttempt spawn(
                @Nonnull ProjectionCommand command,
                @Nonnull CoopResidentStateSnapshot snapshot,
                @Nonnull TameworkProjectionIdentityComponent marker
        );
    }
}
