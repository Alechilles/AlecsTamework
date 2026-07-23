package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Canonical lifecycle cardinality and startup containment read gates. */
class SqlitePublicStartupGatewayTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteReadExecutor reads;
    private SqlitePublicStartupGateway gateway;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -100).initialize();
        reads = new SqliteReadExecutor(connections);
        gateway = new SqlitePublicStartupGateway(reads);
    }

    @AfterEach
    void tearDown() {
        reads.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void emptyCanonicalTargetIsConsistent() throws Exception {
        PersistenceReadResult.Found<SqlitePublicCanonicalSnapshot> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        read()
                );

        assertEquals(0, found.value().profileCount());
        assertEquals(0, found.value().lifecycleCount());
        assertEquals(0, found.value().activeQuarantines().size());
    }

    @Test
    void profileWithoutLifecycleFailsAsCorruptInsteadOfLookingAbsent()
            throws Exception {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO companion_profile(
                        profile_id, display_name, role_id, metadata_json,
                        metadata_hash, last_known_world_key, created_at_ms,
                        updated_at_ms, last_active_at_ms, metadata_revision
                    ) VALUES (
                        '20000000-0000-0000-0000-000000000001', 'Companion',
                        'role', NULL, NULL, 'world',
                        -100, -100, -100, 0
                    )
                    """);
        }

        PersistenceReadResult.Failed<SqlitePublicCanonicalSnapshot> failed =
                assertInstanceOf(
                        PersistenceReadResult.Failed.class,
                        read()
                );

        assertEquals(
                "canonical_profile_lifecycle_mismatch",
                failed.failure().code()
        );
    }

    private PersistenceReadResult<SqlitePublicCanonicalSnapshot> read()
            throws Exception {
        return gateway.loadCanonical().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }
}
