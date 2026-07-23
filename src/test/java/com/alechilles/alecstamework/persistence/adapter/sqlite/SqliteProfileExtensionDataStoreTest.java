package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataDecoder;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDecodeResult;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction and read-contract tests for replacement profile extension data. */
class SqliteProfileExtensionDataStoreTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileExtensionKey KEY =
            new ProfileExtensionKey(PROFILE, "example:integration", "state");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = transaction()) {
            new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                    PROFILE, "Companion", "role", null, null, null,
                    -10_000, -10_000, -10_000, 0
            ));
            connection.commit();
        }
    }

    @Test
    void createsUpdatesListsAndRevisionFencesValues() throws Exception {
        try (Connection connection = transaction()) {
            SqliteProfileExtensionDataStore store =
                    new SqliteProfileExtensionDataStore(connection);
            ProfileExtensionData initial =
                    ProfileExtensionData.initial(KEY, "{\"value\":1}", -9_000);
            assertEquals(initial, store.put(initial, 0).value());
            assertEquals(
                    PersistenceMutationStatus.REVISION_MISMATCH,
                    store.put(value(1, "{\"value\":2}", -8_000), 0).status()
            );
            ProfileExtensionData updated = value(2, "{\"value\":2}", -8_000);
            assertEquals(updated, store.put(updated, 1).value());

            PersistenceReadResult.Found<ProfileExtensionData> found =
                    assertInstanceOf(PersistenceReadResult.Found.class, store.find(KEY));
            assertEquals(updated, found.value());
            PersistenceReadResult.Found<List<ProfileExtensionData>> namespace =
                    assertInstanceOf(
                            PersistenceReadResult.Found.class,
                            store.findNamespace(PROFILE, KEY.namespace())
                    );
            assertEquals(List.of(updated), namespace.value());
            connection.commit();
        }
    }

    @Test
    void distinguishesAbsentStorageFailureAndPayloadCorruption() throws Exception {
        try (Connection connection = transaction()) {
            SqliteProfileExtensionDataStore store =
                    new SqliteProfileExtensionDataStore(connection);
            assertInstanceOf(PersistenceReadResult.Absent.class, store.find(KEY));
            ProfileExtensionData initial =
                    ProfileExtensionData.initial(KEY, "{\"value\":1}", -9_000);
            store.put(initial, 0);
            try (PreparedStatement corrupt = connection.prepareStatement("""
                    UPDATE profile_extension_data SET payload_hash = ? WHERE profile_id = ?
                    """)) {
                corrupt.setString(1, "0".repeat(64));
                corrupt.setString(2, PROFILE.toString());
                corrupt.executeUpdate();
            }
            PersistenceReadResult.Found<ProfileExtensionData> corrupt =
                    assertInstanceOf(PersistenceReadResult.Found.class, store.find(KEY));
            ProfileExtensionDecodeResult.Failed decode = assertInstanceOf(
                    ProfileExtensionDecodeResult.Failed.class,
                    ProfileExtensionDataDecoder.decode(corrupt.value())
            );
            assertEquals(
                    ProfileExtensionDecodeResult.Failure.HASH_MISMATCH,
                    decode.failure()
            );
            connection.rollback();
        }

        try (Connection connection = transaction()) {
            connection.createStatement().execute("DROP TABLE profile_extension_data");
            PersistenceReadResult.Failed<ProfileExtensionData> failed = assertInstanceOf(
                    PersistenceReadResult.Failed.class,
                    new SqliteProfileExtensionDataStore(connection).find(KEY)
            );
            assertEquals(StorageFailureKind.SCHEMA, failed.failure().kind());
            connection.rollback();
        }
    }

    @Test
    void rollbackAndDeleteRemainTransactionLocal() throws Exception {
        try (Connection connection = transaction()) {
            SqliteProfileExtensionDataStore store =
                    new SqliteProfileExtensionDataStore(connection);
            ProfileExtensionData initial =
                    ProfileExtensionData.initial(KEY, "{\"value\":1}", -9_000);
            assertTrue(store.put(initial, 0).applied());
            assertTrue(store.delete(KEY, 1).applied());
            connection.rollback();
        }
        try (Connection connection = connections.openReadConnection()) {
            assertInstanceOf(
                    PersistenceReadResult.Absent.class,
                    new SqliteProfileExtensionDataStore(connection).find(KEY)
            );
        }
    }

    private ProfileExtensionData value(long revision, String json, long updatedAt) {
        return new ProfileExtensionData(
                KEY,
                ProfileExtensionDataDecoder.JSON_VERSION,
                json,
                Sha256Hash.ofUtf8(json),
                revision,
                -9_000,
                updatedAt
        );
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}
