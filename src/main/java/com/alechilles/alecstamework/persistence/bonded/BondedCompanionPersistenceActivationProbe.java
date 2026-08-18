package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.activation
        .TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Performs a bounded, immutable probe of the separate bonded authority.
 *
 * <p>The probe does not construct a bonded schema manager, persistence runtime,
 * writer, operation executor, or recovery worker. Generic persistence state is
 * not consulted, which preserves bonded-only recovery when the generic
 * authority is unavailable.</p>
 */
public final class BondedCompanionPersistenceActivationProbe {
    private final Path databasePath;
    private final SqliteConnectionFactory connections;
    private final BondedCompanionSchemaCatalog catalog =
            new BondedCompanionSchemaCatalog();

    /** Creates a probe for one normalized bonded database path. */
    public BondedCompanionPersistenceActivationProbe(@Nonnull Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();
        this.connections = new SqliteConnectionFactory(this.databasePath);
    }

    /** Returns the probed path without exposing a mutable connection. */
    @Nonnull
    public Path databasePath() {
        return databasePath;
    }

    /** Classifies bonded evidence using at most one immutable SQLite session. */
    @Nonnull
    public TameworkPersistenceActivationEvidence probe() {
        SidecarState sidecars = inspectSidecars();
        if (sidecars == SidecarState.UNKNOWN) {
            return TameworkPersistenceActivationEvidence.readOnly(
                    true, "bonded-sidecar-state-unavailable");
        }
        if (sidecars != SidecarState.NONE) {
            return TameworkPersistenceActivationEvidence.readOnly(
                    true, sidecars == SidecarState.INVALID
                            ? "bonded-sidecar-not-regular"
                            : "bonded-sidecar-present");
        }
        PathState state = inspectPath(databasePath);
        if (state == PathState.UNKNOWN) {
            return TameworkPersistenceActivationEvidence.readOnly(
                    true, "bonded-file-state-unavailable");
        }
        if (state == PathState.MISSING) {
            return TameworkPersistenceActivationEvidence.dormant(false, false);
        }
        if (state != PathState.REGULAR) {
            return TameworkPersistenceActivationEvidence.readOnly(
                    true, "bonded-file-not-regular");
        }
        try (Connection connection =
                     connections.openImmutableSchemaProbeConnection()) {
            if (connection == null) {
                return TameworkPersistenceActivationEvidence.readOnly(
                        true, "bonded-file-raced-away");
            }
            if (!schemaIdentityMatches(connection)) {
                return TameworkPersistenceActivationEvidence.readOnly(
                        true, "bonded-schema-unverified");
            }
            Set<String> evidence = durableEvidence(connection);
            if (evidence.isEmpty()) {
                return TameworkPersistenceActivationEvidence.dormant(true, true);
            }
            return TameworkPersistenceActivationEvidence.active(evidence);
        } catch (Exception failure) {
            return TameworkPersistenceActivationEvidence.readOnly(
                    true, diagnosticCode(failure));
        }
    }

    private boolean schemaIdentityMatches(Connection connection)
            throws Exception {
        if (!BondedCompanionSchemaAuthorityVerifier.hasExactFinalSchema(
                connection, catalog.script())) {
            return false;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM bonded_schema_history
                     """)) {
            if (!rows.next()
                    || rows.getInt("version")
                    != BondedCompanionSchemaManager.VERSION
                    || !BondedCompanionSchemaManager.LINEAGE.equals(
                    rows.getString("lineage"))
                    || !catalog.hash().equals(rows.getString("schema_hash"))
                    || rows.next()) {
                return false;
            }
        }
        if (!BondedCompanionSchemaAuthorityVerifier
                .hasDurableCaptureSourceFence(connection)) {
            return false;
        }
        try {
            new BondedCompanionStoredRowValidator().verify(connection);
        } catch (BondedCompanionStoredRowValidator.InvalidRecordException
                 failure) {
            return false;
        }
        if (!singleValue(connection, "PRAGMA quick_check(1)", "ok")
                || hasRows(connection, "PRAGMA foreign_key_check")) {
            return false;
        }
        return noOrphanedActiveLeases(connection);
    }

    private boolean noOrphanedActiveLeases(Connection connection)
            throws SQLException {
        return !hasRows(connection, """
                SELECT p.profile_id
                FROM bonded_companion_profile p
                LEFT JOIN bonded_companion_lease l
                  ON l.profile_id = p.profile_id
                WHERE (p.state = 'ACTIVE' AND l.profile_id IS NULL)
                   OR (p.state <> 'ACTIVE' AND l.profile_id IS NOT NULL)
                LIMIT 1
                """);
    }

    private SidecarState inspectSidecars() {
        Path fileName = databasePath.getFileName();
        Path parent = databasePath.getParent();
        if (fileName == null || parent == null) {
            return SidecarState.NONE;
        }
        SidecarState result = SidecarState.NONE;
        for (String suffix : java.util.List.of("-wal", "-shm")) {
            PathState state = inspectPath(parent.resolve(
                    fileName + suffix));
            if (state == PathState.UNKNOWN) {
                return SidecarState.UNKNOWN;
            }
            if (state == PathState.NON_REGULAR) {
                return SidecarState.INVALID;
            }
            if (state == PathState.REGULAR) {
                result = SidecarState.PRESENT;
            }
        }
        return result;
    }

    private boolean hasRows(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private boolean singleValue(Connection connection, String sql, String value)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() && value.equalsIgnoreCase(rows.getString(1))
                    && !rows.next();
        }
    }

    private PathState inspectPath(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile()
                    ? PathState.REGULAR : PathState.NON_REGULAR;
        } catch (NoSuchFileException missing) {
            return PathState.MISSING;
        } catch (IOException | SecurityException failure) {
            return PathState.UNKNOWN;
        }
    }

    private Set<String> durableEvidence(Connection connection)
            throws SQLException {
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_profile LIMIT 1
                """)) {
            evidence.add("bonded-profile");
        }
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_lease LIMIT 1
                """)) {
            evidence.add("bonded-lease");
        }
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_cleanup
                WHERE cleanup_state = 'PENDING' LIMIT 1
                """)) {
            evidence.add("pending-cleanup");
        }
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_capture_source LIMIT 1
                """)) {
            evidence.add("capture-source");
        }
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_operation LIMIT 1
                """)) {
            evidence.add("bonded-operation");
        }
        if (exists(connection, """
                SELECT 1 FROM bonded_companion_extension_data LIMIT 1
                """)) {
            evidence.add("bonded-extension");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(evidence));
    }

    private boolean exists(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private static String diagnosticCode(Throwable failure) {
        return failure instanceof SQLException sql && sql.getMessage() != null
                && !sql.getMessage().isBlank()
                ? sql.getMessage() : "bonded-probe-failed";
    }

    private enum PathState {
        MISSING,
        REGULAR,
        NON_REGULAR,
        UNKNOWN
    }

    private enum SidecarState {
        NONE,
        PRESENT,
        INVALID,
        UNKNOWN
    }
}
