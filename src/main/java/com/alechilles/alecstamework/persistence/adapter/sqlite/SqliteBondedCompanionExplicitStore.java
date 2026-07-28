package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionStorePlanner;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** Applies one exact explicit-store mutation on the operation transaction. */
final class SqliteBondedCompanionExplicitStore {
    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionProfileRow>
    apply(
            Connection connection,
            BondedCompanionProjectionService.StoreRequest request,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            BondedCompanionProjectionCleanupService.CleanupIntent cleanup,
            String snapshotJson
    ) {
        try {
            if (plan.expectedRevision() != request.expectedRevision()
                    || !cleanup.profileId().equals(request.lease().profileId())
                    || !cleanup.leaseToken().equals(
                    request.lease().leaseToken())) {
                return rejected("store-request-scope-mismatch");
            }
            if (!storeProfile(connection, request, plan, snapshotJson)) {
                return rejected("store-lease-or-revision-conflict");
            }
            if (deleteLease(connection, request) != 1) {
                return rejected("store-lease-delete-conflict");
            }
            deleteSpawnRecovery(connection, request);
            insertCleanup(connection, cleanup);
            return new SqliteBondedCompanionStore.MutationResult<>(
                    SqliteBondedCompanionStore.MutationCode.APPLIED,
                    new SqliteBondedCompanionProfileReader(connection).require(
                            request.lease().profileId()), null);
        } catch (Exception failure) {
            return new SqliteBondedCompanionStore.MutationResult<>(
                    SqliteBondedCompanionStore.MutationCode.STORAGE_FAILURE,
                    null, "explicit-store-write-failed");
        }
    }

    private boolean storeProfile(
            Connection connection,
            BondedCompanionProjectionService.StoreRequest request,
            BondedCompanionProjectionStorePlanner.StorePlan plan,
            String snapshotJson
    ) throws Exception {
        var lease = request.lease();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'STORED', revision = revision + 1,
                    snapshot_json = ?, summon_cooldown_until_ms = ?,
                    died_at_ms = NULL, updated_at_ms = ?
                WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                  AND state = 'ACTIVE' AND revision = ?
                  AND EXISTS (
                    SELECT 1 FROM bonded_companion_lease l
                    WHERE l.profile_id = bonded_companion_profile.profile_id
                      AND l.lease_token = ? AND l.live_npc_uuid = ?
                      AND l.world_key = ? AND l.projection_state = ?
                  )
                """)) {
            update.setString(1, snapshotJson);
            update.setLong(2, plan.summonCooldownUntilMs());
            update.setLong(3, request.nowMs());
            update.setString(4, lease.profileId());
            update.setString(5, lease.ownerUuid().toString());
            update.setString(6, lease.rosterId());
            update.setLong(7, request.expectedRevision());
            update.setString(8, lease.leaseToken());
            update.setString(9, lease.liveNpcUuid().toString());
            update.setString(10, lease.worldKey());
            update.setString(11, lease.phase().name());
            return update.executeUpdate() == 1;
        }
    }

    private int deleteLease(
            Connection connection,
            BondedCompanionProjectionService.StoreRequest request
    ) throws Exception {
        var lease = request.lease();
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM bonded_companion_lease
                WHERE profile_id = ? AND lease_token = ?
                  AND live_npc_uuid = ? AND world_key = ?
                  AND projection_state = ?
                """)) {
            delete.setString(1, lease.profileId());
            delete.setString(2, lease.leaseToken());
            delete.setString(3, lease.liveNpcUuid().toString());
            delete.setString(4, lease.worldKey());
            delete.setString(5, lease.phase().name());
            return delete.executeUpdate();
        }
    }

    private void deleteSpawnRecovery(
            Connection connection,
            BondedCompanionProjectionService.StoreRequest request
    ) throws Exception {
        var lease = request.lease();
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM bonded_companion_cleanup
                WHERE profile_id = ? AND lease_token = ?
                  AND target_npc_uuid = ? AND world_key = ?
                  AND cleanup_reason = 'spawn-recovery'
                """)) {
            delete.setString(1, lease.profileId());
            delete.setString(2, lease.leaseToken());
            delete.setString(3, lease.liveNpcUuid().toString());
            delete.setString(4, lease.worldKey());
            delete.executeUpdate();
        }
    }

    private void insertCleanup(
            Connection connection,
            BondedCompanionProjectionCleanupService.CleanupIntent cleanup
    ) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO bonded_companion_cleanup(
                    cleanup_id, owner_uuid, roster_id, profile_id,
                    lease_token, target_kind, target_npc_uuid, cleanup_reason,
                    world_key, cleanup_state, attempt_count, next_attempt_at_ms,
                    created_at_ms, retained_until_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """)) {
            insert.setString(1, cleanup.cleanupId());
            insert.setString(2, cleanup.ownerUuid().toString());
            insert.setString(3, cleanup.rosterId());
            insert.setString(4, cleanup.profileId());
            insert.setString(5, cleanup.leaseToken());
            insert.setString(6, cleanup.target().name());
            insert.setString(7, cleanup.targetNpcUuid().toString());
            insert.setString(8, cleanup.reason());
            insert.setString(9, cleanup.worldKey());
            insert.setLong(10, cleanup.createdAtMs());
            insert.setLong(11, cleanup.createdAtMs());
            insert.setLong(12, cleanup.retainedUntilMs());
            insert.executeUpdate();
        }
    }

    private SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionProfileRow> rejected(String reason) {
        return new SqliteBondedCompanionStore.MutationResult<>(
                SqliteBondedCompanionStore.MutationCode.REVISION_CONFLICT,
                null, reason);
    }
}
