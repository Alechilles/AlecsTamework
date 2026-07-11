package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.HousedResidentClaim;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/**
 * Transactional SQL implementation kept separate from the managed-resident repository facade.
 */
final class ManagedCoopResidentTransactions {
    private final ManagedCoopUuidClaimStore uuidClaims = new ManagedCoopUuidClaimStore();

    MutationResult registerAuthority(Connection connection,
                                     ManagedCoopAuthorityKey key,
                                     String coopIdRaw,
                                     AuthorityState state,
                                     long nowMs) throws SQLException {
        String coopId = normalizeCoopId(coopIdRaw);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT coop_id, authority_state, active FROM managed_coop_authority WHERE authority_id = ?")) {
            query.setString(1, key.authorityId());
            try (ResultSet resultSet = query.executeQuery()) {
                if (resultSet.next()) {
                    boolean matches = coopId.equals(normalizeCoopId(resultSet.getString("coop_id")))
                            && state.name().equals(resultSet.getString("authority_state"))
                            && resultSet.getInt("active") == 1;
                    return result(matches ? MutationStatus.IDEMPOTENT : MutationStatus.CONFLICT,
                            null, matches ? null : "authority_identity_or_state_conflict");
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state, active,
                    import_version, created_at_ms, updated_at_ms, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, NULL)
                """)) {
            insert.setString(1, key.authorityId());
            insert.setString(2, key.worldName());
            insert.setString(3, coopId);
            insert.setInt(4, key.x());
            insert.setInt(5, key.y());
            insert.setInt(6, key.z());
            insert.setString(7, state.name());
            insert.setLong(8, nowMs);
            insert.setLong(9, nowMs);
            insert.executeUpdate();
        }
        return result(MutationStatus.APPLIED, null, null);
    }

    MutationResult transitionAuthority(Connection connection,
                                       ManagedCoopAuthorityKey key,
                                       AuthorityState expected,
                                       AuthorityState target,
                                       String lastError,
                                       long nowMs) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE managed_coop_authority
                SET authority_state = ?, updated_at_ms = ?, last_error = ?
                WHERE authority_id = ? AND authority_state = ? AND active = 1
                """)) {
            update.setString(1, target.name());
            update.setLong(2, nowMs);
            update.setString(3, lastError);
            update.setString(4, key.authorityId());
            update.setString(5, expected.name());
            if (update.executeUpdate() == 1) {
                return result(MutationStatus.APPLIED, null, null);
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT authority_state FROM managed_coop_authority WHERE authority_id = ? AND active = 1")) {
            query.setString(1, key.authorityId());
            try (ResultSet resultSet = query.executeQuery()) {
                if (!resultSet.next()) {
                    return result(MutationStatus.NOT_FOUND, null, "authority_not_found");
                }
                boolean reached = target.name().equals(resultSet.getString(1));
                return result(reached ? MutationStatus.IDEMPOTENT : MutationStatus.CONFLICT,
                        null, reached ? null : "authority_state_mismatch");
            }
        }
    }

    MutationResult claimHoused(Connection connection, HousedResidentClaim claim) throws SQLException {
        validateClaim(claim);
        ResidentRecord existing = loadById(connection, claim.residentId());
        if (existing != null) {
            boolean same = existing.active() && existing.state() == ResidentState.HOUSED
                    && existing.authorityKey().equals(claim.authorityKey())
                    && existing.coopId().equalsIgnoreCase(claim.coopId())
                    && existing.residentSlot() == claim.residentSlot()
                    && existing.profileId().equals(claim.profileId())
                    && existing.residentUuid().equals(claim.sourceNpcUuid())
                    && Objects.equals(existing.sourceNpcUuid(), claim.sourceNpcUuid())
                    && Objects.equals(existing.snapshotHash(), claim.snapshotHash())
                    && existing.snapshotVersion() == Math.max(1, claim.snapshotVersion());
            return result(same ? MutationStatus.IDEMPOTENT : MutationStatus.CONFLICT,
                    existing, same ? null : "resident_id_conflict");
        }
        if (!authorityMatches(connection, claim.authorityKey(), claim.coopId())
                || hasActiveAssignmentConflict(connection, claim)
                || !uuidClaims.canClaim(connection, claim.residentId(), claim.sourceNpcUuid())) {
            return result(MutationStatus.CONFLICT, null, "active_profile_slot_or_uuid_conflict");
        }
        insertHoused(connection, claim);
        uuidClaims.claim(connection, claim.residentId(), "SOURCE",
                claim.sourceNpcUuid(), claim.capturedAtMs());
        return result(MutationStatus.APPLIED, loadById(connection, claim.residentId()), null);
    }

