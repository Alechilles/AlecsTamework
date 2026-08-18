package com.alechilles.alecstamework.persistence.activation;

import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteSchemaV1ReadOnlyGateway;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceFeatureRegistry;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Performs a bounded, immutable probe of the generic replacement database.
 *
 * <p>The probe does not construct a schema manager, kernel, bootstrap, writer,
 * recovery worker, or projection runtime. Missing files are not opened. Every
 * existing-file failure is read-only so a race or malformed database cannot
 * fall through to fresh schema creation.</p>
 */
public final class TameworkPersistenceActivationProbe {
    private static final Set<String> REQUIRED_PROJECTION_CONSUMERS =
            canonicalProjectionConsumers();
    private static final List<String> DURABLE_TABLES = List.of(
            "companion_profile",
            "companion_lifecycle",
            "companion_alias",
            "companion_snapshot",
            "companion_tool_link",
            "profile_extension_data",
            "owner_population_reservation",
            "population_evidence_batch",
            "population_evidence_observation",
            "population_group_classification",
            "population_group_membership",
            "population_group_reservation",
            "command_family",
            "command_roster_membership",
            "timed_summon_lease",
            "provisioning_record",
            "coop_slot",
            "coop_residency",
            "refund_claim",
            "refund_claim_item",
            "import_manifest"
    );
    private final Path databasePath;
    private final List<Path> sourceDirectories;
    private final SqliteConnectionFactory connections;

    /** Creates a probe for one normalized replacement database path. */
    public TameworkPersistenceActivationProbe(@Nonnull Path databasePath) {
        this(databasePath, parentOf(databasePath));
    }

    /**
     * Creates a probe for a canonical layout. Source directories are inspected
     * only; no target or import workspace is created.
     */
    public TameworkPersistenceActivationProbe(
            @Nonnull TameworkDataPathLayout layout
    ) {
        this(
                PersistenceFiles.replacementDatabase(
                        Objects.requireNonNull(layout, "layout")
                                .targetDirectory()),
                layout.persistenceSourceDirectories()
        );
    }

