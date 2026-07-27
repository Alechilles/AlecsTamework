package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory durability fixture for projection recovery orchestration tests. */
final class RecordingBondedDurability
        implements BondedCompanionProjectionService.Durability,
        BondedCompanionProjectionStorePlanner {
    final Map<String, BondedCompanionState> states = new HashMap<>();
    final Map<String, BondedCompanionProjectionValidator.LeaseExpectation>
            spawnRecovery = new HashMap<>();
    List<String> events = new ArrayList<>();
    final List<Long> cleanupRetentions = new ArrayList<>();
    final List<BondedCompanionProjectionCleanupService.CleanupIntent>
            reconciledCleanups = new ArrayList<>();
    final List<BondedCompanionProjectionCleanupService.CleanupIntent>
            spawnFailureCleanups = new ArrayList<>();
    final Map<String, BondedCompanionSnapshot> snapshots = new HashMap<>();
    BondedCompanionSnapshot lastStoredSnapshot;
    String lastReason;
    boolean rollbackSucceeds = true;
    boolean reconcileSucceeds = true;

    void activate(BondedCompanionProjectionValidator.LeaseExpectation lease) {
        states.put(lease.profileId(), BondedCompanionState.ACTIVE);
        spawnRecovery.put(lease.profileId(), lease);
        snapshots.putIfAbsent(lease.profileId(), defaultSnapshot(
                new UUID(0L, 20L), lease.ownerUuid()));
    }

    void activate(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionSnapshot snapshot
    ) {
        activate(lease);
        snapshots.put(lease.profileId(), snapshot);
    }

    @Override
    public boolean beginSummon(
            BondedCompanionProjectionService.SummonRequest request,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionCleanupService.CleanupIntent recovery
    ) {
        events.add("begin:" + lease.profileId() + ":" + lease.leaseToken()
                + ":" + lease.liveNpcUuid());
        states.put(lease.profileId(), BondedCompanionState.ACTIVE);
        spawnRecovery.put(lease.profileId(), lease);
        return true;
    }

    @Override
    public boolean confirmSpawn(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            UUID spawnedNpcUuid
    ) {
        events.add("confirm:" + lease.profileId() + ":" + lease.leaseToken()
                + ":" + spawnedNpcUuid);
        spawnRecovery.remove(lease.profileId());
        return lease.liveNpcUuid().equals(spawnedNpcUuid);
    }

    @Override
    public boolean failSpawnAndEnqueueCleanup(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            String reason
    ) {
        events.add("rollback:" + lease.profileId() + ":" + reason);
        if (!rollbackSucceeds) return false;
        states.put(lease.profileId(), BondedCompanionState.STORED);
        spawnRecovery.remove(lease.profileId());
        spawnFailureCleanups.addAll(cleanups);
        lastReason = reason;
        return true;
    }

    @Override
    public boolean storeAndEnqueueCleanup(
            BondedCompanionProjectionService.StoreRequest request,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) {
        events.add("store:" + request.lease().profileId());
        lastStoredSnapshot = plan.snapshot();
        snapshots.put(request.lease().profileId(), plan.snapshot());
        states.put(request.lease().profileId(), BondedCompanionState.STORED);
        return true;
    }

    @Override
    public boolean reconcileStored(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            String reason
    ) {
        if (!reconcileSucceeds) return false;
        snapshots.put(lease.profileId(), plan.snapshot());
        states.put(lease.profileId(), BondedCompanionState.STORED);
        spawnRecovery.remove(lease.profileId());
        cleanups.forEach(cleanup -> cleanupRetentions.add(
                cleanup.retainedUntilMs()));
        reconciledCleanups.addAll(cleanups);
        lastReason = reason;
        return true;
    }

    @Override
    public PlanningResult plan(PlanningRequest request) {
        BondedCompanionSnapshot durable = snapshots.get(
                request.lease().profileId());
        if (durable == null) {
            return PlanningResult.rejected(Status.PROFILE_NOT_FOUND);
        }
        BondedCompanionSnapshot captured = request.capturedSnapshot();
        if (captured == null && request.cause() == Cause.EXPLICIT) {
            return PlanningResult.rejected(Status.SNAPSHOT_INVALID);
        }
        if (captured != null && !sameIdentity(durable, captured)) {
            return PlanningResult.rejected(Status.SNAPSHOT_IDENTITY_MISMATCH);
        }
        BondedCompanionSnapshot merged = captured == null
                ? durable : durable.mergeForStore(captured);
        long revision = request.expectedRevision() == null
                ? 5L : request.expectedRevision();
        return PlanningResult.planned(new StorePlan(revision, merged, 0L));
    }

    @Override
    public boolean confirmDeath(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            long diedAtMs
    ) {
        snapshots.put(lease.profileId(), plan.snapshot());
        states.put(lease.profileId(), BondedCompanionState.DEAD);
        spawnRecovery.remove(lease.profileId());
        lastReason = "CONFIRMED_DEATH";
        return true;
    }

    private static boolean sameIdentity(
            BondedCompanionSnapshot expected,
            BondedCompanionSnapshot captured
    ) {
        var prior = expected.fullState();
        var live = captured.fullState();
        return prior.owner() != null && live.owner() != null
                && prior.owner().getOwnerId().equals(live.owner().getOwnerId())
                && Objects.equals(prior.roleId(), live.roleId());
    }

    private static BondedCompanionSnapshot defaultSnapshot(
            UUID npcUuid,
            UUID ownerUuid
    ) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                npcUuid, null, -1, "role-a", null,
                new TameworkOwnerComponent(ownerUuid, "Owner"),
                new TameworkTamedComponent(true),
                null, null, null, null, null, null, null, null, null,
                -1.0, -1L
        ), Map.of());
    }
}

