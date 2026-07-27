package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomic SQLite implementation of the Task 4 bonded projection durability port. */
public final class SqliteBondedCompanionProjectionDurability implements
        BondedCompanionProjectionService.Durability {
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionCleanupQueue cleanupQueue;
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
        cleanupQueue = new SqliteBondedCompanionCleanupQueue(connections);
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
                lease, null, null, null, cleanups,
                lease.startedAtMs(), false
        );
    }

    @Override
    public boolean storeAndEnqueueCleanup(
            BondedCompanionProjectionService.StoreRequest request,
            BondedCompanionSnapshot snapshot,
            BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) {
        return returnToStored(
                request.lease(), snapshot, request.expectedRevision(),
                request.summonCooldownUntilMs(), List.of(cleanup),
                request.nowMs(), true
        );
    }

    @Override
    public boolean reconcileStored(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionSnapshot snapshot,
            long summonCooldownUntilMs,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            String reason
    ) {
        long updatedAt = cleanups.isEmpty()
                ? lease.startedAtMs() : cleanups.getFirst().createdAtMs();
        return returnToStored(
                lease, snapshot, null, summonCooldownUntilMs,
                cleanups, updatedAt, false
        );
    }

    @Override
    public boolean confirmDeath(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionSnapshot snapshot,
            long diedAtMs
    ) {
        return transaction(connection -> {
            String encoded = snapshot == null ? null : encoded(snapshot);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET state = 'DEAD', revision = revision + 1,
                        snapshot_json = COALESCE(?, snapshot_json),
                        died_at_ms = ?, updated_at_ms = ?
                    WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                      AND state = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1 FROM bonded_companion_lease l
                        WHERE l.profile_id = bonded_companion_profile.profile_id
                          AND l.lease_token = ? AND l.live_npc_uuid = ?
                          AND l.world_key = ?
                      )
                    """)) {
                update.setString(1, encoded);
                update.setLong(2, diedAtMs);
                update.setLong(3, diedAtMs);
                scope(update, 4, lease);
                update.setString(7, lease.leaseToken());
                update.setString(8, lease.liveNpcUuid().toString());
                update.setString(9, lease.worldKey());
                if (update.executeUpdate() != 1) return false;
            }
            return deleteLease(connection, lease) == 1;
        });
    }

    /** Replays due exact cleanups and records their bounded terminal/retry state. */
    public int replayPendingCleanup(
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            long nowMs,
            int limit
    ) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<BondedCompanionProjectionCleanupService.CleanupIntent> pending =
                cleanupQueue.pending(nowMs, limit);
        int attempted = 0;
        for (var intent : pending) {
            BondedCompanionProjectionCleanupService.Outcome outcome =
                    BondedCompanionRecord.Cleanup.LEGACY_UNKNOWN_WORLD.equals(
                            intent.worldKey()
                    )
                            ? BondedCompanionProjectionCleanupService.Outcome
                            .IDENTITY_MISMATCH
                            : cleanup.recover(intent);
            cleanupQueue.recordOutcome(intent.cleanupId(), outcome, nowMs);
            attempted++;
        }
        return attempted;
    }

    /** Attempts one newly committed capture cleanup and records its outcome. */
    @Nonnull
    public BondedCompanionProjectionCleanupService.Outcome attemptCleanup(
            @Nonnull BondedCompanionProjectionCleanupService cleanup,
            @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent intent,
            long nowMs
    ) {
        BondedCompanionProjectionCleanupService.Outcome outcome =
                cleanup.recover(intent);
        cleanupQueue.recordOutcome(intent.cleanupId(), outcome, nowMs);
        return outcome;
    }

    /** Returns a bounded exact lease view for observer reconciliation. */
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation>
    activeLeases(int limit) {
        return leases(null, limit);
    }

    /** Returns finite expired leases without rejecting negative world time. */
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation>
    findExpired(long nowMs, int limit) {
        return leases(nowMs, limit);
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> leases(
            @Nullable Long expiredAt,
            int limit
    ) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        String expiry = expiredAt == null ? """
                WHERE p.state = 'ACTIVE'
                """ : """
                WHERE p.state = 'ACTIVE' AND l.expires_at_ms != 0
                  AND l.expires_at_ms <= ?
                """;
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.owner_uuid, p.roster_id, l.profile_id,
                            l.lease_token, l.live_npc_uuid, l.world_key,
                            l.started_at_ms, l.expires_at_ms, l.projection_state
                     FROM bonded_companion_lease l
                     JOIN bonded_companion_profile p
                       ON p.profile_id = l.profile_id
                     """ + expiry + """
                     ORDER BY l.profile_id LIMIT ?
                     """)) {
            int index = 1;
            if (expiredAt != null) statement.setLong(index++, expiredAt);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionProjectionValidator.LeaseExpectation>
                        result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new BondedCompanionProjectionValidator
                            .LeaseExpectation(
                            UUID.fromString(rows.getString(1)), rows.getString(2),
                            rows.getString(3), rows.getString(4),
                            UUID.fromString(rows.getString(5)), rows.getString(6),
                            rows.getLong(7), rows.getLong(8),
                            BondedCompanionProjectionValidator.LeasePhase
                                    .valueOf(rows.getString(9))
                    ));
                }
                return List.copyOf(result);
            }
        } catch (Exception failure) {
            return List.of();
        }
    }

    private boolean returnToStored(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nullable BondedCompanionSnapshot snapshot,
            @Nullable Long expectedRevision,
            @Nullable Long summonCooldownUntilMs,
            List<BondedCompanionProjectionCleanupService.CleanupIntent> cleanups,
            long updatedAtMs,
            boolean requireRevision
    ) {
        Objects.requireNonNull(cleanups, "cleanups");
        return transaction(connection -> {
            String revisionClause = requireRevision ? " AND revision = ?" : "";
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET state = 'STORED', revision = revision + 1,
                        snapshot_json = COALESCE(?, snapshot_json),
                        revive_cooldown_until_ms = COALESCE(
                            ?, revive_cooldown_until_ms
                        ),
                        died_at_ms = NULL, updated_at_ms = ?
                    WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                      AND state = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1 FROM bonded_companion_lease l
                        WHERE l.profile_id = bonded_companion_profile.profile_id
                          AND l.lease_token = ? AND l.live_npc_uuid = ?
                          AND l.world_key = ?
                      )
                    """ + revisionClause)) {
                update.setString(1, snapshot == null ? null : encoded(snapshot));
                if (summonCooldownUntilMs == null) {
                    update.setNull(2, Types.BIGINT);
                } else {
                    update.setLong(2, summonCooldownUntilMs);
                }
                update.setLong(3, updatedAtMs);
                scope(update, 4, lease);
                update.setString(7, lease.leaseToken());
                update.setString(8, lease.liveNpcUuid().toString());
                update.setString(9, lease.worldKey());
                if (requireRevision) update.setLong(10, expectedRevision);
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
                """)) {
            bindLeaseIdentity(delete, lease);
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
