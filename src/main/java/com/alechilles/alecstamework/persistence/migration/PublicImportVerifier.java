package com.alechilles.alecstamework.persistence.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/** Verifies logical equivalence and structural safety before an import target is published. */
final class PublicImportVerifier {
    void verify(
            @Nonnull Connection connection,
            @Nonnull PublicImportPlan plan,
            @Nonnull PublicImportManifest manifest
    ) throws Exception {
        if (connection == null || plan == null || manifest == null) {
            throw new IllegalArgumentException("Verification connection, plan, and manifest required");
        }
        verifyCounts(connection, plan);
        verifyManifest(connection, manifest);
        verifyHashes(connection, plan);
        verifyLifecycleCoverage(connection);
        verifyNoFabricatedWork(connection);
        verifyQuarantine(connection, plan);
        verifyIntegrity(connection);
    }

    private void verifyCounts(Connection connection, PublicImportPlan plan) throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("companion_profile", plan.profiles().size());
        expected.put("companion_alias", plan.aliases().size());
        expected.put("companion_tool_link", plan.toolLinks().size());
        expected.put("companion_snapshot", plan.snapshots().size());
        expected.put("profile_extension_data", plan.extensionData().size());
        expected.put("coop_slot", plan.coopSlots().size());
        expected.put("coop_residency", plan.coopResidencies().size());
        expected.put("companion_lifecycle", plan.lifecycles().size());
        expected.put("persistence_incident", plan.incidents().size());
        expected.put("persistence_quarantine", plan.incidents().size());
        expected.put("import_manifest", 1);
        for (Map.Entry<String, Integer> count : expected.entrySet()) {
            if (scalar(connection, "SELECT COUNT(*) FROM " + count.getKey()) != count.getValue()) {
                throw failure("IMPORT_COUNT_MISMATCH", count.getKey());
            }
        }
    }

    private void verifyManifest(Connection connection, PublicImportManifest manifest)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_sha256, source_schema_version, importer_version,
                       source_snapshot_name, counts_json, completed_at_ms
                FROM import_manifest WHERE import_id = ?
                """)) {
            statement.setString(1, manifest.importId());
            try (ResultSet row = statement.executeQuery()) {
                boolean matches = row.next()
                        && manifest.sourceSha256().equals(row.getString("source_sha256"))
                        && manifest.sourceSchemaVersion() == row.getInt("source_schema_version")
                        && manifest.importerVersion() == row.getInt("importer_version")
                        && manifest.sourceSnapshotName().equals(row.getString("source_snapshot_name"))
                        && manifest.countsJson().equals(row.getString("counts_json"))
                        && manifest.completedAtMs() == row.getLong("completed_at_ms")
                        && !row.next();
                if (!matches) {
                    throw failure("IMPORT_MANIFEST_MISMATCH", manifest.importId());
                }
            }
        }
    }

    private void verifyHashes(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement profile = connection.prepareStatement("""
                SELECT metadata_json, metadata_hash FROM companion_profile WHERE profile_id = ?
                """)) {
            for (PublicImportPlan.Profile expected : plan.profiles()) {
                profile.setString(1, expected.profileId());
                try (ResultSet row = profile.executeQuery()) {
                    if (!row.next()
                            || !java.util.Objects.equals(
                                    expected.metadataJson(), row.getString("metadata_json"))
                            || !java.util.Objects.equals(
                                    expected.metadataHash(), row.getString("metadata_hash"))) {
                        throw failure("PROFILE_METADATA_HASH_MISMATCH", expected.profileId());
                    }
                }
            }
        }
        try (PreparedStatement snapshot = connection.prepareStatement("""
                SELECT payload_hash, is_current FROM companion_snapshot WHERE snapshot_id = ?
                """)) {
            for (PublicImportPlan.Snapshot expected : plan.snapshots()) {
                snapshot.setString(1, expected.snapshotId());
                try (ResultSet row = snapshot.executeQuery()) {
                    if (!row.next()
                            || !expected.payloadHash().equals(row.getString("payload_hash"))
                            || (expected.current() ? 1 : 0) != row.getInt("is_current")) {
                        throw failure("SNAPSHOT_HASH_MISMATCH", expected.snapshotId());
                    }
                }
            }
        }
    }

    private void verifyLifecycleCoverage(Connection connection) throws Exception {
        long missing = scalar(connection, """
                SELECT COUNT(*) FROM companion_profile profile
                LEFT JOIN companion_lifecycle lifecycle ON lifecycle.profile_id = profile.profile_id
                WHERE lifecycle.profile_id IS NULL
                """);
        long extras = scalar(connection, """
                SELECT COUNT(*) FROM companion_lifecycle lifecycle
                LEFT JOIN companion_profile profile ON profile.profile_id = lifecycle.profile_id
                WHERE profile.profile_id IS NULL
                """);
        if (missing != 0 || extras != 0) {
            throw failure("LIFECYCLE_COVERAGE_MISMATCH", missing + ":" + extras);
        }
    }

    private void verifyNoFabricatedWork(Connection connection) throws Exception {
        for (String table : new String[]{
                "operation_envelope", "operation_participant",
                "projection_outbox", "projection_checkpoint"
        }) {
            if (scalar(connection, "SELECT COUNT(*) FROM " + table) != 0) {
                throw failure("IMPORT_FABRICATED_RUNTIME_WORK", table);
            }
        }
    }

    private void verifyQuarantine(Connection connection, PublicImportPlan plan) throws Exception {
        long disputedCurrentSnapshots = scalar(connection, """
                SELECT COUNT(*) FROM companion_snapshot snapshot
                JOIN persistence_quarantine quarantine
                  ON quarantine.scope_type = 'PROFILE'
                 AND quarantine.scope_key = snapshot.profile_id
                WHERE quarantine.state = 'ACTIVE' AND snapshot.is_current = 1
                """);
        long activeQuarantines = scalar(connection, """
                SELECT COUNT(*) FROM persistence_quarantine WHERE state = 'ACTIVE'
                """);
        if (disputedCurrentSnapshots != 0 || activeQuarantines != plan.incidents().size()) {
            throw failure("IMPORT_QUARANTINE_MISMATCH",
                    disputedCurrentSnapshots + ":" + activeQuarantines);
        }
    }

    private void verifyIntegrity(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("PRAGMA integrity_check")) {
            if (!row.next() || !"ok".equalsIgnoreCase(row.getString(1)) || row.next()) {
                throw failure("IMPORT_TARGET_INTEGRITY_FAILED", "integrity_check");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (violations.next()) {
                throw failure("IMPORT_TARGET_FOREIGN_KEY_FAILED", violations.getString(1));
            }
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw failure("IMPORT_VERIFICATION_QUERY_EMPTY", sql);
            }
            return row.getLong(1);
        }
    }

    private PublicImportException failure(String code, String evidence) {
        return new PublicImportException(code, code + ": " + evidence);
    }
}
