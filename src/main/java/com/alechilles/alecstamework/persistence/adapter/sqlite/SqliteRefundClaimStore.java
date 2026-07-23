package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.compensation.RefundClaimPort;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Connection-bound SQLite adapter for deterministic one-time item refund claims. */
public final class SqliteRefundClaimStore implements RefundClaimPort {
    private static final String SELECT_COLUMNS = """
            operation_id, recipient_uuid, item_id, quantity, reason_code, receipt_key,
            claimed_at_ms, delivery_evidence, delivered_at_ms
            """;

    private final Connection connection;

    public SqliteRefundClaimStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Refund claim connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<RefundClaim> findByOperation(OperationId operationId) {
        require(operationId, "Refund operation");
        return find(
                "SELECT " + SELECT_COLUMNS
                        + " FROM refund_claim WHERE operation_id = ?",
                operationId.toString(),
                "refund_find_operation"
        );
    }

    @Override
    public Optional<RefundClaim> findByReceipt(String receiptKey) {
        return find(
                "SELECT " + SELECT_COLUMNS
                        + " FROM refund_claim WHERE receipt_key = ?",
                requireText(receiptKey, "Refund receipt"),
                "refund_find_receipt"
        );
    }

    @Override
    public PersistenceMutationResult<RefundClaim> create(RefundClaim claim) {
        require(claim, "Refund claim");
        Optional<RefundClaim> existing = findByOperation(claim.operationId());
        if (existing.isPresent()) {
            return existing.get().equals(claim)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(
                            PersistenceMutationStatus.CONFLICT
                    );
        }
        Optional<RefundClaim> receiptOwner = findByReceipt(claim.receiptKey());
        if (receiptOwner.isPresent()) {
            return PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.CONFLICT
            );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO refund_claim(
                    operation_id, recipient_uuid, item_id, quantity, reason_code,
                    receipt_key, claimed_at_ms, delivery_evidence, delivered_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, claim.operationId().toString());
            statement.setString(2, claim.recipientUuid().toString());
            statement.setString(3, claim.itemId());
            statement.setInt(4, claim.quantity());
            statement.setString(5, claim.reasonCode());
            statement.setString(6, claim.receiptKey());
            statement.setLong(7, claim.claimedAtMs());
            setNullableText(statement, 8, claim.deliveryEvidence());
            setNullableLong(statement, 9, claim.deliveredAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(claim);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("refund_create", failure);
        }
    }

    @Override
    public PersistenceMutationResult<RefundClaim> complete(
            OperationId operationId,
            String receiptKey,
            String deliveryEvidence,
            long deliveredAtMs
    ) {
        require(operationId, "Refund operation");
        receiptKey = requireText(receiptKey, "Refund receipt");
        deliveryEvidence = requireText(
                deliveryEvidence,
                "Refund delivery evidence"
        );
        RefundClaim current = findByOperation(operationId).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.NOT_FOUND
            );
        }
        if (!current.receiptKey().equals(receiptKey)) {
            return PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.FENCE_MISMATCH
            );
        }
        if (current.delivered()) {
            return current.deliveryEvidence().equals(deliveryEvidence)
                    ? PersistenceMutationResult.applied(current)
                    : PersistenceMutationResult.rejected(
                            PersistenceMutationStatus.CONFLICT
                    );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE refund_claim
                SET delivery_evidence = ?, delivered_at_ms = ?
                WHERE operation_id = ? AND receipt_key = ?
                  AND delivery_evidence IS NULL AND delivered_at_ms IS NULL
                """)) {
            statement.setString(1, deliveryEvidence);
            statement.setLong(2, deliveredAtMs);
            statement.setString(3, operationId.toString());
            statement.setString(4, receiptKey);
            if (statement.executeUpdate() != 1) {
                return PersistenceMutationResult.rejected(
                        PersistenceMutationStatus.CONFLICT
                );
            }
            return PersistenceMutationResult.applied(
                    current.delivered(deliveryEvidence, deliveredAtMs)
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("refund_complete", failure);
        }
    }

    private Optional<RefundClaim> find(
            String sql,
            String key,
            String operation
    ) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(operation, failure);
        }
    }

    private RefundClaim read(ResultSet row) throws SQLException {
        long deliveredAt = row.getLong("delivered_at_ms");
        boolean deliveredAtAbsent = row.wasNull();
        return new RefundClaim(
                OperationId.parse(row.getString("operation_id")),
                UUID.fromString(row.getString("recipient_uuid")),
                row.getString("item_id"),
                row.getInt("quantity"),
                row.getString("reason_code"),
                row.getString("receipt_key"),
                row.getLong("claimed_at_ms"),
                row.getString("delivery_evidence"),
                deliveredAtAbsent ? null : deliveredAt
        );
    }

    private void setNullableText(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
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

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