    MutationResult beginRelease(Connection connection,
                                String residentId,
                                long expectedGeneration,
                                UUID plannedTargetUuid,
                                long nowMs) throws SQLException {
        ResidentRecord resident = loadById(connection, residentId);
        if (resident == null || !resident.active()) {
            return result(MutationStatus.NOT_FOUND, resident, "active_resident_not_found");
        }
        if (resident.state() == ResidentState.RELEASING
                && resident.generation() == expectedGeneration + 1
                && uuidClaims.hasActive(connection, residentId, plannedTargetUuid, "PLANNED")) {
            return result(MutationStatus.IDEMPOTENT, resident, null);
        }
        if (resident.state() != ResidentState.HOUSED || resident.generation() != expectedGeneration
                || !uuidClaims.canClaim(connection, residentId, plannedTargetUuid)) {
            return result(MutationStatus.CONFLICT, resident, "release_precondition_conflict");
        }
        updateState(connection, residentId, ResidentState.HOUSED, expectedGeneration,
                ResidentState.RELEASING, nowMs);
        uuidClaims.claim(connection, residentId, "PLANNED", plannedTargetUuid, nowMs);
        return result(MutationStatus.APPLIED, loadById(connection, residentId), null);
    }

    MutationResult reserveProjectionUuid(Connection connection,
                                         String residentId,
                                         UUID targetUuid,
                                         long nowMs) throws SQLException {
        ResidentRecord resident = loadById(connection, residentId);
        if (resident == null || !resident.active() || resident.state() != ResidentState.RELEASING) {
            return result(MutationStatus.CONFLICT, resident, "projection_resident_not_releasing");
        }
        if (!uuidClaims.canClaim(connection, residentId, targetUuid)) {
            return result(MutationStatus.CONFLICT, resident, "projection_uuid_conflict");
        }
        if (uuidClaims.hasActive(connection, residentId, targetUuid, "PLANNED")) {
            return result(MutationStatus.IDEMPOTENT, resident, null);
        }
        uuidClaims.claim(connection, residentId, "PLANNED", targetUuid, nowMs);
        return result(MutationStatus.APPLIED, resident, null);
    }

