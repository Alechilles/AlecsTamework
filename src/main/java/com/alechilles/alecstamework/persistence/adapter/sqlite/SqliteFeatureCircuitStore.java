package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuit;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Connection-bound authority for descriptor-keyed durable circuit evidence. */
final class SqliteFeatureCircuitStore {
    private final Connection connection;

    SqliteFeatureCircuitStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Feature circuit connection is required"
            );
        }
        this.connection = connection;
    }

    Map<PersistenceFeatureId, PersistenceFeatureCircuit> synchronize(
            Set<PersistenceFeatureId> registered,
            long synchronizedAtMs
    ) {
        Map<PersistenceFeatureId, PersistenceFeatureCircuit> existing =
                findAll();
        if (!registered.containsAll(existing.keySet())) {
            throw new IllegalStateException(
                    "feature_circuit_unknown_feature"
            );
        }
        for (PersistenceFeatureId featureId : registered) {
            if (!existing.containsKey(featureId)) {
                insert(PersistenceFeatureCircuit.closed(
                        featureId, synchronizedAtMs
                ));
            }
        }
        return requireExact(registered);
    }

    Map<PersistenceFeatureId, PersistenceFeatureCircuit> requireExact(
            Set<PersistenceFeatureId> registered
    ) {
        Map<PersistenceFeatureId, PersistenceFeatureCircuit> found =
                findAll();
        if (!found.keySet().equals(registered)) {
            throw new IllegalStateException(
                    "feature_circuit_registry_mismatch"
            );
        }
        return found;
    }

    private Map<PersistenceFeatureId, PersistenceFeatureCircuit> findAll() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT feature_id, state, failure_count, reason_code,
                       opened_at_ms, updated_at_ms
                FROM feature_circuit
                ORDER BY feature_id
                """);
             ResultSet rows = statement.executeQuery()) {
            HashMap<PersistenceFeatureId, PersistenceFeatureCircuit> result =
                    new HashMap<>();
            while (rows.next()) {
                PersistenceFeatureCircuit circuit = read(rows);
                if (result.put(circuit.featureId(), circuit) != null) {
                    throw new IllegalStateException(
                            "feature_circuit_duplicate"
                    );
                }
            }
            return Map.copyOf(result);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("feature_circuit_find_all", failure);
        }
    }

    private void insert(PersistenceFeatureCircuit circuit) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO feature_circuit(
                    feature_id, state, failure_count, reason_code,
                    opened_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, circuit.featureId().value());
            statement.setString(2, circuit.state().name());
            statement.setInt(3, circuit.failureCount());
            setNullableText(statement, 4, circuit.reasonCode());
            setNullableLong(statement, 5, circuit.openedAtMs());
            statement.setLong(6, circuit.updatedAtMs());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "feature_circuit_insert_rejected"
                );
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("feature_circuit_insert", failure);
        }
    }

    private PersistenceFeatureCircuit read(ResultSet row)
            throws SQLException {
        return new PersistenceFeatureCircuit(
                new PersistenceFeatureId(row.getString("feature_id")),
                PersistenceFeatureCircuitState.valueOf(
                        row.getString("state")
                ),
                row.getInt("failure_count"),
                row.getString("reason_code"),
                nullableLong(row, "opened_at_ms"),
                row.getLong("updated_at_ms")
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

    private Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        return failure instanceof PersistenceStoreException stored
                ? stored
                : new PersistenceStoreException(operation, failure);
    }
}
