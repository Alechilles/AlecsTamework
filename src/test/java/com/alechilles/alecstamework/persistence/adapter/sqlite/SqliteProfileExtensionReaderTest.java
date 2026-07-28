package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Logical extension read tests for active, deleted, corrupt, absent, and failed evidence. */
class SqliteProfileExtensionReaderTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileExtensionKey KEY =
            new ProfileExtensionKey(PROFILE, "example:integration", "state");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteReadExecutor reads;
    private SqliteProfileExtensionReader reader;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = transaction()) {
            new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    null,
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            connection.commit();
        }
        reads = new SqliteReadExecutor(connections);
        reader = new SqliteProfileExtensionReader(reads);
    }

    @AfterEach
    void tearDown() {
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void logicalReadSeparatesActiveDeletedAndAbsent() throws Exception {
        assertInstanceOf(PersistenceReadResult.Absent.class, find());
        try (Connection connection = transaction()) {
            new SqliteProfileExtensionDataStore(connection).put(
                    ProfileExtensionData.initial(KEY, "{\"value\":1}", -9_000),
                    0
            );
            connection.commit();
        }
        PersistenceReadResult.Found<ProfileExtensionData> active = assertInstanceOf(
                PersistenceReadResult.Found.class,
                find()
        );
        assertEquals("{\"value\":1}", active.value().jsonPayload());
        PersistenceReadResult.Found<List<ProfileExtensionData>> listed = assertInstanceOf(
                PersistenceReadResult.Found.class,
                list()
        );
        assertEquals(1, listed.value().size());
        try (Connection connection = transaction()) {
            new SqliteProfileExtensionDataStore(connection).delete(KEY, 1, -8_000);
            connection.commit();
        }
        assertInstanceOf(PersistenceReadResult.Absent.class, find());
        PersistenceReadResult.Found<List<ProfileExtensionData>> empty = assertInstanceOf(
                PersistenceReadResult.Found.class,
                list()
        );
        assertEquals(List.of(), empty.value());
    }

    @Test
    void corruptPayloadIsDecodeFailureRatherThanAbsence() throws Exception {
        try (Connection connection = transaction()) {
            new SqliteProfileExtensionDataStore(connection).put(
                    ProfileExtensionData.initial(KEY, "{\"value\":1}", -9_000),
                    0
            );
            try (PreparedStatement corrupt = connection.prepareStatement("""
                    UPDATE profile_extension_data
                    SET payload_hash = ?
                    WHERE profile_id = ? AND namespace = ? AND data_key = ?
                    """)) {
                corrupt.setString(1, "0".repeat(64));
                corrupt.setString(2, PROFILE.toString());
                corrupt.setString(3, KEY.namespace());
                corrupt.setString(4, KEY.dataKey());
                corrupt.executeUpdate();
            }
            connection.commit();
        }

        PersistenceReadResult.Failed<ProfileExtensionData> failed = assertInstanceOf(
                PersistenceReadResult.Failed.class,
                find()
        );
        assertEquals(StorageFailureKind.DECODE, failed.failure().kind());
        assertEquals("extension_hash_mismatch", failed.failure().code());
        assertInstanceOf(PersistenceReadResult.Failed.class, list());
    }

    private PersistenceReadResult<ProfileExtensionData> find() throws Exception {
        return reader.findActive(KEY).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private PersistenceReadResult<List<ProfileExtensionData>> list() throws Exception {
        return reader.findNamespace(PROFILE, KEY.namespace())
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}
