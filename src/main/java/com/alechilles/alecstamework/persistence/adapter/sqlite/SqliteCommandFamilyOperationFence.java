package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.annotation.Nonnull;

/**
 * Serializes pre-durable command-family writers through existing operation participants.
 *
 * <p>The shared single writer makes the conflict check and new operation preparation atomic.
 * Once an operation has a durable commit, publication retries no longer hold the family fence.</p>
 */
final class SqliteCommandFamilyOperationFence {
    private static final String CONFLICT_SQL = """
            SELECT participant.operation_id
            FROM operation_participant participant
            JOIN operation_envelope operation
              ON operation.operation_id = participant.operation_id
            WHERE participant.scope_type = 'COMMAND_FAMILY'
              AND participant.scope_key = ?
              AND participant.operation_id <> ?
              AND operation.durable_at_ms IS NULL
              AND operation.phase NOT IN ('PUBLISHED', 'COMPENSATED', 'FAILED')
            LIMIT 1
            """;

    private SqliteCommandFamilyOperationFence() {
    }

    static void requireAvailable(
            @Nonnull Connection connection,
            @Nonnull OperationEnvelope operation
    ) {
        if (connection == null || operation == null) {
            throw new IllegalArgumentException(
                    "Command-family fence context is required"
            );
        }
        if (operation.durableAtMs() != null
                || operation.phase().isTerminal()) {
            return;
        }
        for (OperationScope participant : operation.participants()) {
            if (participant.type() == OperationScopeType.COMMAND_FAMILY) {
                requireAvailable(
                        connection, operation, participant
                );
            }
        }
    }

    private static void requireAvailable(
            Connection connection,
            OperationEnvelope operation,
            OperationScope family
    ) {
        try (PreparedStatement statement =
                     connection.prepareStatement(CONFLICT_SQL)) {
            statement.setString(1, family.key());
            statement.setString(2, operation.operationId().toString());
            try (var row = statement.executeQuery()) {
                if (row.next()) {
                    throw new IllegalStateException(
                            "operation_command_family_busy"
                    );
                }
            }
        } catch (SQLException failure) {
            throw new PersistenceStoreException(
                    "operation_command_family_fence", failure
            );
        }
    }
}
