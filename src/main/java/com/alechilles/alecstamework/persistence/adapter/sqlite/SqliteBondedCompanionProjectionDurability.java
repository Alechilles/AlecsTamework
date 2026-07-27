package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionLocalProjectionLifecycle;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionStorePlanner;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomic SQLite implementation of the Task 4 bonded projection durability port. */
public final class SqliteBondedCompanionProjectionDurability implements
        BondedCompanionProjectionService.Durability,
        BondedCompanionLocalProjectionLifecycle.LeaseSource {
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionLeaseReader leaseReader;
    private final SqliteBondedCompanionCleanupReplay cleanupReplay;
    private final SqliteBondedCompanionStartupSettlement startupSettlement;
    private final SqliteBondedCompanionOperationExecutor operations;
    private final SqliteBondedCompanionExplicitStore explicitStore =
            new SqliteBondedCompanionExplicitStore();
    private final SqliteBondedCompanionMapper mapper =
            new SqliteBondedCompanionMapper();
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();
    private final SqliteBondedCompanionSummonWriter summons =
            new SqliteBondedCompanionSummonWriter();

    public SqliteBondedCompanionProjectionDurability(
            @Nonnull Path databasePath
    ) {
        connections = new SqliteConnectionFactory(
                Objects.requireNonNull(databasePath, "databasePath")
        );
        leaseReader = new SqliteBondedCompanionLeaseReader(connections);
        cleanupReplay = new SqliteBondedCompanionCleanupReplay(connections);
        startupSettlement = new SqliteBondedCompanionStartupSettlement(
                connections);
        operations = new SqliteBondedCompanionOperationExecutor(connections);
    }

    @Override
    public boolean beginSummon(
            BondedCompanionProjectionService.SummonRequest request,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionCleanupService.CleanupIntent recovery
    ) {
        return transaction(connection -> {
            if (!summons.activate(connection, request, lease)) return false;
            insertLease(connection, lease, "PENDING");
            insertCleanup(connection, recovery);
            return true;
        });
    }

    @Override
    public boolean confirmSpawn(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            UUID spawnedNpcUuid
    ) {
        if (!lease.liveNpcUuid().equals(spawnedNpcUuid)) return false;
        return transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_lease
                    SET projection_state = 'LIVE'
                    WHERE profile_id = ? AND lease_token = ?
                      AND live_npc_uuid = ? AND world_key = ?
                      AND projection_state = 'PENDING'
                    """)) {
                bindLeaseIdentity(update, lease);
                if (update.executeUpdate() != 1) return false;
            }
            deleteSpawnRecovery(connection, lease);
            return true;
        });
    }

    @Override
    public boolean failSpawnAndEnqueueCleanup(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            String reason
    ) {
        return returnToStored(
                lease, null, cleanups, lease.startedAtMs()
        );
    }

    @Override
    @Nonnull
    public BondedCompanionProjectionService.StoreDurabilityResult
    findStoreResult(@Nonnull BondedCompanionOperation operation) {
        return operations.find(operation, SqliteBondedCompanionProfileRow.class,
                        mapper::toDomain)
                .map(this::storeResult)
                .orElseGet(() -> new BondedCompanionProjectionService
                        .StoreDurabilityResult(
                        BondedCompanionProjectionService.StoreDurabilityStatus
                                .ABSENT));
    }

    @Override
    @Nonnull
    public BondedCompanionProjectionService.StoreDurabilityResult
    storeAndEnqueueCleanup(
            BondedCompanionProjectionService.StoreRequest request,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) {
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                operations.mutateConnection(
                        request.operation(), request.expectedRevision(),
                        SqliteBondedCompanionProfileRow.class,
                        connection -> explicitStore.apply(
                                connection, request, plan, cleanup,
                                encoded(plan.snapshot())),
                        mapper::toDomain, null);
        return storeResult(result);
    }

    @Override
    public boolean reconcileStored(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            String reason
    ) {
        if (lease.phase()
                == BondedCompanionProjectionValidator.LeasePhase.PENDING) {
            return false;
        }
        long updatedAt = cleanups.isEmpty()
                ? lease.startedAtMs() : cleanups.getFirst().createdAtMs();
        return returnToStored(
                lease, plan, cleanups, updatedAt
        );
    }

    @Override
    public boolean confirmDeath(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            long diedAtMs
    ) {
        Objects.requireNonNull(plan, "plan");
        return transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET state = 'DEAD', revision = revision + 1,
                        snapshot_json = ?,
                        summon_cooldown_until_ms = 0,
                        died_at_ms = ?, updated_at_ms = ?
                    WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                      AND state = 'ACTIVE' AND revision = ?
                      AND EXISTS (
                        SELECT 1 FROM bonded_companion_lease l
                        WHERE l.profile_id = bonded_companion_profile.profile_id
                          AND l.lease_token = ? AND l.live_npc_uuid = ?
                          AND l.world_key = ? AND l.projection_state = ?
                      )
                    """)) {
                update.setString(1, encoded(plan.snapshot()));
                update.setLong(2, diedAtMs);
                update.setLong(3, diedAtMs);
                scope(update, 4, lease);
                update.setLong(7, plan.expectedRevision());
                update.setString(8, lease.leaseToken());
                update.setString(9, lease.liveNpcUuid().toString());
                update.setString(10, lease.worldKey());
                update.setString(11, lease.phase().name());
                if (update.executeUpdate() != 1) return false;
            }
            return deleteLease(connection, lease) == 1;
        });
    }

    /** Replays due exact cleanups and records their bounded terminal/retry state. */
    public int replayPendingCleanupForWorld(
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull String worldKey,
            long nowMs,
            int limit
    ) {
        return cleanupReplay.pendingForWorld(
                cleanup, worldKey, nowMs, limit);
    }

    /** Attempts one newly committed capture cleanup and records its outcome. */
    @Nonnull
    public BondedCompanionProjectionCleanupService.Outcome attemptCleanup(
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent intent,
            long nowMs
    ) {
        return cleanupReplay.attempt(cleanup, intent, nowMs);
    }

    /** Returns a bounded exact lease view for observer reconciliation. */
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation>
    activeLeases(int limit) {
        return leaseReader.activeLeases(limit);
    }

    @Override
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation> inWorldAfter(
            @Nonnull String worldKey, @Nullable String afterProfileId, int limit
    ) {
        return leaseReader.inWorldAfter(worldKey, afterProfileId, limit);
    }

    @Override
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation> forOwnerAfter(
            @Nonnull UUID ownerUuid,
            @Nullable String afterWorldKey,
            @Nullable String afterProfileId,
            int limit
    ) {
        return leaseReader.forOwnerAfter(
                ownerUuid, afterWorldKey, afterProfileId, limit);
    }

    @Override
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation>
    forOwnerInWorldAfter(
            @Nonnull UUID ownerUuid,
            @Nonnull String worldKey,
            @Nullable String afterProfileId,
            int limit
    ) {
        return leaseReader.forOwnerInWorldAfter(
                ownerUuid, worldKey, afterProfileId, limit);
    }

    @Override
    @Nonnull
    public Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact(
            @Nonnull String profileId, @Nonnull String leaseToken
    ) {
        return leaseReader.exact(profileId, leaseToken);
    }

    /** Rolls back only incomplete summon admissions; confirmed live leases survive restart. */
    public int settleResidualLeases(long nowMs) {
        return startupSettlement.settle(nowMs);
    }

    private BondedCompanionProjectionService.StoreDurabilityResult storeResult(
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> result
    ) {
        BondedCompanionProjectionService.StoreDurabilityStatus status =
                switch (result.code()) {
                    case APPLIED -> result.replayed()
                            ? BondedCompanionProjectionService
                            .StoreDurabilityStatus.REPLAYED
                            : BondedCompanionProjectionService
                            .StoreDurabilityStatus.APPLIED;
                    case IDEMPOTENCY_CONFLICT -> BondedCompanionProjectionService
                            .StoreDurabilityStatus.CONFLICT;
                    case STORAGE_FAILURE -> BondedCompanionProjectionService
                            .StoreDurabilityStatus.STORAGE_FAILURE;
                    default -> BondedCompanionProjectionService
                            .StoreDurabilityStatus.REJECTED;
                };
        return new BondedCompanionProjectionService.StoreDurabilityResult(status);
    }

    private boolean returnToStored(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nullable BondedCompanionProjectionStorePlanner.StorePlan plan,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            long updatedAtMs
    ) {
        Objects.requireNonNull(cleanups, "cleanups");
        return transaction(connection -> {
            String revisionClause = plan == null ? "" : " AND revision = ?";
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET state = 'STORED', revision = revision + 1,
                        snapshot_json = COALESCE(?, snapshot_json),
                        summon_cooldown_until_ms = COALESCE(
                            ?, summon_cooldown_until_ms
                        ),
                        died_at_ms = NULL, updated_at_ms = ?
                    WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                      AND state = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1 FROM bonded_companion_lease l
                        WHERE l.profile_id = bonded_companion_profile.profile_id
                          AND l.lease_token = ? AND l.live_npc_uuid = ?
                          AND l.world_key = ? AND l.projection_state = ?
                      )
                    """ + revisionClause)) {
                update.setString(1, plan == null
                        ? null : encoded(plan.snapshot()));
                if (plan == null) {
                    update.setNull(2, Types.BIGINT);
                } else {
                    update.setLong(2, plan.summonCooldownUntilMs());
                }
                update.setLong(3, updatedAtMs);
                scope(update, 4, lease);
                update.setString(7, lease.leaseToken());
                update.setString(8, lease.liveNpcUuid().toString());
                update.setString(9, lease.worldKey());
                update.setString(10, lease.phase().name());
                if (plan != null) {
                    update.setLong(11, plan.expectedRevision());
                }
                if (update.executeUpdate() != 1) return false;
            }
            if (deleteLease(connection, lease) != 1) return false;
            deleteSpawnRecovery(connection, lease);
            for (var intent : cleanups) insertCleanup(connection, intent);
            return true;
        });
    }

    private void insertLease(
            Connection connection,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            String phase
    ) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO bonded_companion_lease(
                    profile_id, lease_token, live_npc_uuid, world_key,
                    started_at_ms, expires_at_ms, projection_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, lease.profileId());
            insert.setString(2, lease.leaseToken());
            insert.setString(3, lease.liveNpcUuid().toString());
            insert.setString(4, lease.worldKey());
            insert.setLong(5, lease.startedAtMs());
            insert.setLong(6, lease.expiresAtMs());
            insert.setString(7, phase);
            insert.executeUpdate();
        }
    }

    private void insertCleanup(
            Connection connection,
            BondedCompanionProjectionCleanupService.CleanupIntent intent
    ) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO bonded_companion_cleanup(
                    cleanup_id, owner_uuid, roster_id, profile_id,
                    lease_token, target_kind, target_npc_uuid, cleanup_reason,
                    world_key, cleanup_state, attempt_count, next_attempt_at_ms,
                    created_at_ms, retained_until_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """)) {
            insert.setString(1, intent.cleanupId());
            insert.setString(2, intent.ownerUuid().toString());
            insert.setString(3, intent.rosterId());
            insert.setString(4, intent.profileId());
            insert.setString(5, intent.leaseToken());
            insert.setString(6, intent.target().name());
            insert.setString(7, intent.targetNpcUuid().toString());
            insert.setString(8, intent.reason());
            insert.setString(9, intent.worldKey());
            insert.setLong(10, intent.createdAtMs());
            insert.setLong(11, intent.createdAtMs());
            insert.setLong(12, intent.retainedUntilMs());
            insert.executeUpdate();
        }
    }

    private int deleteLease(
            Connection connection,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM bonded_companion_lease
                WHERE profile_id = ? AND lease_token = ?
                  AND live_npc_uuid = ? AND world_key = ?
                  AND projection_state = ?
                """)) {
            bindLeaseIdentity(delete, lease);
            delete.setString(5, lease.phase().name());
            return delete.executeUpdate();
        }
    }

    private void deleteSpawnRecovery(
            Connection connection,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM bonded_companion_cleanup
                WHERE profile_id = ? AND lease_token = ?
                  AND target_npc_uuid = ? AND world_key = ?
                  AND cleanup_reason = 'spawn-recovery'
                """)) {
            bindLeaseIdentity(delete, lease);
            delete.executeUpdate();
        }
    }

    private String encoded(BondedCompanionSnapshot snapshot) {
        return mapper.payloadJson(BondedCompanionPayload.of(
                snapshots.encode(snapshot).getBytes(StandardCharsets.UTF_8)
        ));
    }

    private void scope(
            PreparedStatement statement,
            int first,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) throws Exception {
        statement.setString(first, lease.profileId());
        statement.setString(first + 1, lease.ownerUuid().toString());
        statement.setString(first + 2, lease.rosterId());
    }

    private void bindLeaseIdentity(
            PreparedStatement statement,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) throws Exception {
        statement.setString(1, lease.profileId());
        statement.setString(2, lease.leaseToken());
        statement.setString(3, lease.liveNpcUuid().toString());
        statement.setString(4, lease.worldKey());
    }

    private boolean transaction(Transaction work) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            boolean applied = work.apply(connection);
            if (applied) connection.commit(); else connection.rollback();
            return applied;
        } catch (Exception failure) {
            if (connection != null) {
                try { connection.rollback(); } catch (Exception ignored) { }
            }
            return false;
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
            }
        }
    }

    @FunctionalInterface
    private interface Transaction {
        boolean apply(Connection connection) throws Exception;
    }
}
