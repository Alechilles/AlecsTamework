package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Target selection tests for fresh, existing, and unsupported source paths. */
class PublicPersistenceTargetOpenerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndThenReusesOneVerifiedFreshTarget() {
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);

        PublicPersistenceTarget fresh = opener.open(tempDir);
        PublicPersistenceTarget existing = opener.open(tempDir);

        assertEquals(PublicPersistenceTarget.Origin.FRESH, fresh.origin());
        assertEquals(PublicPersistenceTarget.Origin.EXISTING, existing.origin());
        assertEquals(fresh.databasePath(), existing.databasePath());
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                new SqliteSchemaV1Manager(new SqliteConnectionFactory(
                        fresh.databasePath()
                )).verify()
        );
        assertNoCreatingAttempts();
    }

    @Test
    void refusesDevelopmentSourceBeforeCreatingTarget() throws Exception {
        Path source = PersistenceFiles.legacyDatabase(tempDir);
        executeFixture(source, "development-v5-marker.sql");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new PublicPersistenceTargetOpener(() -> -100)
                        .open(tempDir)
        );

        assertTrue(failure.getMessage().endsWith(
                ":UNSUPPORTED_DEVELOPMENT_SCHEMA"
        ));
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.notExists(
                PersistenceFiles.replacementDatabase(tempDir)
        ));
        assertNoCreatingAttempts();
    }

    private void executeFixture(Path database, String fixture)
            throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/persistence-consolidation/" + fixture
        )) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + fixture);
            }
            sql = new String(
                    stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        }
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void assertNoCreatingAttempts() {
        try (var paths = Files.list(tempDir)) {
            assertTrue(paths.noneMatch(path ->
                    path.getFileName().toString().contains(".creating.")));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
