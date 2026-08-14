package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Coordinates durable bonded leases with disposable world projections. */
public final class BondedCompanionProjectionService {
    private final BondedCompanionProjectionStorePlanner storePlanner;
    private final BondedCompanionProjectionDurability durability;
    private final BondedCompanionProjectionWorld world;
    private final BondedCompanionProjectionCleanupService cleanup;
    private final BondedCompanionSpawnFailureHandler spawnFailures;
    private final BondedCompanionProjectionValidator validator =
            new BondedCompanionProjectionValidator();
    private final BondedCompanionCleanupIntentFactory cleanupIntents =
            new BondedCompanionCleanupIntentFactory();
    private final Supplier<String> leaseTokens;
    private final Supplier<UUID> npcUuids;
    private final LeaseLifecycleObserver leaseLifecycle;

    public BondedCompanionProjectionService(
            @Nonnull BondedCompanionProjectionStorePlanner storePlanner,
            @Nonnull BondedCompanionProjectionDurability durability,
            @Nonnull BondedCompanionProjectionWorld world,
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull Supplier<String> leaseTokens,
            @Nonnull Supplier<UUID> npcUuids
    ) {
        this(storePlanner, durability, world, cleanup, leaseTokens, npcUuids,
                new LeaseLifecycleObserver() { });
    }

