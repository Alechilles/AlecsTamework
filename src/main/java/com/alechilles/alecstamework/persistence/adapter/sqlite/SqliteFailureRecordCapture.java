package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFailureEvidence;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;

/**
 * Read-only, transaction-local failure evidence with explicit SQL projections.
 * May include uncommitted writes, never establishes a commit outcome, and carries
 * no connections. Names, coordinates, inventory and arbitrary payloads are excluded.
 */
final class SqliteFailureRecordCapture {
    private static final int MAX_ROWS = 12;
    private static final int MAX_OPERATIONS = 3;
    private static final int MAX_PROFILES = 4;
    private static final int MAX_PAYLOAD_CHARS = 262_144;
    private static final long MAX_NANOS = 150_000_000L;
    private static final Set<String> ENUM_COLUMNS = Set.of("phase", "lifecycle_state", "location_kind",
            "alias_state", "scope_type", "scope_kind", "state", "failure_kind");
    private static final Set<String> ENUM_VALUES = Set.of("PREPARED", "LIVE_APPLYING", "DURABLE",
            "PUBLISHED", "COMPENSATING", "COMPENSATED", "RETRYABLE", "FAILED", "UNKNOWN",
            "ACTIVE", "UNLOADED", "CAPTURED", "COOP", "DEAD_REVIVABLE", "LOST", "RELEASED",
            "UNRESOLVED", "ROSTER_STORED", "PROVISIONED_DORMANT", "LIVE_ENTITY", "CAPTURE_ITEM",
            "COOP_SLOT", "COMMAND_ROSTER", "PROVISIONING", "NONE", "LEASED", "CURRENT", "RETIRED",
            "OPERATION", "PROFILE", "OWNER", "TOOL", "COMMAND_FAMILY", "FEATURE", "GLOBAL",
            "PER_WORLD", "OPEN", "RESOLVED", "CLOSED", "HALF_OPEN", "BUSY", "TIMEOUT", "CORRUPT",
            "SCHEMA", "IO", "UNAVAILABLE", "DECODE");
    private static final Set<String> OPERATION_KINDS = Set.of("profile_extension_mutation", "companion_coop_release",
            "companion_dormant_transition", "command_roster_membership", "companion_capture", "companion_coop_capture",
            "companion_capture_release", "command_roster_transition", "coop_slot_registration", "timed_summon_lease_mutation",
            "timed_summon_transition", "saved_companion_talent", "companion_alias_rotation", "companion_profile_mutation",
            "owner_population_transition", "owner_population_reconciliation", "companion_provisioning", "companion_restoration",
            "paid_revival", "provisioning_activation", "companion_revive_ready", "population_group_assignment", "population_domain_admission", "breeding_litter");
    private static final Set<String> SOURCE_KINDS = Set.of("DEATH_COMPONENT", "OLD_AGE_DEATH", "DESTRUCTIVE_REMOVAL",
            "WORLD_DELETION", "EXPLICIT_RECALL_EXHAUSTED", "death", "lost", "capture", "coop",
            "timed_summon", "full_state_projection", "public_import_recovery");
    private static final String OPERATION_COLUMNS = "operation_id, operation_kind, phase, payload_version, "
            + "expected_lifecycle_revision, attempt_count, lease_owner, lease_until_ms, failure_kind, failure_code, "
            + "created_at_ms, updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms, "
            + "length(payload_json) AS payload_chars, CASE WHEN length(payload_json) <= "
            + MAX_PAYLOAD_CHARS + " THEN payload_json END AS payload_json";

    private SqliteFailureRecordCapture() { }

    static void attach(Connection connection, OperationId operationId, Throwable failure, String operation) {
        if (failure == null || hasEvidence(failure)) return;
        try {
            StorageFailureKind kind = SqliteFailureClassifier.classify(failure, operation).kind();
            failure.addSuppressed(new PersistenceFailureEvidence(capture(connection,
                    operationId == null ? null : operationId.toString(), kind)));
        } catch (Exception ignored) {
            // Collection must not replace the storage failure or change rollback/readback.
        }
    }