    MutationResult finishRelease(Connection connection,
                                 String residentId,
                                 long expectedGeneration,
                                 UUID actualTargetUuid,
                                 long nowMs) throws SQLException {
        ResidentRecord resident = loadById(connection, residentId);
        if (resident == null || !resident.active()) {
            return result(MutationStatus.NOT_FOUND, resident, "active_resident_not_found");
        }
        if (resident.state() == ResidentState.DEPLOYED
                && resident.generation() == expectedGeneration + 1
                && actualTargetUuid.equals(resident.deployedNpcUuid())) {
            return result(MutationStatus.IDEMPOTENT, resident, null);
        }
        if (resident.state() != ResidentState.RELEASING || resident.generation() != expectedGeneration
                || !uuidClaims.canClaim(connection, residentId, actualTargetUuid)) {
            return result(MutationStatus.CONFLICT, resident, "deployment_precondition_conflict");
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE managed_coop_residents
                SET resident_uuid = ?, deployed_npc_uuid = ?, state = 'DEPLOYED',
                    generation = generation + 1, released_at_ms = ?, updated_at_ms = ?
                WHERE resident_id = ? AND active = 1 AND state = 'RELEASING' AND generation = ?
                """)) {
            update.setString(1, actualTargetUuid.toString());
            update.setString(2, actualTargetUuid.toString());
            update.setLong(3, nowMs);
            update.setLong(4, nowMs);
            update.setString(5, residentId);
            update.setLong(6, expectedGeneration);
            requireUpdated(update, "deployment_generation_changed");
        }
        uuidClaims.claim(connection, residentId, "DEPLOYED", actualTargetUuid, nowMs);
        uuidClaims.deactivateKind(connection, residentId, "PLANNED", nowMs);
        return result(MutationStatus.APPLIED, loadById(connection, residentId), null);
    }

    MutationResult finishCapture(Connection connection,
                                 String residentId,
                                 long expectedGeneration,
                                 UUID sourceNpcUuid,
                                 String snapshotJson,
                                 String snapshotHash,
                                 int snapshotVersion,
                                 long nowMs) throws SQLException {
        ResidentRecord resident = loadById(connection, residentId);
        if (resident == null || !resident.active()) {
            return result(MutationStatus.NOT_FOUND, resident, "active_resident_not_found");
        }
        if (resident.state() == ResidentState.HOUSED
                && resident.generation() == expectedGeneration + 1
                && sourceNpcUuid.equals(resident.sourceNpcUuid())
                && Objects.equals(snapshotHash, resident.snapshotHash())) {
            return result(MutationStatus.IDEMPOTENT, resident, null);
        }
        if (resident.state() != ResidentState.DEPLOYED || resident.generation() != expectedGeneration
                || !uuidClaims.canClaim(connection, residentId, sourceNpcUuid)) {
            return result(MutationStatus.CONFLICT, resident, "capture_precondition_conflict");
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE managed_coop_residents
                SET resident_uuid = ?, source_npc_uuid = ?, deployed_npc_uuid = NULL,
                    snapshot_json = ?, snapshot_hash = ?, snapshot_version = ?, state = 'HOUSED',
                    generation = generation + 1, captured_at_ms = ?, updated_at_ms = ?
                WHERE resident_id = ? AND active = 1 AND state = 'DEPLOYED' AND generation = ?
                """)) {
            update.setString(1, sourceNpcUuid.toString());
            update.setString(2, sourceNpcUuid.toString());
            update.setString(3, snapshotJson);
            update.setString(4, snapshotHash);
            update.setInt(5, Math.max(1, snapshotVersion));
            update.setLong(6, nowMs);
            update.setLong(7, nowMs);
            update.setString(8, residentId);
            update.setLong(9, expectedGeneration);
            requireUpdated(update, "capture_generation_changed");
        }
        uuidClaims.claim(connection, residentId, "SOURCE", sourceNpcUuid, nowMs);
        return result(MutationStatus.APPLIED, loadById(connection, residentId), null);
    }

    @Nullable
    ResidentRecord loadById(Connection connection, String residentId) throws SQLException {
        return loadOne(connection, "SELECT * FROM managed_coop_residents WHERE resident_id = ? LIMIT 1",
                statement -> statement.setString(1, residentId));
    }

    @Nullable
    ResidentRecord loadActiveByProfile(Connection connection, String profileId) throws SQLException {
        return loadUnique(connection,
                "SELECT * FROM managed_coop_residents WHERE profile_id = ? AND active = 1 LIMIT 2",
                statement -> statement.setString(1, profileId));
    }

    @Nullable
    ResidentRecord loadActiveSlot(Connection connection,
                                  ManagedCoopAuthorityKey key,
                                  int residentSlot) throws SQLException {
        return loadUnique(connection, """
                SELECT * FROM managed_coop_residents
                WHERE authority_id = ? AND resident_slot = ? AND active = 1 LIMIT 2
                """, statement -> {
            statement.setString(1, key.authorityId());
            statement.setInt(2, residentSlot);
        });
    }

    int findFirstAvailableSlot(Connection connection,
                               ManagedCoopAuthorityKey key,
                               int maximumResidents) throws SQLException {
        if (maximumResidents <= 0) {
            return -1;
        }
        boolean[] occupied = new boolean[maximumResidents];
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resident_slot FROM managed_coop_residents WHERE authority_id = ? AND active = 1")) {
            statement.setString(1, key.authorityId());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int slot = resultSet.getInt(1);
                    if (slot >= 0 && slot < occupied.length) {
                        occupied[slot] = true;
                    }
                }
            }
        }
        for (int slot = 0; slot < occupied.length; slot++) {
            if (!occupied[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private void insertHoused(Connection connection, HousedResidentClaim claim) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 'HOUSED', 0, 1, ?, 0, ?, ?)
                """)) {
            insert.setString(1, claim.residentId());
            insert.setString(2, claim.authorityKey().authorityId());
            insert.setString(3, claim.authorityKey().worldName());
            insert.setString(4, normalizeCoopId(claim.coopId()));
            insert.setInt(5, claim.authorityKey().x());
            insert.setInt(6, claim.authorityKey().y());
            insert.setInt(7, claim.authorityKey().z());
            insert.setInt(8, claim.residentSlot());
            insert.setString(9, claim.profileId());
            insert.setString(10, claim.roleId());
            insert.setString(11, claim.sourceNpcUuid().toString());
            insert.setString(12, claim.sourceNpcUuid().toString());
            insert.setString(13, claim.snapshotJson());
            insert.setString(14, claim.snapshotHash());
            insert.setInt(15, Math.max(1, claim.snapshotVersion()));
            insert.setLong(16, claim.capturedAtMs());
            insert.setLong(17, claim.capturedAtMs());
            insert.setLong(18, claim.capturedAtMs());
            insert.executeUpdate();
        }
    }

    private boolean authorityMatches(Connection connection,
                                     ManagedCoopAuthorityKey key,
                                     String coopId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_authority
                WHERE authority_id = ? AND world_name = ? AND x = ? AND y = ? AND z = ?
                  AND lower(coop_id) = ? AND active = 1 LIMIT 1
                """)) {
            query.setString(1, key.authorityId());
            query.setString(2, key.worldName());
            query.setInt(3, key.x());
            query.setInt(4, key.y());
            query.setInt(5, key.z());
            query.setString(6, normalizeCoopId(coopId));
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean hasActiveAssignmentConflict(Connection connection, HousedResidentClaim claim)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_residents
                WHERE active = 1 AND (profile_id = ? OR (authority_id = ? AND resident_slot = ?)) LIMIT 1
                """)) {
            query.setString(1, claim.profileId());
            query.setString(2, claim.authorityKey().authorityId());
            query.setInt(3, claim.residentSlot());
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void updateState(Connection connection,
                             String residentId,
                             ResidentState expected,
                             long expectedGeneration,
                             ResidentState target,
                             long nowMs) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE managed_coop_residents
                SET state = ?, generation = generation + 1, updated_at_ms = ?
                WHERE resident_id = ? AND active = 1 AND state = ? AND generation = ?
                """)) {
            update.setString(1, target.name());
            update.setLong(2, nowMs);
            update.setString(3, residentId);
            update.setString(4, expected.name());
            update.setLong(5, expectedGeneration);
            requireUpdated(update, "resident_generation_changed");
        }
    }

    @Nullable
    private ResidentRecord loadOne(Connection connection,
                                   String sql,
                                   StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? ManagedCoopResidentRowMapper.read(resultSet) : null;
            }
        }
    }

    @Nullable
    private ResidentRecord loadUnique(Connection connection,
                                      String sql,
                                      StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ResidentRecord resident = ManagedCoopResidentRowMapper.read(resultSet);
                if (resultSet.next()) {
                    throw new SQLException("multiple_active_managed_coop_residents");
                }
                return resident;
            }
        }
    }

    private void validateClaim(HousedResidentClaim claim) {
        if (claim.residentId().isBlank() || claim.profileId().isBlank()
                || claim.coopId().isBlank() || claim.residentSlot() < 0) {
            throw new IllegalArgumentException("managed coop claim identifiers and slot must be valid");
        }
    }

    private String normalizeCoopId(String coopId) {
        if (coopId == null || coopId.isBlank()) {
            throw new IllegalArgumentException("coopId must not be blank");
        }
        return coopId.trim().toLowerCase(Locale.ROOT);
    }

    private void requireUpdated(PreparedStatement statement, String reason) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new SQLException(reason);
        }
    }

    private MutationResult result(MutationStatus status, ResidentRecord resident, String detail) {
        return new MutationResult(status, resident, detail);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
