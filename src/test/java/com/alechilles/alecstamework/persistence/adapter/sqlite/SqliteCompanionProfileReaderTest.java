package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Explicit absence versus invalid-authority tests for composed profile reads. */
class SqliteCompanionProfileReaderTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId MISSING =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    @Test
    void missingProfileIsAbsentButMissingCanonicalLifecycleIsFailure() throws Exception {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                    PROFILE, "Companion", "role", null, null, null,
                    -10_000, -10_000, -10_000, 0
            ));
            connection.commit();
        }

        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteCompanionProfileReader reader = new SqliteCompanionProfileReader(reads);
            assertInstanceOf(
                    PersistenceReadResult.Absent.class,
                    reader.findByProfile(MISSING).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
            );
            PersistenceReadResult.Failed<CompanionProfileReadModel> failed =
                    assertInstanceOf(
                            PersistenceReadResult.Failed.class,
                            reader.findByProfile(PROFILE).toCompletableFuture()
                                    .get(10, TimeUnit.SECONDS)
                    );
            assertEquals(StorageFailureKind.CORRUPT, failed.failure().kind());
            assertEquals("profile_lifecycle_missing", failed.failure().code());
        } finally {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }
}
