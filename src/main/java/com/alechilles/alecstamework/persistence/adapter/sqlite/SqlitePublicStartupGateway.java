package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Focused startup reads; no runtime caller receives a connection or store. */
public final class SqlitePublicStartupGateway {
    private static final PersistenceReadKind CANONICAL =
            new PersistenceReadKind("public_canonical_startup");

    private final SqliteReadExecutor reads;

    public SqlitePublicStartupGateway(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Public startup read executor is required"
            );
        }
        this.reads = reads;
    }

    /** Verifies exactly one lifecycle per profile and loads active containment. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<SqlitePublicCanonicalSnapshot>>
    loadCanonical() {
        return reads.execute(new SqliteReadCommand<>(
                CANONICAL,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                this::readCanonical
        ));
    }

    private PersistenceReadResult<SqlitePublicCanonicalSnapshot> readCanonical(
            Connection connection
    ) throws Exception {
        long profiles = scalar(
                connection,
                "SELECT COUNT(*) FROM companion_profile"
        );
        long lifecycles = scalar(
                connection,
                "SELECT COUNT(*) FROM companion_lifecycle"
        );
        long missingLifecycles = scalar(connection, """
                SELECT COUNT(*)
                FROM companion_profile profile
                LEFT JOIN companion_lifecycle lifecycle
                  ON lifecycle.profile_id = profile.profile_id
                WHERE lifecycle.profile_id IS NULL
                """);
        long orphanedLifecycles = scalar(connection, """
                SELECT COUNT(*)
                FROM companion_lifecycle lifecycle
                LEFT JOIN companion_profile profile
                  ON profile.profile_id = lifecycle.profile_id
                WHERE profile.profile_id IS NULL
                """);
        if (profiles != lifecycles
                || missingLifecycles != 0
                || orphanedLifecycles != 0) {
            return PersistenceReadResult.failed(new StorageFailure(
                    StorageFailureKind.CORRUPT,
                    "canonical_profile_lifecycle_mismatch",
                    CANONICAL.value(),
                    false,
                    null
            ));
        }
        SqlitePublicCanonicalSnapshot snapshot =
                new SqlitePublicCanonicalSnapshot(
                        profiles,
                        lifecycles,
                        new SqliteIncidentStore(connection)
                                .findAllActiveQuarantines()
                );
        return PersistenceReadResult.found(snapshot, profiles);
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException(
                        "canonical_count_result_missing"
                );
            }
            long value = row.getLong(1);
            if (row.next()) {
                throw new IllegalStateException(
                        "canonical_count_result_ambiguous"
                );
            }
            return value;
        }
    }
}
