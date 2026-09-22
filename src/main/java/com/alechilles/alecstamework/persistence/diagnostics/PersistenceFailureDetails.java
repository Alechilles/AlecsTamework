package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFailureEvidence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Bounded cause traversal. Free-text exception messages never enter an upload. */
final class PersistenceFailureDetails {
    private static final int MAX_FAILURES = 12;
    private static final int MAX_TRAVERSAL = 32;
    private static final int MAX_FRAMES = 20;
    private static final int MAX_CAPTURES = 4;
    // Exact machine codes only. A message that merely looks like a token may contain player data.
    private static final Set<String> CODES = Set.of(
            "operation_prepared_detail_missing", "operation_not_found",
            "operation_compensation_detail_missing", "operation_compensated_evidence_missing",
            "operation_phase_or_lease_mismatch", "operation_revision_mismatch",
            "durable_operation_requires_outbox_event", "operation_prepare_conflict",
            "operation_prepare_rejected", "operation_prepare_not_found",
            "dormant_profile_lifecycle_missing", "dormant_transition_not_exact_source_profile",
            "dormant_population_domain_convergence_failed", "capture_prepare_not_exact_live_profile",
            "dormant_population_domain_pending_conflict", "owner_population_domain_claim_pending",
            "population_admission_containment_reservations_missing",
            "recovery_containment_incomplete", "recovery_containment_read_failed",
            "recovery_containment_not_committed", "recovery_pass_limit_reached",
            "operation_recovery_failed:dispatch_failed", "operation_recovery_failed:scan_failed",
            "operation_recovery_failed:unresolved", "operation_recovery_failed:pass_limit_reached",
            "replacement_schema_validation_failed", "canonical_startup_read_failed",
            "projection_startup_failed:canonical_read_failed", "projection_startup_failed:canonical_rebuild_failed",
            "projection_startup_failed:catch_up_failed",
            "replacement_schema_upgrade_verification_failed", "replacement_schema_objects_present",
            "replacement_schema_object_mismatch", "replacement_schema_definition_mismatch",
            "replacement_schema_history_mismatch", "replacement_schema_resource_missing",
            "replacement_schema_sql_missing", "replacement_schema_unverified",
            "replacement_schema_initialization_failed", "replacement_schema_migration_failed",
            "replacement_schema_verification_failed", "published_schema_verification_failed",
            "schema_upgrade_outcome_unknown", "schema_initialization_outcome_unknown",
            "schema_initialization_commit_unknown", "projection_sequence_missing",
            "projection_outbox_head_missing", "projection_checkpoint_registry_mismatch",
            "operation_publish_transition_failed", "durable_operation_evidence_absent",
            "operation_publish_phase_conflict", "operation_transition_phase_conflict",
            "persistence_engine_manifest_invalid", "persistence_engine_lease_failed",
            "persistence_engine_manifest_publish_failed", "persistence_engine_manifest_read_failed",
            "persistence_engine_lock_path_invalid", "persistence_engine_startup_owner_invalid",
            "persistence_engine_startup_transfer_invalid", "replacement_persistence_lineage_already_selected",
            "sqlite_busy", "sqlite_timeout", "sqlite_corrupt", "sqlite_schema", "sqlite_io",
            "sqlite_unavailable", "sqlite_unknown", "sqlite_commit_outcome_unknown",
            "unknown_commit_proven_absent", "unknown_commit_readback_failed",
            "read_executor_saturated", "read_executor_closed", "read_contract_returned_null"
    );

    private PersistenceFailureDetails() { }

    static JsonObject collect(Throwable failure) {
        return collect(failure, true);
    }