    static void attachOperation(Throwable failure, OperationEnvelope operation) {
        if (failure == null || operation == null || hasEvidence(failure)) return;
        try {
            Capture capture = new Capture();
            capture.root.addProperty("view", "recovery_envelope");
            capture.issue("related_records", "connection_unavailable");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation_id", operation.operationId().toString());
            row.put("operation_kind", operation.kind().value());
            row.put("phase", operation.phase().name());
            row.put("payload_version", operation.payloadVersion());
            row.put("expected_lifecycle_revision", operation.expectedLifecycleRevision() == null
                    ? null : operation.expectedLifecycleRevision().value());
            row.put("attempt_count", operation.attemptCount());
            row.put("payload_json", operation.payloadJson());
            capture.rows("operation_envelope").add(capture.sanitize(row));
            for (var participant : operation.participants()) {
                if (capture.rows("operation_participant").size() == MAX_ROWS) {
                    capture.truncated = true;
                    break;
                }
                capture.rows("operation_participant").add(capture.sanitize(Map.of(
                        "scope_type", participant.type().name(), "scope_key", participant.key())));
            }
            failure.addSuppressed(new PersistenceFailureEvidence(capture.finish()));
        } catch (Exception ignored) {
            // Recovery keeps its original result if diagnostic serialization fails.
        }
    }

