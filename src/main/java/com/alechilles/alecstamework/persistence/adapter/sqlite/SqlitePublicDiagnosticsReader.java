package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Reads one sanitized, transactionally consistent diagnostic snapshot. */
final class SqlitePublicDiagnosticsReader {
    private static final PersistenceReadKind READ_KIND =
            new PersistenceReadKind("public_persistence_diagnostics");

    private final PersistenceFeatureRegistry registry;
    private final SqliteReadExecutor reads;
    private final Set<PersistenceFeatureId> featureIds;
    private final Set<ProjectionConsumerId> consumerIds;

    SqlitePublicDiagnosticsReader(
            PersistenceFeatureRegistry registry,
            SqliteReadExecutor reads
    ) {
        if (registry == null || reads == null) {
            throw new IllegalArgumentException(
                    "Public diagnostic reader dependencies are required"
            );
        }
        this.registry = registry;
        this.reads = reads;
        featureIds = registry.descriptors().stream()
                .map(PersistenceFeatureDescriptor::featureId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        consumerIds = registry.descriptors().stream()
                .flatMap(descriptor ->
                        descriptor.projectionConsumers().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    CompletionStage<PersistenceReadResult<SqlitePublicDiagnosticsSnapshot>>
    read() {
        return reads.execute(new SqliteReadCommand<>(
                READ_KIND,
                PersistenceReadPriority.DIAGNOSTIC,
                this::load
        ));
    }

    private PersistenceReadResult<SqlitePublicDiagnosticsSnapshot> load(
            Connection connection
    ) throws SQLException {
        connection.setAutoCommit(false);
        SqlitePublicDiagnosticsSnapshot snapshot =
                new SqlitePublicDiagnosticsSnapshot(
                        operationCounts(connection),
                        outboxHead(connection),
                        projectionCheckpoints(connection),
                        new SqliteFeatureCircuitStore(connection)
                                .requireExact(featureIds),
                        groupedTextCount(
                                connection,
                                """
                                SELECT failure_code, COUNT(*)
                                FROM persistence_incident
                                WHERE state = 'OPEN'
                                GROUP BY failure_code
                                ORDER BY failure_code
                                """
                        ),
                        quarantineScopeCounts(connection),
                        groupedTextCount(
                                connection,
                                """
                                SELECT reason_code, COUNT(*)
                                FROM persistence_quarantine
                                WHERE state = 'ACTIVE'
                                GROUP BY reason_code
                                ORDER BY reason_code
                                """
                        )
                );
        connection.commit();
        return PersistenceReadResult.found(snapshot, snapshot.outboxHead());
    }

    private Map<OperationKind, Map<OperationPhase, Long>>
    operationCounts(Connection connection) throws SQLException {
        HashMap<OperationKind, Map<OperationPhase, Long>> result =
                new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_kind, phase, COUNT(*) AS row_count
                FROM operation_envelope
                GROUP BY operation_kind, phase
                ORDER BY operation_kind, phase
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                OperationKind kind =
                        new OperationKind(rows.getString("operation_kind"));
                registry.requireOperation(kind);
                OperationPhase phase = OperationPhase.valueOf(
                        rows.getString("phase")
                );
                result.computeIfAbsent(
                        kind, ignored -> new HashMap<>()
                ).put(phase, rows.getLong("row_count"));
            }
        }
        HashMap<OperationKind, Map<OperationPhase, Long>> copied =
                new HashMap<>();
        result.forEach((kind, counts) ->
                copied.put(kind, Map.copyOf(counts)));
        return Map.copyOf(copied);
    }

    private long outboxHead(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(event_sequence), 0) FROM projection_outbox"
        );
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("projection_outbox_head_missing");
            }
            return row.getLong(1);
        }
    }

    private Map<ProjectionConsumerId, Long> projectionCheckpoints(
            Connection connection
    ) throws SQLException {
        HashMap<ProjectionConsumerId, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT consumer_id, acknowledged_sequence
                FROM projection_checkpoint
                ORDER BY consumer_id
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ProjectionConsumerId consumerId =
                        new ProjectionConsumerId(
                                rows.getString("consumer_id")
                        );
                if (!consumerIds.contains(consumerId)
                        || result.put(
                        consumerId,
                        rows.getLong("acknowledged_sequence")
                ) != null) {
                    throw new IllegalStateException(
                            "projection_checkpoint_registry_mismatch"
                    );
                }
            }
        }
        consumerIds.forEach(consumerId ->
                result.putIfAbsent(consumerId, 0L));
        return Map.copyOf(result);
    }

    private Map<OperationScopeType, Long> quarantineScopeCounts(
            Connection connection
    ) throws SQLException {
        HashMap<OperationScopeType, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT scope_type, COUNT(*) AS row_count
                FROM persistence_quarantine
                WHERE state = 'ACTIVE'
                GROUP BY scope_type
                ORDER BY scope_type
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.put(
                        OperationScopeType.valueOf(
                                rows.getString("scope_type")
                        ),
                        rows.getLong("row_count")
                );
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Long> groupedTextCount(
            Connection connection,
            String sql
    ) throws SQLException {
        HashMap<String, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String key = rows.getString(1);
                if (key == null || key.isBlank()
                        || result.put(key, rows.getLong(2)) != null) {
                    throw new IllegalStateException(
                            "diagnostic_grouping_invalid"
                    );
                }
            }
        }
        return Map.copyOf(result);
    }
}