    private static JsonObject collect(Throwable failure, boolean includeRecords) {
        JsonObject result = new JsonObject();
        JsonArray errors = new JsonArray();
        JsonArray captures = new JsonArray();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Node> pending = new ArrayDeque<>();
        if (failure != null) pending.add(new Node(failure, -1, "root"));
        int examined = 0;
        boolean exceptionsTruncated = false;
        boolean capturesTruncated = false;
        boolean invalidEvidence = false;
        while (!pending.isEmpty() && examined++ < MAX_TRAVERSAL) {
            Node node = pending.removeFirst();
            Throwable current = node.failure();
            if (!visited.add(current)) continue;
            if (current instanceof PersistenceFailureEvidence evidence) {
                if (!includeRecords) continue;
                if (captures.size() < MAX_CAPTURES) {
                    try {
                        JsonObject capture = JsonParser.parseString(evidence.json()).getAsJsonObject();
                        if (validCapture(capture)) {
                            captures.add(capture);
                        } else {
                            invalidEvidence = true;
                        }
                    } catch (RuntimeException invalid) {
                        invalidEvidence = true;
                    }
                } else {
                    capturesTruncated = true;
                }
                continue;
            }
            int index = errors.size();
            boolean serializing = errors.size() < MAX_FAILURES;
            if (!serializing) {
                exceptionsTruncated = true;
                index = -1;
            }
            JsonObject error = serializing ? new JsonObject() : null;
            if (serializing) {
                error.addProperty("parent", node.parent());
                error.addProperty("relation", node.relation());
                error.addProperty("exceptionClass", current.getClass().getName());
                String code = safeCode(current.getMessage());
                if (code != null) {
                    error.addProperty("code", code);
                } else if (current.getMessage() != null) {
                    error.addProperty("messageOmitted", true);
                }
            }
            if (current instanceof SQLException sql) {
                if (serializing) error.addProperty("sqlErrorCode", sql.getErrorCode());
                String state = sql.getSQLState();
                if (serializing && state != null && state.matches("[A-Z0-9]{5}")) {
                    error.addProperty("sqlState", state);
                }
                if (sql.getNextException() != null && pending.size() < MAX_TRAVERSAL) {
                    pending.add(new Node(sql.getNextException(), index, "next_sql_exception"));
                }
            }
            if (serializing) {
                JsonArray frames = new JsonArray();
                StackTraceElement[] trace = current.getStackTrace();
                for (int i = 0; i < Math.min(trace.length, MAX_FRAMES); i++) {
                    StackTraceElement frame = trace[i];
                    frames.add(frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
                }
                error.add("stackFrames", frames);
                error.addProperty("stackTruncated", trace.length > MAX_FRAMES);
                errors.add(error);
            }
            if (current.getCause() != null && pending.size() < MAX_TRAVERSAL) {
                pending.add(new Node(current.getCause(), index, "cause"));
            }
            Throwable[] suppressed = current.getSuppressed();
            for (int i = 0; i < Math.min(suppressed.length, 8) && pending.size() < MAX_TRAVERSAL; i++) {
                pending.add(new Node(suppressed[i], index, "suppressed"));
            }
            exceptionsTruncated |= suppressed.length > 8;
        }
        result.add("exceptions", errors);
        result.addProperty("exceptionsTruncated", exceptionsTruncated || !pending.isEmpty());
        JsonObject records = new JsonObject();
        records.addProperty("status", captures.isEmpty() ? "unavailable" : "captured");
        if (captures.isEmpty()) {
            records.addProperty("reason", invalidEvidence ? "invalid_evidence" : "not_captured_at_failure_boundary");
        }
        records.addProperty("capturesTruncated", capturesTruncated);
        records.add("captures", captures);
        result.add("records", records);
        return result;
    }

    /** Distinguishes different underlying failures without incorporating record identities. */
    static String signature(Throwable failure) {
        JsonArray errors = collect(failure, false).getAsJsonArray("exceptions");
        StringBuilder signature = new StringBuilder();
        for (var value : errors) {
            JsonObject error = value.getAsJsonObject();
            signature.append(error.get("exceptionClass")).append('|');
            if (error.has("code")) signature.append(error.get("code"));
            if (error.has("sqlErrorCode")) signature.append(error.get("sqlErrorCode"));
            JsonArray frames = error.getAsJsonArray("stackFrames");
            if (!frames.isEmpty()) {
                String frame = frames.get(0).getAsString();
                signature.append(frame.substring(0, frame.lastIndexOf(':')));
            }
            signature.append(';');
        }
        return signature.toString();
    }

    static String rootCode(Throwable failure) {
        String code = null;
        for (var value : collect(failure, false).getAsJsonArray("exceptions")) {
            JsonObject error = value.getAsJsonObject();
            if (error.has("code")) code = error.get("code").getAsString();
            else if (error.has("sqlErrorCode")) code = "sql_error_" + error.get("sqlErrorCode").getAsInt();
        }
        return code;
    }

    static boolean hasCapturedRecords(Throwable failure) {
        for (JsonElement capture : collect(failure).getAsJsonObject("records").getAsJsonArray("captures")) {
            JsonObject object = capture.getAsJsonObject();
            if (object.has("tables")) {
                for (JsonElement rows : object.getAsJsonObject("tables").asMap().values()) {
                    if (!rows.getAsJsonArray().isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static String safeCode(String message) {
        if (message == null) return null;
        if (CODES.contains(message)) return message;
        if (message != null && message.matches(
                "persistence_engine_lock_unavailable:path=(active|legacy);scope=(same_process|external_process)")) {
            return message;
        }
        return null;
    }

    private static boolean validCapture(JsonObject capture) {
        if (!only(capture, Set.of("version", "status", "view", "rowLimitPerSection",
                "operationLimit", "profileLimit", "timeBudgetMs", "selection", "operationId",
                "tables", "issues", "truncated", "elapsedMs", "reason"))
                || !number(capture, "version", 1) || !stringIn(capture, "status",
                Set.of("complete", "partial", "unavailable")) || !booleanValue(capture, "truncated")) return false;
        if (capture.has("reason")) {
            return capture.size() == 4 && stringIn(capture, "reason", Set.of("size_limit"));
        }
        if (!stringIn(capture, "view", Set.of("transaction_local_not_commit_evidence", "recovery_envelope"))
                || !number(capture, "elapsedMs", -1) || !object(capture, "tables") || !array(capture, "issues")) return false;
        if (capture.has("selection") && !stringIn(capture, "selection",
                Set.of("exact_operation", "unfinished_operation_sample_not_causal_proof"))) return false;
        if (capture.has("operationId") && !token(capture.get("operationId"))) return false;
        if (capture.has("reason") && !stringIn(capture, "reason", Set.of("size_limit"))) return false;
        for (String key : List.of("rowLimitPerSection", "operationLimit", "profileLimit", "timeBudgetMs")) {
            if (capture.has(key) && !number(capture, key, 0)) return false;
        }
        for (Map.Entry<String, JsonElement> entry : capture.getAsJsonObject("tables").entrySet()) {
            if (!TABLE_COLUMNS.containsKey(entry.getKey()) || !entry.getValue().isJsonArray()) return false;
            for (JsonElement row : entry.getValue().getAsJsonArray()) {
                if (!validRow(entry.getKey(), row)) return false;
            }
        }
        for (JsonElement issue : capture.getAsJsonArray("issues")) {
            if (!issue.isJsonObject() || !only(issue.getAsJsonObject(), Set.of("section", "reason"))
                    || !stringIn(issue.getAsJsonObject(), "section", ISSUE_SECTIONS)
                    || !safeIssueReason(issue.getAsJsonObject().get("reason"))) return false;
        }
        return true;
    }

    private static boolean validRow(String table, JsonElement candidate) {
        if (!candidate.isJsonObject()) return false;
        JsonObject row = candidate.getAsJsonObject();
        if (!only(row, TABLE_COLUMNS.get(table))) return false;
        for (Map.Entry<String, JsonElement> field : row.entrySet()) {
            if (field.getValue().isJsonNull() || field.getValue().isJsonPrimitive()
                    && (field.getValue().getAsJsonPrimitive().isNumber()
                    || field.getValue().getAsJsonPrimitive().isBoolean())) continue;
            if ("payload_evidence".equals(field.getKey()) && validPayload(field.getValue(), 0)) continue;
            if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isString()) return false;
            String value = field.getValue().getAsString();
            if ("operation_kind".equals(field.getKey()) && OPERATION_KINDS.contains(value)) continue;
            if ("snapshot_kind".equals(field.getKey()) && PAYLOAD_ENUMS.contains(value)) continue;
            if ("schema_hash".equals(field.getKey()) && value.matches("[a-f0-9]{64}")) continue;
            if (ENUM_COLUMNS.contains(field.getKey()) && ENUM_VALUES.contains(value)) continue;
            if (!value.matches("[a-f0-9]{24}")) return false;
        }
        return true;
    }

    private static boolean validPayload(JsonElement candidate, int depth) {
        if (!candidate.isJsonObject() || depth > 2) return false;
        JsonObject value = candidate.getAsJsonObject();
        if (!only(value, Set.of("expectedLifecycleRevision", "expectedMetadataRevision",
                "sourceLifecycleRevision", "sourceMetadataRevision", "policyRevision", "payloadVersion",
                "observedGeneration", "aliasGeneration", "requestedAtMs", "observedAtMs", "createdAtMs",
                "profileId", "snapshotId", "sourceSnapshotId",
                "sourceAlias", "targetAlias", "ownerUuid", "sourceWorldKey", "worldKey", "locationKey",
                "receiptKey", "payloadHash", "kind", "targetState", "sourceState", "snapshotKind", "current", "source", "snapshot",
                "expected", "location", "otherFieldsOmitted", "omitted"))) return false;
        for (Map.Entry<String, JsonElement> field : value.entrySet()) {
            if (Set.of("source", "snapshot", "expected", "location").contains(field.getKey())) {
                if (!validPayload(field.getValue(), depth + 1)) return false;
            } else if ("otherFieldsOmitted".equals(field.getKey())) {
                if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsBoolean()) return false;
            } else if ("current".equals(field.getKey())) {
                if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isBoolean()) return false;
            } else if ("omitted".equals(field.getKey())) {
                if (!stringIn(value, field.getKey(), Set.of("payload_size_or_availability", "invalid_payload"))) return false;
            } else if (field.getValue().isJsonPrimitive() && field.getValue().getAsJsonPrimitive().isNumber()) {
                continue;
            } else if (PAYLOAD_ENUMS.contains(field.getValue().getAsString())) {
                continue;
            } else if (!token(field.getValue())) return false;
        }
        return true;
    }

    private static boolean only(JsonObject object, Set<String> allowed) {
        return object.keySet().stream().allMatch(allowed::contains);
    }

    private static boolean object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject();
    }

    private static boolean array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray();
    }

    private static boolean number(JsonObject object, String key, long minimum) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isNumber()
                && object.get(key).getAsLong() >= minimum;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isBoolean();
    }