    private static boolean hasEvidence(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        for (int i = 0; i < 32 && !pending.isEmpty(); i++) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof PersistenceFailureEvidence) return true;
            if (current.getCause() != null) pending.add(current.getCause());
            for (Throwable child : current.getSuppressed()) {
                if (pending.size() < 32) pending.add(child);
            }
        }
        return false;
    }

    static String capture(Connection connection, String operationId, StorageFailureKind kind) {
        Capture capture = new Capture();
        if (connection == null || kind == StorageFailureKind.BUSY || kind == StorageFailureKind.TIMEOUT
                || kind == StorageFailureKind.CORRUPT) {
            capture.issue("database", connection == null ? "connection_unavailable" : "unsafe_or_retryable_storage_failure");
            return capture.finish();
        }
        // Our connections are operation-scoped. Only this driver supports bounded VM work.
        if (!(connection instanceof SQLiteConnection)) {
            capture.issue("database", "unsupported_connection");
            return capture.finish();
        }
        int previousBusyTimeout = -1;
        boolean progressInstalled = false;
        try {
            // The factory configures PRAGMA directly, so the JDBC config getter can be stale.
            try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA busy_timeout")) {
                if (row.next()) previousBusyTimeout = row.getInt(1);
            }
            if (previousBusyTimeout < 0) throw new SQLException("diagnostic_timeout_unavailable");
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=1"); }
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler() {
                @Override protected int progress() { return capture.expired() ? 1 : 0; }
            });
            progressInstalled = true;
            capture.collect(connection, operationId);
        } catch (Exception failure) {
            capture.issue("database", "collection_failed");
        } finally {
            try {
                if (progressInstalled) ProgressHandler.clearHandler(connection);
            } catch (SQLException ignored) { capture.issue("database", "progress_cleanup_failed"); }
            try {
                if (previousBusyTimeout >= 0) {
                    try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=" + previousBusyTimeout); }
                }
            } catch (SQLException ignored) { capture.issue("database", "timeout_restore_failed"); }
        }
        return capture.finish();
    }

    private static final class Capture {
        private final String salt = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private final JsonObject root = new JsonObject();
        private final JsonObject tables = new JsonObject();
        private final JsonArray issues = new JsonArray();
        private final Set<String> profiles = new LinkedHashSet<>();
        private boolean truncated;

        Capture() {
            root.addProperty("version", 1);
            root.addProperty("view", "transaction_local_not_commit_evidence");
            root.addProperty("rowLimitPerSection", MAX_ROWS);
            root.addProperty("operationLimit", MAX_OPERATIONS);
            root.addProperty("profileLimit", MAX_PROFILES);
            root.addProperty("timeBudgetMs", MAX_NANOS / 1_000_000);
            root.add("tables", tables);
            root.add("issues", issues);
        }

        boolean expired() { return System.nanoTime() - started >= MAX_NANOS; }

        void collect(Connection connection, String operationId) {
            if (operationId != null) {
                root.addProperty("selection", "exact_operation");
                root.addProperty("operationId", token(operationId));
                query(connection, "operation_envelope", "SELECT " + OPERATION_COLUMNS
                        + " FROM operation_envelope WHERE operation_id = ?", operationId);
                collectOperation(connection, operationId);
            } else {
                root.addProperty("selection", "unfinished_operation_sample_not_causal_proof");
                var operations = query(connection, "operation_envelope", "SELECT " + OPERATION_COLUMNS
                        + " FROM operation_envelope WHERE phase IN ('PREPARED','LIVE_APPLYING','COMPENSATING',"
                        + "'RETRYABLE','FAILED','UNKNOWN','DURABLE') ORDER BY updated_at_ms DESC LIMIT "
                        + (MAX_OPERATIONS + 1));
                if (operations.size() > MAX_OPERATIONS) truncated = true;
                for (var row : operations.subList(0, Math.min(operations.size(), MAX_OPERATIONS))) {
                    collectOperation(connection, String.valueOf(row.get("operation_id")));
                }
            }
            for (String profile : profiles) collectProfile(connection, profile);
            query(connection, "schema_history", "SELECT version, lineage, schema_hash FROM schema_history");
            query(connection, "projection_checkpoint", "SELECT consumer_id, acknowledged_sequence, updated_at_ms FROM projection_checkpoint");
            query(connection, "feature_circuit", "SELECT feature_id, state, reason_code, failure_count, opened_at_ms, updated_at_ms FROM feature_circuit");
        }

        void collectOperation(Connection connection, String operationId) {
            var participants = query(connection, "operation_participant",
                    "SELECT operation_id, scope_type, scope_key FROM operation_participant WHERE operation_id = ?", operationId);
            for (var row : participants) {
                String type = String.valueOf(row.get("scope_type"));
                String key = String.valueOf(row.get("scope_key"));
                if ("PROFILE".equals(type)) addProfile(key);
                if ("PROFILE".equals(type) || "OPERATION".equals(type) || "COOP".equals(type)) {
                    for (var quarantine : query(connection, "persistence_quarantine",
                            "SELECT scope_type, scope_key, incident_id, state, reason_code, created_at_ms, released_at_ms "
                                    + "FROM persistence_quarantine WHERE scope_type = ? AND scope_key = ?", type, key)) {
                        query(connection, "persistence_incident", "SELECT incident_id, failure_kind, failure_code, state, "
                                + "created_at_ms, resolved_at_ms FROM persistence_incident WHERE incident_id = ?", quarantine.get("incident_id"));
                    }
                }
                if ("COOP".equals(type)) collectCoop(connection, key);
            }
            for (var row : query(connection, "active_operation_profiles", "SELECT profile_id FROM companion_lifecycle WHERE active_operation_id = ?", operationId)) {
                addProfile(String.valueOf(row.get("profile_id")));
            }
            query(connection, "projection_outbox", "SELECT event_sequence, operation_id, event_type, aggregate_id, aggregate_revision, "
                    + "payload_version FROM projection_outbox WHERE operation_id = ? ORDER BY event_sequence DESC", operationId);
            query(connection, "owner_population_reservation", "SELECT operation_id, profile_id, expected_lifecycle_revision, scope_kind, "
                    + "capacity_delta, snapshotted_limit FROM owner_population_reservation WHERE operation_id = ?", operationId);
            query(connection, "population_domain_reservation", "SELECT operation_id, profile_id, expected_lifecycle_revision, scope_kind, "
                    + "owned_delta, deployable_delta, weight, snapshotted_max_owned, snapshotted_max_deployable, policy_revision "
                    + "FROM population_domain_reservation WHERE operation_id = ?", operationId);
            query(connection, "refund_claim", "SELECT operation_id, reason_code, claimed_at_ms, delivered_at_ms "
                    + "FROM refund_claim WHERE operation_id = ?", operationId);
        }

        void addProfile(String profile) {
            if (profiles.size() < MAX_PROFILES) profiles.add(profile);
            else if (!profiles.contains(profile)) truncated = true;
        }

        void collectProfile(Connection connection, String profile) {
            query(connection, "companion_lifecycle", "SELECT profile_id, owner_uuid, lifecycle_state, location_kind, location_key, world_key, "
                    + "revision, active_operation_id, last_reconciled_generation, quarantine_incident_id FROM companion_lifecycle WHERE profile_id = ?", profile);
            query(connection, "companion_profile", "SELECT profile_id, role_id, metadata_revision, metadata_hash FROM companion_profile WHERE profile_id = ?", profile);
            query(connection, "companion_alias", "SELECT profile_id, npc_uuid, alias_generation, alias_state, lease_operation_id "
                    + "FROM companion_alias WHERE profile_id = ? ORDER BY alias_state = 'CURRENT' DESC, alias_generation DESC", profile);
            query(connection, "companion_snapshot", "SELECT profile_id, snapshot_id, snapshot_kind, payload_version, payload_hash, "
                    + "length(payload_json) AS payload_chars, source_lifecycle_revision, is_current "
                    + "FROM companion_snapshot WHERE profile_id = ? ORDER BY is_current DESC, created_at_ms DESC", profile);
            for (var residency : query(connection, "coop_residency", "SELECT profile_id, coop_key, housed_npc_uuid, snapshot_id FROM coop_residency WHERE profile_id = ?", profile)) {
                collectCoop(connection, String.valueOf(residency.get("coop_key")));
            }
            query(connection, "command_roster_membership", "SELECT profile_id, slot_id, owner_uuid, family_id, membership_revision, "
                    + "active_for_bulk_commands FROM command_roster_membership WHERE profile_id = ?", profile);
            query(connection, "timed_summon_lease", "SELECT profile_id, lease_revision, session_id, remaining_ms, cooldown_until_ms, "
                    + "config_revision, checkpointed_at_ms FROM timed_summon_lease WHERE profile_id = ?", profile);
            query(connection, "provisioning_record", "SELECT profile_id, policy_revision, creation_operation_id FROM provisioning_record WHERE profile_id = ?", profile);
            query(connection, "profile_extension_data", "SELECT profile_id, namespace, data_key, payload_version, revision, "
                    + "payload_hash, length(json_payload) AS payload_chars, deleted_at_ms FROM profile_extension_data WHERE profile_id = ?", profile);
            query(connection, "companion_output_claim", "SELECT profile_id, output_key, claim_revision, active_operation_id FROM companion_output_claim WHERE profile_id = ?", profile);
        }

        void collectCoop(Connection connection, String key) {
            query(connection, "coop_slot", "SELECT coop_key, residency_revision, active_operation_id, reserved_profile_id FROM coop_slot WHERE coop_key = ?", key);
        }

        List<Map<String, Object>> query(Connection connection, String section, String sql, Object... parameters) {
            if (expired()) { issue(section, "time_limit"); truncated = true; return List.of(); }
            JsonArray output = rows(section);
            int limit = "operation_envelope".equals(section) && "unfinished_operation_sample_not_causal_proof"
                    .equals(root.get("selection").getAsString()) ? MAX_OPERATIONS : MAX_ROWS;
            int available = limit - output.size();
            if (available <= 0) { truncated = true; return List.of(); }
            List<Map<String, Object>> result = new ArrayList<>();
            // All identifiers and projections are adapter-owned, never caller-supplied.
            String boundedSql = sql.contains(" LIMIT ") ? sql : sql + " LIMIT " + (available + 1);
            try (PreparedStatement statement = connection.prepareStatement(boundedSql)) {
                for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                try (ResultSet cursor = statement.executeQuery()) {
                    while (cursor.next()) {
                        if (result.size() >= available || expired()) { truncated = true; break; }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cursor.getMetaData().getColumnCount(); i++) {
                            row.put(cursor.getMetaData().getColumnLabel(i), cursor.getObject(i));
                        }
                        output.add(sanitize(row));
                        result.add(row);
                    }
                }
            } catch (SQLException failure) {
                issue(section, expired() ? "time_limit" : "query_failed_sql_" + failure.getErrorCode());
                truncated |= expired();
            } catch (RuntimeException failure) {
                issue(section, "decode_failed");
            }
            return result;
        }

        JsonArray rows(String section) {
            if (!tables.has(section)) tables.add(section, new JsonArray());
            return tables.getAsJsonArray(section);
        }

        JsonObject sanitize(Map<String, Object> row) {
            JsonObject result = new JsonObject();
            row.forEach((key, value) -> {
                if ("payload_json".equals(key)) result.add("payload_evidence", payload(value == null ? null : value.toString(), 0));
                else if (value == null) result.add(key, JsonNull.INSTANCE);
                else if (value instanceof Number number) result.addProperty(key, number);
                else if (ENUM_COLUMNS.contains(key) && ENUM_VALUES.contains(value.toString())) result.addProperty(key, value.toString());
                else if ("operation_kind".equals(key) && OPERATION_KINDS.contains(value.toString())) result.addProperty(key, value.toString());
                else if ("snapshot_kind".equals(key) && SOURCE_KINDS.contains(value.toString())) result.addProperty(key, value.toString());
                else if ("schema_hash".equals(key) && value.toString().matches("[a-f0-9]{64}")) result.addProperty(key, value.toString());
                else result.addProperty(key, token(value.toString()));
            });
            return result;
        }

        JsonObject payload(String json, int depth) {
            JsonObject result = new JsonObject();
            if (json == null || json.length() > MAX_PAYLOAD_CHARS || depth > 2) {
                result.addProperty("omitted", "payload_size_or_availability");
                return result;
            }
            try {
                JsonObject source = JsonParser.parseString(json).getAsJsonObject();
                for (String key : List.of("expectedLifecycleRevision", "expectedMetadataRevision", "sourceLifecycleRevision",
                        "sourceMetadataRevision", "policyRevision", "payloadVersion", "observedGeneration", "aliasGeneration",
                        "requestedAtMs", "observedAtMs", "createdAtMs")) {
                    JsonElement value = source.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) result.addProperty(key, value.getAsLong());
                }
                for (String key : List.of("profileId", "snapshotId", "sourceSnapshotId", "sourceAlias", "targetAlias", "ownerUuid",
                        "sourceWorldKey", "worldKey", "locationKey", "receiptKey", "payloadHash", "kind", "targetState", "sourceState", "snapshotKind")) {
                    JsonElement value = source.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        String text = value.getAsString();
                        boolean knownKind = Set.of("kind", "targetState", "sourceState", "snapshotKind").contains(key)
                                && (ENUM_VALUES.contains(text) || SOURCE_KINDS.contains(text));
                        result.addProperty(key, knownKind ? text : token(text));
                    }
                }
                if (source.has("current") && source.get("current").isJsonPrimitive()
                        && source.getAsJsonPrimitive("current").isBoolean()) result.add("current", source.get("current"));
                for (String key : List.of("source", "snapshot", "expected", "location")) {
                    if (depth < 2 && source.has(key) && source.get(key).isJsonObject()) result.add(key, payload(source.get(key).toString(), depth + 1));
                }
                result.addProperty("otherFieldsOmitted", true);
            } catch (RuntimeException failure) { result.addProperty("omitted", "invalid_payload"); }
            return result;
        }

        String token(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((salt + ":" + value).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }

        void issue(String section, String reason) {
            if (issues.size() >= 32) { truncated = true; return; }
            JsonObject issue = new JsonObject();
            issue.addProperty("section", section);
            issue.addProperty("reason", reason);
            issues.add(issue);
        }

        String finish() {
            root.addProperty("status", issues.isEmpty() ? (truncated ? "partial" : "complete")
                    : (tables.size() == 0 ? "unavailable" : "partial"));
            root.addProperty("truncated", truncated);
            root.addProperty("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            String json = root.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length <= PersistenceFailureEvidence.MAX_BYTES) return json;
            return "{\"version\":1,\"status\":\"partial\",\"reason\":\"size_limit\",\"truncated\":true}";
        }
    }
}
