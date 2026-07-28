package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact descriptor synchronization gates for durable feature circuits. */
class SqliteFeatureCircuitStoreTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private Set<PersistenceFeatureId> featureIds;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("feature-circuits.sqlite")
        );
        new SqliteSchemaV1Manager(
                connections, () -> -1_000
        ).initialize();
        featureIds = PublicPersistenceFeatureRegistry.create()
                .descriptors().stream()
                .map(PersistenceFeatureDescriptor::featureId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Test
    void missingRowsInitializeClosedAndExistingEvidenceIsRetained()
            throws Exception {
        try (Connection connection =
                     connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqliteFeatureCircuitStore store =
                    new SqliteFeatureCircuitStore(connection);
            var first = store.synchronize(featureIds, -900);
            connection.commit();

            assertEquals(featureIds, first.keySet());
            assertEquals(
                    Set.of(PersistenceFeatureCircuitState.CLOSED),
                    first.values().stream()
                            .map(circuit -> circuit.state())
                            .collect(java.util.stream.Collectors.toSet())
            );
            assertEquals(
                    -900,
                    first.get(
                            PublicPersistenceFeatureRegistry.CAPTURE
                    ).updatedAtMs()
            );

            connection.setAutoCommit(false);
            var replay = store.synchronize(featureIds, -800);
            connection.commit();
            assertEquals(first, replay);
        }
    }

    @Test
    void unknownDurableCircuitCannotCreateASecondFeatureCatalog()
            throws Exception {
        try (Connection connection =
                     connections.openWriterConnection()) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO feature_circuit(
                        feature_id, state, failure_count, reason_code,
                        opened_at_ms, updated_at_ms
                    ) VALUES ('unknown_feature', 'CLOSED', 0, NULL, NULL, ?)
                    """)) {
                statement.setLong(1, -900);
                statement.executeUpdate();
            }

            assertThrows(
                    IllegalStateException.class,
                    () -> new SqliteFeatureCircuitStore(connection)
                            .synchronize(featureIds, -800)
            );
        }
    }
}
