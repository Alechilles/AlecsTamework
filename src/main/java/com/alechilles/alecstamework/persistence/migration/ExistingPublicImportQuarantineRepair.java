package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Repairs old public-import flag quarantines before the replacement runtime opens.
 *
 * <p>The repair keeps the current target. It reads the preserved public source only
 * when its fingerprint matches the committed import manifest. Each profile must
 * still match its original imported identity, snapshots, lifecycle, and quarantine.
 * Changed or ambiguous profiles remain quarantined.</p>
 */
final class ExistingPublicImportQuarantineRepair {
    private final PublicPersistenceSourceDiscovery sources =
            new PublicPersistenceSourceDiscovery();
    private final SqliteReadOnlySnapshotter snapshotter =
            new SqliteReadOnlySnapshotter();
    private final LegacySourceClassifier classifier =
            new LegacySourceClassifier();
    private final LegacyPublicDataReader reader = new LegacyPublicDataReader();
    private final PublicImportPlanner planner = new PublicImportPlanner();
    private final ExistingPublicImportQuarantineTargetRepair targetRepair;

    ExistingPublicImportQuarantineRepair(@Nonnull LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Repair clock is required");
        }
        targetRepair = new ExistingPublicImportQuarantineTargetRepair(clock);
    }

    int repair(
            @Nonnull Path target,
            @Nonnull List<Path> candidateDirectories
    ) {
        if (target == null || candidateDirectories == null) {
            throw new IllegalArgumentException(
                    "Repair target and source directories are required"
            );
        }
        RepairManifest manifest = repairManifest(target);
        if (manifest == null) {
            return 0;
        }
        PublicPersistenceSourceDiscovery.Result discovered =
                sources.discover(candidateDirectories);
        if (!(discovered instanceof
                PublicPersistenceSourceDiscovery.Selected selected)
                || selected.format()
                != PublicPersistenceSourceDiscovery.Format.SQLITE) {
            return 0;
        }
        PublicImportPlan plan = prepare(selected.source(), manifest);
        if (plan == null) {
            return 0;
        }
        try {
            return targetRepair.apply(
                    target,
                    manifest.sourceSha256(),
                    manifest.sourceSchemaVersion(),
                    manifest.completedAtMs(),
                    plan
            );
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "existing_import_quarantine_repair_failed",
                    failure
            );
        }
    }

    @Nullable
    private RepairManifest repairManifest(Path target) {
        try (Connection connection =
                     new SqliteConnectionFactory(target).openReadConnection()) {
            if (!hasRepairCandidate(connection)) {
                return null;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT source_sha256, source_schema_version,
                                completed_at_ms
                         FROM import_manifest
                         ORDER BY import_id
                         """)) {
                if (!rows.next()) {
                    return null;
                }
                RepairManifest manifest = new RepairManifest(
                        rows.getString("source_sha256"),
                        rows.getInt("source_schema_version"),
                        rows.getLong("completed_at_ms")
                );
                return rows.next() ? null : manifest;
            }
        } catch (Exception ignored) {
            // Normal schema validation reports an invalid existing target.
            return null;
        }
    }

    private boolean hasRepairCandidate(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM persistence_quarantine quarantine
                     JOIN companion_lifecycle lifecycle
                       ON lifecycle.profile_id = quarantine.scope_key
                     WHERE quarantine.scope_type = 'PROFILE'
                       AND quarantine.state = 'ACTIVE'
                       AND quarantine.reason_code =
                           'MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS'
                       AND lifecycle.lifecycle_state = 'UNRESOLVED'
                       AND lifecycle.location_kind = 'UNRESOLVED'
                       AND lifecycle.revision = 0
                       AND lifecycle.active_operation_id IS NULL
                       AND lifecycle.quarantine_incident_id =
                           quarantine.incident_id
                     """)) {
            return row.next() && row.getLong(1) != 0;
        }
    }

    @Nullable
    private PublicImportPlan prepare(
            Path source,
            RepairManifest manifest
    ) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory(
                    "tamework-import-quarantine-repair-"
            );
            try (SqliteReadOnlySnapshotter.Snapshot snapshot =
                         snapshotter.create(source, workspace)) {
                if (!manifest.sourceSha256().equals(
                        snapshot.fingerprint().snapshotSha256()
                )) {
                    return null;
                }
                LegacySourceClassification classification =
                        classifier.classifySnapshot(snapshot);
                if (!classification.importablePublicSource()
                        || classification.schemaVersion()
                        != manifest.sourceSchemaVersion()) {
                    return null;
                }
                try (Connection connection = new SqliteConnectionFactory(
                        snapshot.path()
                ).openReadConnection()) {
                    LegacyPublicData sourceData = reader.read(
                            connection,
                            classification.schemaVersion()
                    );
                    return planner.plan(
                            sourceData,
                            snapshot.fingerprint(),
                            manifest.completedAtMs()
                    );
                }
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private void deleteWorkspace(@Nullable Path workspace) {
        if (workspace == null || Files.notExists(workspace)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // The workspace is temporary and independent of both databases.
        }
    }

    private record RepairManifest(
            String sourceSha256,
            int sourceSchemaVersion,
            long completedAtMs
    ) {
    }
}
