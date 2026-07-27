package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionLegacyPaymentSettlementGroup;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionOperationProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Groups and quarantines non-injective historical payment identities. */
final class SqliteBondedCompanionLegacyPaymentStore {
    private final Connection connection;

    SqliteBondedCompanionLegacyPaymentStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    List<BondedCompanionLegacyPaymentSettlementGroup> listAwaiting(
            UUID ownerUuid, int limit) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (limit <= 0) throw new IllegalArgumentException("limit is required");
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH legacy_group AS (
                    SELECT caller_namespace || ':' || idempotency_key
                               AS legacy_operation_id,
                           MIN(updated_at_ms) AS first_updated_at_ms
                    FROM bonded_companion_operation
                    WHERE owner_uuid = ? AND operation_type = 'REVIVE'
                      AND expected_revision IS NULL
                    GROUP BY legacy_operation_id
                    HAVING MAX(CASE
                        WHEN operation_state IN ('SUCCEEDED', 'REJECTED')
                         AND result_json IS NOT NULL
                         AND expires_at_ms = 9223372036854775807
                        THEN 1 ELSE 0 END) = 1
                    ORDER BY first_updated_at_ms, legacy_operation_id
                    LIMIT ?
                )
                SELECT group_row.legacy_operation_id,
                       operation.caller_namespace, operation.idempotency_key,
                       operation.roster_id, operation.profile_id
                FROM legacy_group group_row
                JOIN bonded_companion_operation operation
                  ON operation.caller_namespace || ':' ||
                     operation.idempotency_key = group_row.legacy_operation_id
                 AND operation.owner_uuid = ?
                 AND operation.operation_type = 'REVIVE'
                 AND operation.expected_revision IS NULL
                ORDER BY group_row.first_updated_at_ms,
                         group_row.legacy_operation_id,
                         operation.caller_namespace,
                         operation.idempotency_key
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setInt(2, limit);
            statement.setString(3, ownerUuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return readGroups(rows, ownerUuid);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "list-legacy-bonded-payment-groups", failure);
        }
    }

    int quarantine(
            UUID ownerUuid, String operationId, long retainedUntilMs) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String identity = requireText(operationId, "operationId");
        if (retainedUntilMs == 0L || retainedUntilMs == Long.MAX_VALUE) {
            throw new IllegalArgumentException("retainedUntilMs is required");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_companion_operation
                SET expires_at_ms = ?
                WHERE owner_uuid = ? AND operation_type = 'REVIVE'
                  AND expected_revision IS NULL
                  AND expires_at_ms = 9223372036854775807
                  AND caller_namespace || ':' || idempotency_key = ?
                """)) {
            statement.setLong(1, retainedUntilMs);
            statement.setString(2, ownerUuid.toString());
            statement.setString(3, identity);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "quarantine-legacy-bonded-payment-group", failure);
        }
    }

    private List<BondedCompanionLegacyPaymentSettlementGroup> readGroups(
            ResultSet rows, UUID ownerUuid) throws SQLException {
        ArrayList<BondedCompanionLegacyPaymentSettlementGroup> result =
                new ArrayList<>();
        String operationId = null;
        ArrayList<BondedCompanionOperationProbe> members = new ArrayList<>();
        while (rows.next()) {
            String nextId = rows.getString(1);
            if (operationId != null && !operationId.equals(nextId)) {
                result.add(new BondedCompanionLegacyPaymentSettlementGroup(
                        operationId, members));
                members = new ArrayList<>();
            }
            operationId = nextId;
            members.add(new BondedCompanionOperationProbe(
                    rows.getString(2), rows.getString(3), ownerUuid,
                    rows.getString(4), rows.getString(5),
                    BondedCompanionOperation.Type.REVIVE, null));
        }
        if (operationId != null) {
            result.add(new BondedCompanionLegacyPaymentSettlementGroup(
                    operationId, members));
        }
        return List.copyOf(result);
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