    /** Creates a projection service with post-commit lease lifecycle publication. */
    public BondedCompanionProjectionService(
            @Nonnull BondedCompanionProjectionStorePlanner storePlanner,
            @Nonnull BondedCompanionProjectionDurability durability,
            @Nonnull BondedCompanionProjectionWorld world,
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull Supplier<String> leaseTokens,
            @Nonnull Supplier<UUID> npcUuids,
            @Nonnull LeaseLifecycleObserver leaseLifecycle
    ) {
        this.storePlanner = Objects.requireNonNull(
                storePlanner, "storePlanner");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.world = Objects.requireNonNull(world, "world");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.spawnFailures = new BondedCompanionSpawnFailureHandler(
                durability, cleanup);
        this.leaseTokens = Objects.requireNonNull(leaseTokens, "leaseTokens");
        this.npcUuids = Objects.requireNonNull(npcUuids, "npcUuids");
        this.leaseLifecycle = Objects.requireNonNull(leaseLifecycle, "leaseLifecycle");
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
                lease, request.roleId(), request.snapshot(), marker,
                request.placement(), request.summonAuraEffectId()
        ));
        if (spawned.status() != SpawnStatus.SPAWNED
                || !npcUuid.equals(spawned.npcUuid())) {
            String reason = spawnFailureReason(spawned, npcUuid);
            return spawnFailures.rollback(
                    lease, spawned, reason, request.nowMs());
        }
        if (!safeConfirmSpawn(lease, spawned.npcUuid())) {
            return spawnFailures.rollback(
                    lease, spawned, "SPAWN_CONFIRM_FAILED", request.nowMs()
            );
        }
        BondedCompanionProjectionValidator.LeaseExpectation liveLease = live(lease);
        leaseLifecycle.activated(liveLease);
        return new SummonResult(SummonStatus.ACTIVE, liveLease);
    }

    /** Captures full live state first, then durably stores and enqueues exact cleanup. */
    @Nonnull
    public StoreResult store(@Nonnull StoreRequest request) {
        Objects.requireNonNull(request, "request");
        StoreDurabilityResult prior = durability.findStoreResult(
                request.operation());
        if (prior.status() == StoreDurabilityStatus.REPLAYED) {
            leaseLifecycle.ended(request.lease());
            return new StoreResult(StoreStatus.STORED, null);
        }
        if (prior.status() != StoreDurabilityStatus.ABSENT) {
            return new StoreResult(StoreStatus.DURABILITY_REJECTED, null);
        }
        BondedCompanionProjectionValidator.Projection projection =
                world.readExact(request.lease());
        var validation = validator.validate(
                request.lease(), projection == null ? List.of() : List.of(projection)
        );
        if (validation.status() != BondedCompanionProjectionValidator.Status.VALID
                || projection.snapshot() == null) {
            return new StoreResult(StoreStatus.PROJECTION_NOT_FOUND, null);
        }
        var planned = storePlanner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        request.lease(), request.expectedRevision(),
                        request.nowMs(), projection.snapshot(),
                        BondedCompanionProjectionStorePlanner.Cause.EXPLICIT
                )
        );
        if (planned.plan() == null) {
            return new StoreResult(StoreStatus.DURABILITY_REJECTED, null);
        }
        var intent = cleanupIntents.projection(
                request.lease(), "store", request.nowMs()
        );
        StoreDurabilityResult committed = durability.storeAndEnqueueCleanup(
                request, planned.plan(), intent);
        if (committed.status() == StoreDurabilityStatus.REPLAYED) {
            leaseLifecycle.ended(request.lease());
            return new StoreResult(StoreStatus.STORED, null);
        }
        if (committed.status() != StoreDurabilityStatus.APPLIED) {
            return new StoreResult(StoreStatus.DURABILITY_REJECTED, null);
        }
        leaseLifecycle.ended(request.lease());
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
        if (lease.phase()
                == BondedCompanionProjectionValidator.LeasePhase.PENDING) {
            return new ReconcileResult(
                    ReconcileStatus.PENDING_IN_PROGRESS, List.of());
        }
        var validation = validator.validate(lease, observed);
        boolean expired = BondedCompanionExpirySystem.isExpired(
                lease.expiresAtMs(), observedAtMs
        );
        boolean forced = expired || cause == RecoveryCause.EXPIRED
                || cause == RecoveryCause.WORLD_TRANSFER
                || cause == RecoveryCause.LOGOUT;
        if (!forced
                && validation.status() == BondedCompanionProjectionValidator.Status.VALID) {
            return new ReconcileResult(ReconcileStatus.ACTIVE_VALID, List.of());
        }
        String reason = expired ? "LEASE_EXPIRED"
                : BondedCompanionProjectionRecoveryEvidence.reason(
                        cause, validation.status());
        BondedCompanionSnapshot snapshot =
                BondedCompanionProjectionRecoveryEvidence.snapshot(validation);
        var planned = storePlanner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        lease, null, observedAtMs, snapshot,
                        BondedCompanionProjectionStorePlanner.Cause.RECONCILIATION
                )
        );
        if (planned.plan() == null) {
            ReconcileStatus status = planned.status()
                    == BondedCompanionProjectionStorePlanner.Status
                    .SNAPSHOT_IDENTITY_MISMATCH
                    ? ReconcileStatus.IDENTITY_MISMATCH
                    : ReconcileStatus.DURABILITY_REJECTED;
            return new ReconcileResult(status, List.of());
        }
        List<BondedCompanionProjectionCleanupService.CleanupIntent> intents =
                cleanupIntents.recovery(
                        lease, validation, reason, observedAtMs
                );
        if (!durability.reconcileStored(
                lease, planned.plan(), intents, reason)) {
            return new ReconcileResult(ReconcileStatus.DURABILITY_REJECTED, intents);
        }
        leaseLifecycle.ended(lease);
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
        var planned = storePlanner.plan(
                new BondedCompanionProjectionStorePlanner.PlanningRequest(
                        lease, null, diedAtMs, projection.snapshot(),
                        BondedCompanionProjectionStorePlanner.Cause
                                .CONFIRMED_DEATH
                )
        );
        if (planned.plan() == null) {
            ReconcileStatus status = planned.status()
                    == BondedCompanionProjectionStorePlanner.Status
                    .SNAPSHOT_IDENTITY_MISMATCH
                    ? ReconcileStatus.IDENTITY_MISMATCH
                    : ReconcileStatus.DURABILITY_REJECTED;
            return new ReconcileResult(status, List.of());
        }
        if (!durability.confirmDeath(lease, planned.plan(), diedAtMs)) {
            return new ReconcileResult(ReconcileStatus.DURABILITY_REJECTED, List.of());
        }
        leaseLifecycle.ended(lease);
        return new ReconcileResult(ReconcileStatus.DEAD, List.of());
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

    public record SummonRequest(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            @Nonnull String roleId,
            @Nonnull BondedCompanionSnapshot snapshot,
            @Nonnull String worldKey,
            @Nullable com.alechilles.alecstamework.companion.placement
                    .CompanionSpawnPlacement placement,
            long nowMs,
            long expiresAtMs,
            @Nonnull BondedCompanionActiveCapacity activeCapacity,
            @Nullable String summonAuraEffectId
    ) {
        public SummonRequest {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            profileId = text(profileId, "profileId");
            roleId = text(roleId, "roleId");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            worldKey = text(worldKey, "worldKey");
            activeCapacity = Objects.requireNonNull(
                    activeCapacity, "activeCapacity");
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
            summonAuraEffectId = summonAuraEffectId == null
                    || summonAuraEffectId.isBlank()
                    ? null : summonAuraEffectId.trim();
        }

        public SummonRequest(
                UUID ownerUuid, String rosterId, String profileId,
                long expectedRevision, String roleId, BondedCompanionSnapshot snapshot,
                String worldKey,
                com.alechilles.alecstamework.companion.placement
                        .CompanionSpawnPlacement placement,
                long nowMs, long expiresAtMs,
                BondedCompanionActiveCapacity activeCapacity
        ) {
            this(ownerUuid, rosterId, profileId, expectedRevision, roleId, snapshot,
                    worldKey, placement, nowMs, expiresAtMs, activeCapacity, null);
        }
    }

    public record StoreRequest(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            long expectedRevision,
            long nowMs,
            @Nonnull BondedCompanionOperation operation
    ) {
        public StoreRequest {
            lease = Objects.requireNonNull(lease, "lease");
            operation = Objects.requireNonNull(operation, "operation");
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("negative expectedRevision");
            }
            if (operation.type() != BondedCompanionOperation.Type.STORE
                    || !operation.ownerUuid().equals(lease.ownerUuid())
                    || !operation.rosterId().equals(lease.rosterId())
                    || !Objects.equals(operation.profileId(), lease.profileId())
                    || !new BondedCompanionOperation.StoreLeaseIdentity(
                    lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey())
                    .equals(operation.storeLeaseIdentity())) {
                throw new IllegalArgumentException(
                        "store operation scope does not match lease");
            }
        }
    }

    public record SpawnPlan(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull String roleId,
            @Nonnull BondedCompanionSnapshot snapshot,
            @Nonnull TameworkProjectionIdentityComponent marker,
            @Nullable com.alechilles.alecstamework.companion.placement
                    .CompanionSpawnPlacement placement,
            @Nullable String summonAuraEffectId
    ) {
        public SpawnPlan(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                String roleId, BondedCompanionSnapshot snapshot,
                TameworkProjectionIdentityComponent marker
        ) {
            this(lease, roleId, snapshot, marker, null, null);
        }

        public SpawnPlan {
            lease = Objects.requireNonNull(lease, "lease");
            roleId = text(roleId, "roleId");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            marker = Objects.requireNonNull(marker, "marker").clone();
            summonAuraEffectId = summonAuraEffectId == null
                    || summonAuraEffectId.isBlank()
                    ? null : summonAuraEffectId.trim();
        }

        public SpawnPlan(
                BondedCompanionProjectionValidator.LeaseExpectation lease,
                String roleId, BondedCompanionSnapshot snapshot,
                TameworkProjectionIdentityComponent marker,
                com.alechilles.alecstamework.companion.placement
                        .CompanionSpawnPlacement placement
        ) {
            this(lease, roleId, snapshot, marker, placement, null);
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
    public enum StoreDurabilityStatus {
        ABSENT, APPLIED, REPLAYED, CONFLICT, REJECTED, STORAGE_FAILURE
    }
    public enum ReconcileStatus {
        ACTIVE_VALID, PENDING_IN_PROGRESS, STORED, DEAD, IDENTITY_MISMATCH,
        DURABILITY_REJECTED
    }
    public enum RecoveryCause {
        STARTUP, WORLD_LOAD, PLAYER_JOIN, WORLD_TRANSFER, LOGOUT, EXPIRED,
        MISSING_SCAN
    }

    /** Receives lease changes only after their durable transaction succeeds. */
    public interface LeaseLifecycleObserver {
        default void activated(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
        ) { }

        default void ended(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
        ) { }
    }

    public record SummonResult(
            @Nonnull SummonStatus status,
            @Nullable BondedCompanionProjectionValidator.LeaseExpectation lease
    ) { }

    public record StoreResult(
            @Nonnull StoreStatus status,
            @Nullable BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) { }

    public record StoreDurabilityResult(
            @Nonnull StoreDurabilityStatus status
    ) {
        public StoreDurabilityResult {
            status = Objects.requireNonNull(status, "status");
        }
    }

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