/** In-memory exact-world fixture for projection recovery orchestration tests. */
final class RecordingBondedWorld
        implements BondedCompanionProjectionService.World,
        BondedCompanionProjectionCleanupService.WorldGateway,
        BondedCompanionWorldLifecycleObserver.ProjectionSource {
    final List<String> events = new ArrayList<>();
    final List<BondedCompanionProjectionValidator.Projection> projections =
            new ArrayList<>();
    final Map<UUID, String> sources = new HashMap<>();
    final List<UUID> removed = new ArrayList<>();
    SpawnMode spawnMode = SpawnMode.SPAWNED;
    boolean removeSucceeds = true;

    @Override
    public BondedCompanionProjectionService.SpawnResult spawn(
            BondedCompanionProjectionService.SpawnPlan plan
    ) {
        events.add("spawn:" + plan.lease().profileId() + ":"
                + plan.lease().leaseToken());
        if (spawnMode == SpawnMode.THROW) {
            throw new IllegalStateException("spawn failed");
        }
        if (spawnMode == SpawnMode.RETRYABLE) {
            return BondedCompanionProjectionService.SpawnResult.retryRequired();
        }
        UUID spawnedUuid = spawnMode == SpawnMode.IDENTITY_MISMATCH
                ? new UUID(0L, 91L) : plan.lease().liveNpcUuid();
        projections.add(new BondedCompanionProjectionValidator.Projection(
                spawnedUuid, plan.lease().worldKey(),
                plan.marker(), plan.snapshot()
        ));
        return spawnMode == SpawnMode.IDENTITY_MISMATCH
                ? BondedCompanionProjectionService.SpawnResult
                .identityMismatch(spawnedUuid)
                : BondedCompanionProjectionService.SpawnResult
                .spawned(spawnedUuid);
    }

    @Override
    public BondedCompanionProjectionValidator.Projection readExact(
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        events.add("read:" + lease.liveNpcUuid());
        return projections.stream().filter(projection ->
                projection.npcUuid().equals(lease.liveNpcUuid()))
                .findFirst().orElse(null);
    }

    @Override
    public List<BondedCompanionProjectionValidator.Projection> projections() {
        return List.copyOf(projections);
    }

    @Override
    public BondedCompanionProjectionCleanupService.Outcome removeIfExact(
            BondedCompanionProjectionCleanupService.CleanupIntent intent
    ) {
        events.add("remove-if-exact:" + intent.worldKey() + ":"
                + intent.targetNpcUuid());
        for (int index = 0; index < projections.size(); index++) {
            var projection = projections.get(index);
            if (!projection.npcUuid().equals(intent.targetNpcUuid())) continue;
            boolean exact = projection.worldKey().equals(intent.worldKey())
                    && intent.target()
                    == BondedCompanionProjectionCleanupService.Target.PROJECTION
                    && projection.marker().isBondedCompanion()
                    && intent.profileId().equals(
                    projection.marker().getProfileId())
                    && intent.leaseToken().equals(
                    projection.marker().getBondedLeaseToken());
            if (!exact) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .IDENTITY_MISMATCH;
            }
            if (!removeSucceeds) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .RETRY_REQUIRED;
            }
            projections.remove(index);
            removed.add(intent.targetNpcUuid());
            return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
        }
        String sourceWorld = sources.get(intent.targetNpcUuid());
        if (sourceWorld == null) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .ALREADY_MISSING;
        }
        if (intent.target()
                != BondedCompanionProjectionCleanupService.Target.SOURCE
                || !sourceWorld.equals(intent.worldKey())) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .IDENTITY_MISMATCH;
        }
        if (!removeSucceeds) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .RETRY_REQUIRED;
        }
        sources.remove(intent.targetNpcUuid());
        removed.add(intent.targetNpcUuid());
        return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
    }

    enum SpawnMode { SPAWNED, RETRYABLE, IDENTITY_MISMATCH, THROW }
}
