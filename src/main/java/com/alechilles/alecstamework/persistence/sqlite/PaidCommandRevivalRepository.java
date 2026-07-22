package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.google.gson.Gson;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQLite authority for paid revival idempotency, frozen costs, reservations, and recovery. */
public final class PaidCommandRevivalRepository {
    private static final String OP_COLUMNS = """
            SELECT operation_id, caller_namespace, idempotency_key, owner_uuid, profile_id,
                   command_family_id, role_id, config_id, config_revision, death_revision,
                   profile_revision, population_admission_operation_id, placement_fingerprint,
                   revive_projection_operation_id, state, detail, created_at_ms, updated_at_ms,
                   completed_at_ms
            FROM paid_command_revival_operations
            """;

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public PaidCommandRevivalRepository(@Nonnull SqliteConnectionManager connectionManager,
                                        @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareAsync(
            @Nonnull PaidCommandRevivalRecord requested) {
        if (requested.state() != PaidCommandRevivalRecord.State.PREPARED
                || !requested.reservations().isEmpty() || requested.completedAtMs() != null) {
            throw new IllegalArgumentException("new paid revival must be an uncompleted PREPARED operation");
        }
        return writeQueue.submitTracked("paid_command_revival_prepare",
                connection -> prepare(connection, requested), null);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> reserveAsync(
            @Nonnull UUID operationId,
            @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations,
            long nowMs) {
        List<PaidCommandRevivalRecord.Reservation> frozen = List.copyOf(reservations);
        return writeQueue.submitTracked("paid_command_revival_reserve",
                connection -> reserve(connection, operationId, frozen, nowMs), null);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> transitionAsync(
            @Nonnull UUID operationId,
            @Nonnull PaidCommandRevivalRecord.State expected,
            @Nonnull PaidCommandRevivalRecord.State next,
            @Nullable String detail,
            long nowMs) {
        validateTransition(expected, next);
        return writeQueue.submitTracked("paid_command_revival_transition",
                connection -> transition(connection, operationId, expected, next, detail, nowMs), null);
    }

    @Nullable
    public PaidCommandRevivalRecord findByIdempotency(@Nonnull String callerNamespace,
                                                       @Nonnull String idempotencyKey) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OP_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ?")) {
            statement.setString(1, requireText(callerNamespace, "callerNamespace"));
            statement.setString(2, requireText(idempotencyKey, "idempotencyKey"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    @Nullable
    public PaidCommandRevivalRecord find(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return find(connection, operationId);
        }
    }

    @Nonnull
    public List<PaidCommandRevivalRecord> loadRecoverable() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(OP_COLUMNS
                     + " WHERE state IN ('PREPARED','RESERVED','COST_CONSUMED','APPLYING',"
                     + "'REFUND_REQUIRED','QUARANTINED') ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            ArrayList<PaidCommandRevivalRecord> rows = new ArrayList<>();
            while (result.next()) rows.add(read(connection, result));
            return List.copyOf(rows);
        }
    }

    private MutationResult prepare(Connection connection, PaidCommandRevivalRecord requested) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord existing = findByIdempotency(
                    connection, requested.callerNamespace(), requested.idempotencyKey());
            if (existing != null) {
                MutationResult result = sameRequest(existing, requested)
                        ? new MutationResult(Status.IDEMPOTENT, existing, null)
                        : new MutationResult(Status.CONFLICT, existing, "idempotency-key-payload-mismatch");
                connection.commit();
                return result;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_operations (
                        operation_id, caller_namespace, idempotency_key, owner_uuid, profile_id,
                        command_family_id, role_id, config_id, config_revision, death_revision,
                        profile_revision, population_admission_operation_id, placement_fingerprint,
                        revive_projection_operation_id, state, detail, created_at_ms, updated_at_ms,
                        completed_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, NULL)
                    """)) {
                int index = 1;
                statement.setString(index++, requested.operationId().toString());
                statement.setString(index++, requested.callerNamespace());
                statement.setString(index++, requested.idempotencyKey());
                statement.setString(index++, requested.ownerUuid().toString());
                statement.setString(index++, requested.profileId());
                statement.setString(index++, requested.commandFamilyId());
                statement.setString(index++, requested.roleId());
                setNullable(statement, index++, requested.configId());
                statement.setString(index++, requested.configRevision());
                statement.setLong(index++, requested.deathRevision());
                statement.setLong(index++, requested.profileRevision());
                setNullable(statement, index++, requested.populationAdmissionOperationId());
                setNullable(statement, index++, requested.placementFingerprint());
                setNullable(statement, index++, requested.reviveProjectionOperationId());
                setNullable(statement, index++, requested.detail());
                statement.setLong(index++, requested.createdAtMs());
                statement.setLong(index, requested.updatedAtMs());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_costs(operation_id, ordinal, item_id, quantity)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (int ordinal = 0; ordinal < requested.exactCost().size(); ordinal++) {
                    ItemCostComponentView cost = requested.exactCost().get(ordinal);
                    statement.setString(1, requested.operationId().toString());
                    statement.setInt(2, ordinal);
                    statement.setString(3, cost.itemId());
                    statement.setInt(4, cost.quantity());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
            return new MutationResult(Status.APPLIED, requested, null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private MutationResult reserve(Connection connection, UUID operationId,
                                   List<PaidCommandRevivalRecord.Reservation> reservations,
                                   long nowMs) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord current = find(connection, operationId);
            if (current == null) return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            if (current.state() == PaidCommandRevivalRecord.State.RESERVED
                    && current.reservations().equals(reservations)) {
                connection.commit();
                return new MutationResult(Status.IDEMPOTENT, current, null);
            }
            if (current.state() != PaidCommandRevivalRecord.State.PREPARED) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, current, "operation-not-prepared");
            }
            validateReservations(current.exactCost(), reservations);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_reservations (
                        operation_id, cost_ordinal, stack_ordinal, compartment_id, slot_index,
                        quantity, source_stack_fingerprint, reservation_generation, state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HELD')
                    """)) {
                for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
                    statement.setString(1, operationId.toString());
                    statement.setInt(2, reservation.costOrdinal());
                    statement.setInt(3, reservation.stackOrdinal());
                    statement.setString(4, reservation.compartmentId());
                    statement.setInt(5, reservation.slotIndex());
                    statement.setInt(6, reservation.quantity());
                    statement.setString(7, reservation.sourceStackFingerprint());
                    statement.setLong(8, reservation.reservationGeneration());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            updateState(connection, operationId, PaidCommandRevivalRecord.State.PREPARED,
                    PaidCommandRevivalRecord.State.RESERVED, null, nowMs);
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, operationId), null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private MutationResult transition(Connection connection, UUID operationId,
                                      PaidCommandRevivalRecord.State expected,
                                      PaidCommandRevivalRecord.State next,
                                      String detail, long nowMs) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord current = find(connection, operationId);
            if (current == null) return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            if (current.state() == next) {
                connection.commit();
                return new MutationResult(Status.IDEMPOTENT, current, null);
            }
            if (current.state() != expected) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, current, "unexpected-operation-state");
            }
            updateState(connection, operationId, expected, next, detail, nowMs);
            updateReservationStates(connection, operationId, next);
            if (next == PaidCommandRevivalRecord.State.REFUND_REQUIRED) {
                upsertRefundClaim(connection, current, nowMs);
            } else if (next == PaidCommandRevivalRecord.State.REFUNDED) {
                markRefundDelivered(connection, operationId, nowMs);
            }
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, operationId), null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void updateState(Connection connection, UUID operationId,
                             PaidCommandRevivalRecord.State expected,
                             PaidCommandRevivalRecord.State next,
                             String detail, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_operations
                SET state = ?, detail = ?, updated_at_ms = ?, completed_at_ms = ?
                WHERE operation_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            setNullable(statement, 2, detail);
            statement.setLong(3, nowMs);
            if (terminal(next)) statement.setLong(4, nowMs); else statement.setNull(4, Types.BIGINT);
            statement.setString(5, operationId.toString());
            statement.setString(6, expected.name());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("operation-state-cas-failed");
        }
    }

    private void updateReservationStates(Connection connection, UUID operationId,
                                         PaidCommandRevivalRecord.State next) throws Exception {
        String reservationState = switch (next) {
            case COST_CONSUMED, APPLYING, SUCCEEDED -> "CONSUMED";
            case CANCELED -> "RELEASED";
            case REFUND_REQUIRED, QUARANTINED -> "REFUND_REQUIRED";
            case REFUNDED -> "REFUNDED";
            default -> null;
        };
        if (reservationState == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_reservations SET state = ? WHERE operation_id = ?
                """)) {
            statement.setString(1, reservationState);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private void upsertRefundClaim(Connection connection, PaidCommandRevivalRecord operation,
                                   long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO paid_command_revival_refund_claims(
                    operation_id, owner_uuid, exact_cost_json, state, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT(operation_id) DO UPDATE SET updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.ownerUuid().toString());
            statement.setString(3, new Gson().toJson(operation.exactCost()));
            statement.setLong(4, nowMs);
            statement.setLong(5, nowMs);
            statement.executeUpdate();
        }
    }

    private void markRefundDelivered(Connection connection, UUID operationId, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_refund_claims
                SET state = 'DELIVERED', updated_at_ms = ?
                WHERE operation_id = ? AND state IN ('PENDING','DELIVERING')
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private PaidCommandRevivalRecord find(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(OP_COLUMNS + " WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    private PaidCommandRevivalRecord findByIdempotency(Connection connection, String callerNamespace,
                                                        String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OP_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ?")) {
            statement.setString(1, callerNamespace);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    private PaidCommandRevivalRecord read(Connection connection, ResultSet result) throws Exception {
        UUID operationId = UUID.fromString(result.getString("operation_id"));
        return new PaidCommandRevivalRecord(
                operationId, result.getString("caller_namespace"), result.getString("idempotency_key"),
                UUID.fromString(result.getString("owner_uuid")), result.getString("profile_id"),
                result.getString("command_family_id"), result.getString("role_id"),
                result.getString("config_id"), result.getString("config_revision"),
                result.getLong("death_revision"), result.getLong("profile_revision"),
                result.getString("population_admission_operation_id"),
                result.getString("placement_fingerprint"),
                result.getString("revive_projection_operation_id"),
                PaidCommandRevivalRecord.State.valueOf(result.getString("state")),
                readCosts(connection, operationId), readReservations(connection, operationId),
                result.getString("detail"), result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"), nullableLong(result, "completed_at_ms"));
    }

    private List<ItemCostComponentView> readCosts(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item_id, quantity FROM paid_command_revival_costs
                WHERE operation_id = ? ORDER BY ordinal
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                ArrayList<ItemCostComponentView> rows = new ArrayList<>();
                while (result.next()) rows.add(new ItemCostComponentView(
                        result.getString("item_id"), result.getInt("quantity")));
                return List.copyOf(rows);
            }
        }
    }

    private List<PaidCommandRevivalRecord.Reservation> readReservations(
            Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT cost_ordinal, stack_ordinal, compartment_id, slot_index, quantity,
                       source_stack_fingerprint, reservation_generation, state
                FROM paid_command_revival_reservations WHERE operation_id = ?
                ORDER BY cost_ordinal, stack_ordinal
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                ArrayList<PaidCommandRevivalRecord.Reservation> rows = new ArrayList<>();
                while (result.next()) rows.add(new PaidCommandRevivalRecord.Reservation(
                        result.getInt("cost_ordinal"), result.getInt("stack_ordinal"),
                        result.getString("compartment_id"), result.getInt("slot_index"),
                        result.getInt("quantity"), result.getString("source_stack_fingerprint"),
                        result.getLong("reservation_generation"),
                        PaidCommandRevivalRecord.ReservationState.valueOf(result.getString("state"))));
                return List.copyOf(rows);
            }
        }
    }

    private static void validateReservations(List<ItemCostComponentView> costs,
                                             List<PaidCommandRevivalRecord.Reservation> reservations) {
        int[] totals = new int[costs.size()];
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            if (reservation.costOrdinal() >= costs.size()) {
                throw new IllegalArgumentException("reservation cost ordinal out of bounds");
            }
            totals[reservation.costOrdinal()] = Math.addExact(
                    totals[reservation.costOrdinal()], reservation.quantity());
        }
        for (int ordinal = 0; ordinal < costs.size(); ordinal++) {
            if (totals[ordinal] != costs.get(ordinal).quantity()) {
                throw new IllegalArgumentException("reservation total does not match cost " + ordinal);
            }
        }
    }

    private static boolean sameRequest(PaidCommandRevivalRecord left, PaidCommandRevivalRecord right) {
        return left.ownerUuid().equals(right.ownerUuid())
                && left.profileId().equals(right.profileId())
                && left.commandFamilyId().equals(right.commandFamilyId())
                && left.roleId().equals(right.roleId())
                && Objects.equals(left.configId(), right.configId())
                && left.configRevision().equals(right.configRevision())
                && left.deathRevision() == right.deathRevision()
                && left.profileRevision() == right.profileRevision()
                && left.exactCost().equals(right.exactCost());
    }

    private static void validateTransition(PaidCommandRevivalRecord.State expected,
                                           PaidCommandRevivalRecord.State next) {
        boolean allowed = switch (expected) {
            case PREPARED -> next == PaidCommandRevivalRecord.State.CANCELED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case RESERVED -> next == PaidCommandRevivalRecord.State.COST_CONSUMED
                    || next == PaidCommandRevivalRecord.State.CANCELED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case COST_CONSUMED -> next == PaidCommandRevivalRecord.State.APPLYING
                    || next == PaidCommandRevivalRecord.State.REFUND_REQUIRED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case APPLYING -> next == PaidCommandRevivalRecord.State.SUCCEEDED
                    || next == PaidCommandRevivalRecord.State.REFUND_REQUIRED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case REFUND_REQUIRED -> next == PaidCommandRevivalRecord.State.REFUNDED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case QUARANTINED -> next == PaidCommandRevivalRecord.State.REFUND_REQUIRED;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("invalid paid revival transition: " + expected + " -> " + next);
    }

    private static boolean terminal(PaidCommandRevivalRecord.State state) {
        return state == PaidCommandRevivalRecord.State.SUCCEEDED
                || state == PaidCommandRevivalRecord.State.CANCELED
                || state == PaidCommandRevivalRecord.State.REFUNDED;
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public enum Status { APPLIED, IDEMPOTENT, CONFLICT, NOT_FOUND }

    public record MutationResult(@Nonnull Status status,
                                 @Nullable PaidCommandRevivalRecord operation,
                                 @Nullable String reason) {
    }
}
