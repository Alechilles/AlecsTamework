package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopPort;
import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Connection-bound SQLite authority for normalized coop slots and exact residency claims. */
public final class SqliteCompanionCoopStore implements CompanionCoopPort {
    private static final String SLOT_COLUMNS = """
            coop_key, world_key, coop_id, x, y, z, resident_slot,
            residency_revision, active_operation_id, reserved_profile_id
            """;
    private static final String RESIDENCY_COLUMNS = """
            coop_key, profile_id, housed_npc_uuid, snapshot_id,
            captured_at_ms, updated_at_ms
            """;

    private final Connection connection;

    public SqliteCompanionCoopStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Coop store connection is required");
        }
        this.connection = connection;
    }

    @Override
    @Nonnull
    public Optional<CoopSlot> findSlot(@Nonnull CoopSlotKey slotKey) {
        require(slotKey, "Coop slot key");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SLOT_COLUMNS + " FROM coop_slot WHERE coop_key = ?")) {
            statement.setString(1, slotKey.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readSlot(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("coop_find_slot", failure);
        }
    }

    @Override
    @Nonnull
    public Optional<CoopResidency> findResidencyBySlot(@Nonnull CoopSlotKey slotKey) {
        require(slotKey, "Coop slot key");
        return findResidency("coop_key", slotKey.toString(), "coop_find_residency_slot");
    }

    @Override
    @Nonnull
    public Optional<CoopResidency> findResidencyByProfile(@Nonnull ProfileId profileId) {
        require(profileId, "Profile ID");
        return findResidency(
                "profile_id", profileId.toString(), "coop_find_residency_profile"
        );
    }

    @Override
    @Nonnull
    public List<CoopOccupancy> findAllOccupancies() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    s.coop_key, s.world_key, s.coop_id, s.x, s.y, s.z,
                    s.resident_slot, s.residency_revision,
                    s.active_operation_id, s.reserved_profile_id,
                    r.profile_id, r.housed_npc_uuid, r.snapshot_id,
                    r.captured_at_ms, r.updated_at_ms
                FROM coop_slot s
                JOIN coop_residency r ON r.coop_key = s.coop_key
                ORDER BY s.coop_key
                """)) {
            ArrayList<CoopOccupancy> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    result.add(new CoopOccupancy(
                            readSlot(row),
                            readResidency(row)
                    ));
                }
            }
            return List.copyOf(result);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("coop_find_all_occupancies", failure);
        }
    }

    @Override
    @Nonnull
    public PersistenceMutationResult<CoopSlot> registerSlot(@Nonnull CoopSlot slot) {
        require(slot, "Coop slot");
        CoopSlot existing = findSlot(slot.key()).orElse(null);
        if (existing != null) {
            return sameStructure(existing, slot.key())
                    ? PersistenceMutationResult.applied(existing)
                    : rejected(PersistenceMutationStatus.CONFLICT);
        }
        if (slot.residencyRevision() != 0 || slot.reserved()) {
            throw new IllegalArgumentException("New coop slot must be unoccupied and unreserved");
        }
        CoopSlotKey key = slot.key();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_slot(
                    coop_key, world_key, coop_id, x, y, z, resident_slot,
                    residency_revision, active_operation_id, reserved_profile_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL)
                """)) {
            bindKey(statement, key);
            statement.executeUpdate();
            return PersistenceMutationResult.applied(slot);
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            throw storeFailure("coop_register_slot", failure);
        }
    }

    @Override
    @Nonnull
    public PersistenceMutationResult<CoopSlot> reserveEmpty(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId
    ) {
        CoopConflictDiagnostic diagnostic = diagnoseCapture(slotKey, profileId);
        if (diagnostic.reason() != CoopConflictDiagnostic.Reason.NONE) {
            return rejected(status(diagnostic.reason()));
        }
        return reserve(slotKey, profileId, operationId, false);
    }

    @Override
    @Nonnull
    public PersistenceMutationResult<CoopSlot> reserveOccupied(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId
    ) {
        CoopConflictDiagnostic diagnostic = diagnoseRelease(slotKey, profileId);
        if (diagnostic.reason() != CoopConflictDiagnostic.Reason.NONE) {
            return rejected(status(diagnostic.reason()));
        }
        return reserve(slotKey, profileId, operationId, true);
    }

    @Override
    @Nonnull
    public PersistenceMutationResult<CoopOccupancy> commitCapture(
            @Nonnull CoopResidency residency,
            @Nonnull OperationId operationId
    ) {
        require(residency, "Coop residency");
        require(operationId, "Operation ID");
        CoopSlot slot = findSlot(residency.slotKey()).orElse(null);
        if (!reservedBy(slot, residency.profileId(), operationId)) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (findResidencyBySlot(residency.slotKey()).isPresent()
                || findResidencyByProfile(residency.profileId()).isPresent()) {
            return rejected(PersistenceMutationStatus.CONFLICT);
        }
        try {
            insertResidency(residency);
            CoopSlot updated = completeReservation(
                    residency.slotKey(), residency.profileId(), operationId
            );
            return PersistenceMutationResult.applied(
                    new CoopOccupancy(updated, residency)
            );
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            throw storeFailure("coop_commit_capture", failure);
        }
    }

    @Override
    @Nonnull
    public PersistenceMutationResult<CoopSlot> commitRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId,
            @Nonnull OperationId operationId,
            long releasedAtMs
    ) {
        require(slotKey, "Coop slot key");
        require(profileId, "Profile ID");
        require(operationId, "Operation ID");
        CoopSlot slot = findSlot(slotKey).orElse(null);
        CoopResidency residency = findResidencyBySlot(slotKey).orElse(null);
        if (!reservedBy(slot, profileId, operationId)) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (residency == null || !profileId.equals(residency.profileId())) {
            return rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM coop_residency
                WHERE coop_key = ? AND profile_id = ?
                """)) {
            statement.setString(1, slotKey.toString());
            statement.setString(2, profileId.toString());
            if (statement.executeUpdate() != 1) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(
                    completeReservation(slotKey, profileId, operationId)
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("coop_commit_release", failure);
        }
    }

    @Override
    @Nonnull
    public CoopConflictDiagnostic diagnoseCapture(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        require(slotKey, "Coop slot key");
        require(profileId, "Profile ID");
        CoopSlot slot = findSlot(slotKey).orElse(null);
        CoopResidency slotResidency = findResidencyBySlot(slotKey).orElse(null);
        CoopResidency profileResidency = findResidencyByProfile(profileId).orElse(null);
        CoopConflictDiagnostic.Reason reason;
        if (slot == null) {
            reason = CoopConflictDiagnostic.Reason.SLOT_MISSING;
        } else if (slot.reserved()) {
            reason = CoopConflictDiagnostic.Reason.SLOT_RESERVED;
        } else if (slotResidency != null) {
            reason = CoopConflictDiagnostic.Reason.SLOT_OCCUPIED;
        } else if (profileResidency != null) {
            reason = CoopConflictDiagnostic.Reason.PROFILE_ALREADY_RESIDENT;
        } else {
            reason = CoopConflictDiagnostic.Reason.NONE;
        }
        return diagnostic(
                reason, slotKey, profileId, slot, slotResidency, profileResidency
        );
    }

    @Override
    @Nonnull
    public CoopConflictDiagnostic diagnoseRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        require(slotKey, "Coop slot key");
        require(profileId, "Profile ID");
        CoopSlot slot = findSlot(slotKey).orElse(null);
        CoopResidency slotResidency = findResidencyBySlot(slotKey).orElse(null);
        CoopResidency profileResidency = findResidencyByProfile(profileId).orElse(null);
        CoopConflictDiagnostic.Reason reason;
        if (slot == null) {
            reason = CoopConflictDiagnostic.Reason.SLOT_MISSING;
        } else if (slot.reserved()) {
            reason = CoopConflictDiagnostic.Reason.SLOT_RESERVED;
        } else if (slotResidency == null) {
            reason = CoopConflictDiagnostic.Reason.SLOT_EMPTY;
        } else if (!profileId.equals(slotResidency.profileId())
                || profileResidency == null
                || !slotKey.equals(profileResidency.slotKey())) {
            reason = CoopConflictDiagnostic.Reason.RESIDENT_MISMATCH;
        } else {
            reason = CoopConflictDiagnostic.Reason.NONE;
        }
        return diagnostic(
                reason, slotKey, profileId, slot, slotResidency, profileResidency
        );
    }

    private PersistenceMutationResult<CoopSlot> reserve(
            CoopSlotKey slotKey,
            ProfileId profileId,
            OperationId operationId,
            boolean occupied
    ) {
        require(operationId, "Operation ID");
        String occupancy = occupied ? "EXISTS" : "NOT EXISTS";
        String sql = """
                UPDATE coop_slot
                SET active_operation_id = ?, reserved_profile_id = ?
                WHERE coop_key = ?
                  AND active_operation_id IS NULL
                  AND %s (
                      SELECT 1 FROM coop_residency WHERE coop_key = ?
                  )
                """.formatted(occupancy);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, profileId.toString());
            statement.setString(3, slotKey.toString());
            statement.setString(4, slotKey.toString());
            if (statement.executeUpdate() != 1) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(findSlot(slotKey).orElseThrow());
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("coop_reserve_slot", failure);
        }
    }

    private CoopSlot completeReservation(
            CoopSlotKey slotKey,
            ProfileId profileId,
            OperationId operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_slot
                SET residency_revision = residency_revision + 1,
                    active_operation_id = NULL,
                    reserved_profile_id = NULL
                WHERE coop_key = ?
                  AND active_operation_id = ?
                  AND reserved_profile_id = ?
                """)) {
            statement.setString(1, slotKey.toString());
            statement.setString(2, operationId.toString());
            statement.setString(3, profileId.toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("coop_reservation_fence_mismatch");
            }
        }
        return findSlot(slotKey).orElseThrow();
    }

    private void insertResidency(CoopResidency residency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_residency(
                    coop_key, profile_id, housed_npc_uuid, snapshot_id,
                    captured_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, residency.slotKey().toString());
            statement.setString(2, residency.profileId().toString());
            if (residency.housedNpcAlias() == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, residency.housedNpcAlias().toString());
            }
            statement.setString(4, residency.snapshotId().toString());
            statement.setLong(5, residency.capturedAtMs());
            statement.setLong(6, residency.updatedAtMs());
            statement.executeUpdate();
        }
    }

    private Optional<CoopResidency> findResidency(
            String column,
            String value,
            String operation
    ) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + RESIDENCY_COLUMNS
                        + " FROM coop_residency WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readResidency(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(operation, failure);
        }
    }

    private CoopSlot readSlot(ResultSet row) throws SQLException {
        CoopSlotKey key = new CoopSlotKey(
                row.getString("world_key"),
                row.getString("coop_id"),
                row.getInt("x"),
                row.getInt("y"),
                row.getInt("z"),
                row.getInt("resident_slot")
        );
        if (!key.toString().equals(row.getString("coop_key"))) {
            throw new IllegalStateException("coop_slot_key_not_normalized");
        }
        String operationId = row.getString("active_operation_id");
        String profileId = row.getString("reserved_profile_id");
        return new CoopSlot(
                key,
                row.getLong("residency_revision"),
                operationId == null ? null : OperationId.parse(operationId),
                profileId == null ? null : ProfileId.parse(profileId)
        );
    }

    private CoopResidency readResidency(ResultSet row) throws SQLException {
        String alias = row.getString("housed_npc_uuid");
        return new CoopResidency(
                CoopSlotKey.parse(row.getString("coop_key")),
                ProfileId.parse(row.getString("profile_id")),
                alias == null ? null : NpcAlias.parse(alias),
                SnapshotId.parse(row.getString("snapshot_id")),
                row.getLong("captured_at_ms"),
                row.getLong("updated_at_ms")
        );
    }

    private void bindKey(PreparedStatement statement, CoopSlotKey key)
            throws SQLException {
        statement.setString(1, key.toString());
        statement.setString(2, key.worldKey());
        statement.setString(3, key.coopId());
        statement.setInt(4, key.x());
        statement.setInt(5, key.y());
        statement.setInt(6, key.z());
        statement.setInt(7, key.residentSlot());
    }

    private boolean reservedBy(
            CoopSlot slot,
            ProfileId profileId,
            OperationId operationId
    ) {
        return slot != null
                && operationId.equals(slot.activeOperationId())
                && profileId.equals(slot.reservedProfileId());
    }

    private boolean sameStructure(CoopSlot slot, CoopSlotKey key) {
        return slot.key().equals(key);
    }

    private CoopConflictDiagnostic diagnostic(
            CoopConflictDiagnostic.Reason reason,
            CoopSlotKey slotKey,
            ProfileId profileId,
            CoopSlot slot,
            CoopResidency slotResidency,
            CoopResidency profileResidency
    ) {
        return new CoopConflictDiagnostic(
                reason, slotKey, profileId, slot, slotResidency, profileResidency
        );
    }

    private PersistenceMutationStatus status(CoopConflictDiagnostic.Reason reason) {
        return reason == CoopConflictDiagnostic.Reason.SLOT_MISSING
                ? PersistenceMutationStatus.NOT_FOUND
                : reason == CoopConflictDiagnostic.Reason.SLOT_RESERVED
                ? PersistenceMutationStatus.FENCE_MISMATCH
                : PersistenceMutationStatus.CONFLICT;
    }

    private <T> PersistenceMutationResult<T> rejected(
            PersistenceMutationStatus status
    ) {
        return PersistenceMutationResult.rejected(status);
    }

    private boolean constraint(SQLException failure) {
        return failure.getErrorCode() == 19
                || (failure.getMessage() != null
                && failure.getMessage().contains("constraint"));
    }

    private <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }
}