    /** Creates a probe with explicit immutable historical source directories. */
    public TameworkPersistenceActivationProbe(
            @Nonnull Path databasePath,
            @Nonnull List<Path> sourceDirectories
    ) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();
        if (sourceDirectories == null
                || sourceDirectories.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Persistence source directories are required");
        }
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        sourceDirectories.forEach(path -> normalized.add(
                path.toAbsolutePath().normalize()));
        this.sourceDirectories = List.copyOf(normalized);
        this.connections = new SqliteConnectionFactory(this.databasePath);
    }

    /** Returns the probed path without exposing a mutable connection. */
    @Nonnull
    public Path databasePath() {
        return databasePath;
    }

    /** Returns the immutable, de-duplicated source search path. */
    @Nonnull
    public List<Path> sourceDirectories() {
        return sourceDirectories;
    }

    /** Classifies one target and its historical source candidates. */
    @Nonnull
    public TameworkPersistenceActivationEvidence probe() {
        SidecarState sidecars = inspectSidecars(databasePath);
        if (sidecars == SidecarState.UNKNOWN) {
            return readOnly(true, "persistence-sidecar-state-unavailable");
        }
        if (sidecars != SidecarState.NONE) {
            return readOnly(true, sidecars == SidecarState.INVALID
                    ? "persistence-sidecar-not-regular"
                    : "persistence-sidecar-present");
        }

        PathState target = inspectPath(databasePath);
        if (target == PathState.UNKNOWN) {
            return readOnly(true, "persistence-file-state-unavailable");
        }
        if (target == PathState.DIRECTORY
                || target == PathState.NON_REGULAR) {
            return readOnly(true, "persistence-file-not-regular");
        }
        if (target == PathState.REGULAR) {
            return probeTarget();
        }
        return probeHistoricalSources();
    }

    private TameworkPersistenceActivationEvidence probeTarget() {
        try (Connection connection =
                     connections.openImmutableSchemaProbeConnection()) {
            if (connection == null) {
                return readOnly(true, "persistence-file-raced-away");
            }
            SqliteSchemaV1ReadOnlyGateway.verify(connection);
            Set<String> evidence = durableEvidence(connection);
            if (evidence.isEmpty()) {
                return TameworkPersistenceActivationEvidence.dormant(
                        true, true);
            }
            return TameworkPersistenceActivationEvidence.active(evidence);
        } catch (Exception failure) {
            return readOnly(true, diagnosticCode(failure));
        }
    }

    private TameworkPersistenceActivationEvidence probeHistoricalSources() {
        TameworkPersistenceSourceActivationProbe.Disposition disposition =
                new TameworkPersistenceSourceActivationProbe(sourceDirectories)
                        .probe();
        return switch (disposition) {
            case IMPORTABLE -> TameworkPersistenceActivationEvidence.active(
                    Set.of("legacy-import-source"));
            case UNCERTAIN -> readOnly(true, "persistence-source-uncertain");
            case NONE -> TameworkPersistenceActivationEvidence.dormant(
                    false, false);
        };
    }

    private Set<String> durableEvidence(Connection connection)
            throws SQLException {
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        if (exists(connection, """
                SELECT 1 FROM operation_envelope
                WHERE phase NOT IN ('PUBLISHED', 'COMPENSATED', 'FAILED')
                LIMIT 1
                """)) {
            evidence.add("nonterminal-operation");
        }
        if (exists(connection, """
                SELECT 1 FROM persistence_quarantine
                WHERE state = 'ACTIVE' LIMIT 1
                """)) {
            evidence.add("active-quarantine");
        }
        if (exists(connection, """
                SELECT 1 FROM feature_circuit
                WHERE state IN ('OPEN', 'HALF_OPEN') LIMIT 1
                """)) {
            evidence.add("open-or-half-open-circuit");
        }
        if (projectionBehind(connection)) {
            evidence.add("unresolved-projection");
        }
        if (exists(connection, """
                SELECT 1 FROM persistence_incident
                WHERE state = 'OPEN' LIMIT 1
                """)) {
            evidence.add("open-incident");
        }
        for (String table : DURABLE_TABLES) {
            if (exists(connection,
                    "SELECT 1 FROM " + table + " LIMIT 1")) {
                evidence.add("durable-row:" + table);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(evidence));
    }

    private boolean projectionBehind(Connection connection) throws SQLException {
        long head;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT COALESCE(MAX(event_sequence), 0)
                     FROM projection_outbox
                     """)) {
            if (!rows.next()) {
                throw new SQLException("projection_outbox_head_missing");
            }
            head = rows.getLong(1);
        }
        Map<String, Long> checkpoints = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT consumer_id, acknowledged_sequence
                     FROM projection_checkpoint
                     """)) {
            while (rows.next()) {
                String consumer = rows.getString(1);
                long sequence = rows.getLong(2);
                if (!REQUIRED_PROJECTION_CONSUMERS.contains(consumer)
                        || checkpoints.put(consumer, sequence) != null
                        || sequence > head) {
                    throw new SQLException(
                            "projection_checkpoint_registry_mismatch");
                }
            }
        }
        if (head == 0) {
            return false;
        }
        for (String consumer : REQUIRED_PROJECTION_CONSUMERS) {
            if (checkpoints.getOrDefault(consumer, 0L) < head) {
                return true;
            }
        }
        return false;
    }

    private boolean exists(Connection connection, String sql)
            throws SQLException {
        return hasRows(connection, sql);
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

    private SidecarState inspectSidecars(Path database) {
        Path fileName = database.getFileName();
        Path parent = database.getParent();
        if (fileName == null || parent == null) {
            return SidecarState.NONE;
        }
        SidecarState result = SidecarState.NONE;
        for (String suffix : List.of("-wal", "-shm")) {
            PathState state = inspectPath(parent.resolve(fileName + suffix));
            if (state == PathState.UNKNOWN) {
                return SidecarState.UNKNOWN;
            }
            if (state == PathState.DIRECTORY
                    || state == PathState.NON_REGULAR) {
                return SidecarState.INVALID;
            }
            if (state == PathState.REGULAR) {
                result = SidecarState.PRESENT;
            }
        }
        return result;
    }

    private PathState inspectPath(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return PathState.DIRECTORY;
            }
            return attributes.isRegularFile()
                    ? PathState.REGULAR : PathState.NON_REGULAR;
        } catch (NoSuchFileException missing) {
            return PathState.MISSING;
        } catch (IOException | SecurityException failure) {
            return PathState.UNKNOWN;
        }
    }

    private static List<Path> parentOf(Path databasePath) {
        Path normalized = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();
        return normalized.getParent() == null
                ? List.of() : List.of(normalized.getParent());
    }

    private static Set<String> canonicalProjectionConsumers() {
        Set<String> consumers = new LinkedHashSet<>();
        PublicPersistenceFeatureRegistry.create().descriptors().forEach(
                descriptor -> descriptor.projectionConsumers().forEach(
                        consumer -> consumers.add(consumer.value())));
        return Collections.unmodifiableSet(consumers);
    }

    private TameworkPersistenceActivationEvidence readOnly(
            boolean present, String code
    ) {
        return TameworkPersistenceActivationEvidence.readOnly(present, code);
    }

    private static String diagnosticCode(Throwable failure) {
        return failure instanceof SQLException sql && sql.getMessage() != null
                && !sql.getMessage().isBlank()
                ? sql.getMessage() : "persistence-probe-failed";
    }

    private enum PathState {
        MISSING,
        DIRECTORY,
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
