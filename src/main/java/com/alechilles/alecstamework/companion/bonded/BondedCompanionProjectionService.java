package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Coordinates durable bonded leases with disposable world projections. */
public final class BondedCompanionProjectionService {
    private final Durability durability;
    private final World world;
    private final BondedCompanionProjectionCleanupService cleanup;
    private final BondedCompanionProjectionValidator validator =
            new BondedCompanionProjectionValidator();
    private final BondedCompanionCleanupIntentFactory cleanupIntents =
            new BondedCompanionCleanupIntentFactory();
    private final Supplier<String> leaseTokens;
    private final Supplier<UUID> npcUuids;

    public BondedCompanionProjectionService(
            @Nonnull Durability durability,
            @Nonnull World world,
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull Supplier<String> leaseTokens,
            @Nonnull Supplier<UUID> npcUuids
    ) {
        this.durability = Objects.requireNonNull(durability, "durability");
        this.world = Objects.requireNonNull(world, "world");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.leaseTokens = Objects.requireNonNull(leaseTokens, "leaseTokens");
        this.npcUuids = Objects.requireNonNull(npcUuids, "npcUuids");
    }

    /** Creates durable ACTIVE/lease/recovery state before one world-thread spawn. */
    @Nonnull
    public SummonResult summon(@Nonnull SummonRequest request) {
        Objects.requireNonNull(request, "request");
        String token = text(leaseTokens.get(), "leaseToken");
        UUID npcUuid = Objects.requireNonNull(npcUuids.get(), "plannedNpcUuid");
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                request.ownerUuid(), request.rosterId(), request.profileId(), token,
                npcUuid, request.worldKey(), request.nowMs(), request.expiresAtMs(),
                BondedCompanionProjectionValidator.LeasePhase.PENDING
        );
        var recovery = cleanupIntents.projection(
                lease, "spawn-recovery", request.nowMs()
        );
        if (!durability.beginSummon(request, lease, recovery)) {
            return new SummonResult(SummonStatus.DURABILITY_REJECTED, null);
        }
        TameworkProjectionIdentityComponent marker =
                TameworkProjectionIdentityComponent.bondedCompanion(
                        lease.profileId(), lease.leaseToken()
                );
        SpawnResult spawned = safeSpawn(new SpawnPlan(
                lease, request.roleId(), request.snapshot(), marker
        ));
        if (spawned.status() != SpawnStatus.SPAWNED
                || !npcUuid.equals(spawned.npcUuid())) {
            String reason = spawnFailureReason(spawned, npcUuid);
            return rollbackFailedSpawn(lease, spawned, reason, request.nowMs());
        }
        if (!safeConfirmSpawn(lease, spawned.npcUuid())) {
            return rollbackFailedSpawn(
                    lease, spawned, "SPAWN_CONFIRM_FAILED", request.nowMs()
            );
        }
        return new SummonResult(SummonStatus.ACTIVE, live(lease));
    }

    /** Captures full live state first, then durably stores and enqueues exact cleanup. */
    @Nonnull
    public StoreResult store(@Nonnull StoreRequest request) {
        Objects.requireNonNull(request, "request");
        BondedCompanionProjectionValidator.Projection projection =
                world.readExact(request.lease());
        var validation = validator.validate(
                request.lease(), projection == null ? List.of() : List.of(projection)
        );
        if (validation.status() != BondedCompanionProjectionValidator.Status.VALID
                || projection.snapshot() == null) {
            return new StoreResult(StoreStatus.PROJECTION_NOT_FOUND, null);
        }
        var intent = cleanupIntents.projection(
                request.lease(), "store", request.nowMs()
        );
        if (!durability.storeAndEnqueueCleanup(
                request, projection.snapshot(), intent)) {
            return new StoreResult(StoreStatus.DURABILITY_REJECTED, null);
        }
        BondedCompanionProjectionCleanupService.Outcome outcome = cleanup.recover(intent);
        StoreStatus status = outcome == BondedCompanionProjectionCleanupService.Outcome.REMOVED
                || outcome == BondedCompanionProjectionCleanupService.Outcome.ALREADY_MISSING
                ? StoreStatus.STORED : StoreStatus.STORED_CLEANUP_PENDING;
        return new StoreResult(status, intent);
    }

    /** Reconciles non-death disappearance to STORED and removes exact marker matches. */
    @Nonnull
    public ReconcileResult reconcile(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull List<BondedCompanionProjectionValidator.Projection> observed,
            @Nonnull RecoveryCause cause,
            long observedAtMs
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cause, "cause");
        var validation = validator.validate(lease, observed);
        boolean expired = BondedCompanionExpirySystem.isExpired(
                lease.expiresAtMs(), observedAtMs
        );
        boolean forced = expired || cause == RecoveryCause.EXPIRED
                || cause == RecoveryCause.WORLD_TRANSFER
                || cause == RecoveryCause.LOGOUT;
        boolean interrupted = lease.phase()
                == BondedCompanionProjectionValidator.LeasePhase.PENDING;
        if (!forced && !interrupted
                && validation.status() == BondedCompanionProjectionValidator.Status.VALID) {
            return new ReconcileResult(ReconcileStatus.ACTIVE_VALID, List.of());
        }
        String reason = expired ? "LEASE_EXPIRED"
                : interrupted ? "SPAWN_INTERRUPTED"
                : reason(cause, validation.status());
        BondedCompanionSnapshot snapshot = snapshot(validation);
        List<BondedCompanionProjectionCleanupService.CleanupIntent> intents =
                cleanupIntents.projections(
                        lease, validation.exactMatches(), reason, observedAtMs
                );
        if (!durability.reconcileStored(lease, snapshot, intents, reason)) {
            return new ReconcileResult(ReconcileStatus.DURABILITY_REJECTED, intents);
        }
        for (var intent : intents) {
            cleanup.recover(intent);
        }
        return new ReconcileResult(ReconcileStatus.STORED, intents);
    }

    /** Authors DEAD only for an explicitly confirmed exact projection death. */
    @Nonnull
    public ReconcileResult confirmDeath(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull BondedCompanionProjectionValidator.Projection projection,
            long diedAtMs
    ) {
        var validation = validator.validate(lease, List.of(projection));
        if (validation.status() != BondedCompanionProjectionValidator.Status.VALID) {
            return new ReconcileResult(ReconcileStatus.IDENTITY_MISMATCH, List.of());
        }
        return durability.confirmDeath(lease, projection.snapshot(), diedAtMs)
                ? new ReconcileResult(ReconcileStatus.DEAD, List.of())
                : new ReconcileResult(ReconcileStatus.DURABILITY_REJECTED, List.of());
    }

    private SpawnResult safeSpawn(SpawnPlan plan) {
        try {
            SpawnResult result = world.spawn(plan);
            return result == null ? SpawnResult.failed() : result;
        } catch (RuntimeException failure) {
            return SpawnResult.failed();
        }
    }

    private boolean safeConfirmSpawn(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            UUID spawnedNpcUuid
    ) {
        try {
            return durability.confirmSpawn(lease, spawnedNpcUuid);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private SummonResult rollbackFailedSpawn(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            SpawnResult spawned,
            String reason,
            long nowMs
    ) {
        List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups =
                spawnFailureCleanups(lease, spawned, reason, nowMs);
        boolean stored;
        try {
            stored = durability.failSpawnAndEnqueueCleanup(
                    lease, cleanups, reason
            );
        } catch (RuntimeException failure) {
            stored = false;
        }
        if (!stored) {
            return new SummonResult(SummonStatus.SPAWN_ROLLBACK_PENDING, lease);
        }
        for (var intent : cleanups) {
            cleanup.recover(intent);
        }
        return new SummonResult(SummonStatus.SPAWN_FAILED_STORED, lease);
    }

    private List<BondedCompanionProjectionCleanupService.CleanupIntent>
            spawnFailureCleanups(
                    BondedCompanionProjectionValidator.LeaseExpectation lease,
                    SpawnResult spawned,
                    String reason,
                    long nowMs
            ) {
        ArrayList<BondedCompanionProjectionCleanupService.CleanupIntent> result =
                new ArrayList<>();
        UUID observedUuid = spawned == null ? null : spawned.npcUuid();
        if (observedUuid != null) {
            result.add(cleanupIntents.projection(
                    lease, observedUuid, lease.worldKey(), reason, nowMs
            ));
        }
        if (!lease.liveNpcUuid().equals(observedUuid)) {
            result.add(cleanupIntents.projection(
                    lease, lease.liveNpcUuid(), lease.worldKey(), reason, nowMs
            ));
        }
        return List.copyOf(result);
    }

    private String spawnFailureReason(SpawnResult spawned, UUID plannedNpcUuid) {
        if (spawned.status() == SpawnStatus.RETRY_REQUIRED) {
            return "SPAWN_RETRY_REQUIRED";
        }
        if (spawned.status() == SpawnStatus.IDENTITY_MISMATCH
                || spawned.npcUuid() != null
                && !plannedNpcUuid.equals(spawned.npcUuid())) {
            return "SPAWN_IDENTITY_MISMATCH";
        }
        return "SPAWN_FAILED";
    }

    private BondedCompanionProjectionValidator.LeaseExpectation live(
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                lease.ownerUuid(), lease.rosterId(), lease.profileId(),
                lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                lease.startedAtMs(), lease.expiresAtMs(),
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }

    @Nullable
    private BondedCompanionSnapshot snapshot(
            BondedCompanionProjectionValidator.Validation validation
    ) {
        if (validation.validProjection() != null) {
            return validation.validProjection().snapshot();
        }
        for (var projection : validation.exactMatches()) {
            if (projection.snapshot() != null) {
                return projection.snapshot();
            }
        }
        return null;
    }

    private String reason(
            RecoveryCause cause,
            BondedCompanionProjectionValidator.Status status
    ) {
        return switch (cause) {
            case EXPIRED -> "LEASE_EXPIRED";
            case WORLD_TRANSFER -> "WORLD_TRANSFER";
            case LOGOUT -> "LOGOUT";
            case STARTUP -> status == BondedCompanionProjectionValidator.Status.MISSING
                    ? "PROJECTION_MISSING" : status.name();
            case WORLD_LOAD, PLAYER_JOIN, MISSING_SCAN -> status.name();
        };
    }

    /** Atomic durable operations; implementations own their bonded DB transaction. */
    public interface Durability {
        /** Atomically authors ACTIVE, the lease, and its bounded spawn-recovery intent. */
        boolean beginSummon(
                @Nonnull SummonRequest request,
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent recovery
        );

        /** Atomically commits the exact NPC UUID to the lease and clears spawn recovery. */
        boolean confirmSpawn(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                @Nonnull UUID spawnedNpcUuid
        );

        /** Atomically stores, persists exact cleanup, and clears/replaces spawn recovery. */
        boolean failSpawnAndEnqueueCleanup(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                @Nonnull List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
                @Nonnull String reason
        );

        /** Atomically replaces the snapshot, invalidates the lease, stores, and enqueues cleanup. */
        boolean storeAndEnqueueCleanup(
                @Nonnull StoreRequest request,
                @Nonnull BondedCompanionSnapshot snapshot,
                @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent cleanup
        );

        /** Atomically returns a non-death exit to STORED and enqueues every exact cleanup. */
        boolean reconcileStored(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                @Nullable BondedCompanionSnapshot snapshot,
                @Nonnull List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
                @Nonnull String reason
        );

        /** Atomically authors DEAD only for the confirmed exact projection death. */
        boolean confirmDeath(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                @Nullable BondedCompanionSnapshot snapshot,
                long diedAtMs
        );
    }

    /** World-thread boundary; implementations use exact UUIDs and marker checks. */
    public interface World {
        @Nonnull SpawnResult spawn(@Nonnull SpawnPlan plan);

        @Nullable BondedCompanionProjectionValidator.Projection readExact(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
        );
    }

    public record SummonRequest(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            @Nonnull String roleId,
            @Nonnull BondedCompanionSnapshot snapshot,
            @Nonnull String worldKey,
            long nowMs,
            long expiresAtMs
    ) {
        public SummonRequest {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            profileId = text(profileId, "profileId");
            roleId = text(roleId, "roleId");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            worldKey = text(worldKey, "worldKey");
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
        }
    }

    public record StoreRequest(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            long expectedRevision,
            long nowMs
    ) {
        public StoreRequest {
            lease = Objects.requireNonNull(lease, "lease");
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
        }
    }

    public record SpawnPlan(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull String roleId,
            @Nonnull BondedCompanionSnapshot snapshot,
            @Nonnull TameworkProjectionIdentityComponent marker
    ) {
        public SpawnPlan {
            lease = Objects.requireNonNull(lease, "lease");
            roleId = text(roleId, "roleId");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            marker = Objects.requireNonNull(marker, "marker").clone();
        }
    }

    public record SpawnResult(
            @Nonnull SpawnStatus status,
            @Nullable UUID npcUuid
    ) {
        public SpawnResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == SpawnStatus.SPAWNED
                    || status == SpawnStatus.IDENTITY_MISMATCH)
                    && npcUuid == null) {
                throw new IllegalArgumentException("spawn status requires npcUuid");
            }
        }

        @Nonnull public static SpawnResult spawned(UUID npcUuid) {
            return new SpawnResult(
                    SpawnStatus.SPAWNED,
                    Objects.requireNonNull(npcUuid, "npcUuid")
            );
        }

        @Nonnull public static SpawnResult failed() {
            return new SpawnResult(SpawnStatus.FAILED, null);
        }

        @Nonnull public static SpawnResult retryRequired() {
            return new SpawnResult(SpawnStatus.RETRY_REQUIRED, null);
        }

        @Nonnull public static SpawnResult identityMismatch(UUID npcUuid) {
            return new SpawnResult(
                    SpawnStatus.IDENTITY_MISMATCH,
                    Objects.requireNonNull(npcUuid, "npcUuid")
            );
        }
    }

    public enum SpawnStatus { SPAWNED, RETRY_REQUIRED, IDENTITY_MISMATCH, FAILED }
    public enum SummonStatus {
        ACTIVE, DURABILITY_REJECTED, SPAWN_FAILED_STORED,
        SPAWN_ROLLBACK_PENDING
    }
    public enum StoreStatus {
        STORED, STORED_CLEANUP_PENDING, PROJECTION_NOT_FOUND, DURABILITY_REJECTED
    }
    public enum ReconcileStatus {
        ACTIVE_VALID, STORED, DEAD, IDENTITY_MISMATCH, DURABILITY_REJECTED
    }
    public enum RecoveryCause {
        STARTUP, WORLD_LOAD, PLAYER_JOIN, WORLD_TRANSFER, LOGOUT, EXPIRED,
        MISSING_SCAN
    }

    public record SummonResult(
            @Nonnull SummonStatus status,
            @Nullable BondedCompanionProjectionValidator.LeaseExpectation lease
    ) { }

    public record StoreResult(
            @Nonnull StoreStatus status,
            @Nullable BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) { }

    public record ReconcileResult(
            @Nonnull ReconcileStatus status,
            @Nonnull List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups
    ) {
        public ReconcileResult {
            cleanups = List.copyOf(cleanups);
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