    private static boolean stringIn(JsonObject object, String key, Set<String> allowed) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString()
                && allowed.contains(object.get(key).getAsString());
    }

    private static boolean token(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().matches("[a-f0-9]{24}");
    }

    private static boolean safeIssueReason(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
        String reason = value.getAsString();
        return Set.of("connection_unavailable", "unsafe_or_retryable_storage_failure", "unsupported_connection",
                "collection_failed", "progress_cleanup_failed", "timeout_restore_failed", "time_limit",
                "decode_failed").contains(reason) || reason.matches("query_failed_sql_-?[0-9]+");
    }

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
    private static final Set<String> PAYLOAD_ENUMS = Set.of("PREPARED", "LIVE_APPLYING", "DURABLE",
            "PUBLISHED", "COMPENSATING", "COMPENSATED", "RETRYABLE", "FAILED", "UNKNOWN",
            "ACTIVE", "UNLOADED", "CAPTURED", "COOP", "DEAD_REVIVABLE", "LOST", "RELEASED",
            "UNRESOLVED", "ROSTER_STORED", "PROVISIONED_DORMANT", "LIVE_ENTITY", "CAPTURE_ITEM",
            "COOP_SLOT", "COMMAND_ROSTER", "PROVISIONING", "NONE", "LEASED", "CURRENT", "RETIRED",
            "OPERATION", "PROFILE", "OWNER", "TOOL", "COMMAND_FAMILY", "FEATURE", "GLOBAL",
            "PER_WORLD", "OPEN", "RESOLVED", "CLOSED", "HALF_OPEN", "BUSY", "TIMEOUT", "CORRUPT",
            "SCHEMA", "IO", "UNAVAILABLE", "DECODE", "DEATH_COMPONENT", "OLD_AGE_DEATH",
            "DESTRUCTIVE_REMOVAL", "WORLD_DELETION", "EXPLICIT_RECALL_EXHAUSTED", "death", "lost", "capture", "coop",
            "timed_summon", "full_state_projection", "public_import_recovery");
    private static final Set<String> ISSUE_SECTIONS = Set.of("database", "related_records", "operation_envelope",
            "operation_participant", "persistence_quarantine", "persistence_incident", "active_operation_profiles",
            "projection_outbox", "owner_population_reservation", "population_domain_reservation", "refund_claim",
            "companion_lifecycle", "companion_profile", "companion_alias", "companion_snapshot", "coop_residency",
            "command_roster_membership", "timed_summon_lease", "provisioning_record", "profile_extension_data",
            "companion_output_claim", "coop_slot", "schema_history", "projection_checkpoint", "feature_circuit");
    private static final Map<String, Set<String>> TABLE_COLUMNS = Map.ofEntries(
            Map.entry("operation_envelope", Set.of("operation_id", "operation_kind", "phase", "payload_version", "expected_lifecycle_revision", "attempt_count", "lease_owner", "lease_until_ms", "failure_kind", "failure_code", "created_at_ms", "updated_at_ms", "durable_at_ms", "published_at_ms", "terminal_at_ms", "payload_chars", "payload_evidence")),
            Map.entry("operation_participant", Set.of("operation_id", "scope_type", "scope_key")),
            Map.entry("persistence_quarantine", Set.of("scope_type", "scope_key", "incident_id", "state", "reason_code", "created_at_ms", "released_at_ms")),
            Map.entry("persistence_incident", Set.of("incident_id", "failure_kind", "failure_code", "state", "created_at_ms", "resolved_at_ms")),
            Map.entry("active_operation_profiles", Set.of("profile_id")),
            Map.entry("projection_outbox", Set.of("event_sequence", "operation_id", "event_type", "aggregate_id", "aggregate_revision", "payload_version")),
            Map.entry("owner_population_reservation", Set.of("operation_id", "profile_id", "expected_lifecycle_revision", "scope_kind", "capacity_delta", "snapshotted_limit")),
            Map.entry("population_domain_reservation", Set.of("operation_id", "profile_id", "expected_lifecycle_revision", "scope_kind", "owned_delta", "deployable_delta", "weight", "snapshotted_max_owned", "snapshotted_max_deployable", "policy_revision")),
            Map.entry("refund_claim", Set.of("operation_id", "reason_code", "claimed_at_ms", "delivered_at_ms")),
            Map.entry("companion_lifecycle", Set.of("profile_id", "owner_uuid", "lifecycle_state", "location_kind", "location_key", "world_key", "revision", "active_operation_id", "last_reconciled_generation", "quarantine_incident_id")),
            Map.entry("companion_profile", Set.of("profile_id", "role_id", "metadata_revision", "metadata_hash")),
            Map.entry("companion_alias", Set.of("profile_id", "npc_uuid", "alias_generation", "alias_state", "lease_operation_id")),
            Map.entry("companion_snapshot", Set.of("profile_id", "snapshot_id", "snapshot_kind", "payload_version", "payload_hash", "payload_chars", "source_lifecycle_revision", "is_current")),
            Map.entry("coop_residency", Set.of("profile_id", "coop_key", "housed_npc_uuid", "snapshot_id")),
            Map.entry("command_roster_membership", Set.of("profile_id", "slot_id", "owner_uuid", "family_id", "membership_revision", "active_for_bulk_commands")),
            Map.entry("timed_summon_lease", Set.of("profile_id", "lease_revision", "session_id", "remaining_ms", "cooldown_until_ms", "config_revision", "checkpointed_at_ms")),
            Map.entry("provisioning_record", Set.of("profile_id", "policy_revision", "creation_operation_id")),
            Map.entry("profile_extension_data", Set.of("profile_id", "namespace", "data_key", "payload_version", "revision", "payload_hash", "payload_chars", "deleted_at_ms")),
            Map.entry("companion_output_claim", Set.of("profile_id", "output_key", "claim_revision", "active_operation_id")),
            Map.entry("coop_slot", Set.of("coop_key", "residency_revision", "active_operation_id", "reserved_profile_id")),
            Map.entry("schema_history", Set.of("version", "lineage", "schema_hash")),
            Map.entry("projection_checkpoint", Set.of("consumer_id", "acknowledged_sequence", "updated_at_ms")),
            Map.entry("feature_circuit", Set.of("feature_id", "state", "reason_code", "failure_count", "opened_at_ms", "updated_at_ms")));

    private record Node(Throwable failure, int parent, String relation) { }
}
