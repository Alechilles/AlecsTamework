package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionCleanupRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionExtensionDataRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionLeaseRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProfileRow;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Validates raw bonded rows against the complete domain mapping contract. */
final class BondedCompanionStoredRowValidator {
    private static final Gson GSON = new Gson();
    void verify(Connection connection) throws SQLException, InvalidRecordException {
        verifyProfiles(connection);
        verifyExtensions(connection);
        verifyVocabulary(connection, """
                SELECT live_npc_uuid, projection_state FROM bonded_companion_lease
                """, Set.of("PENDING", "LIVE", "REMOVE_PENDING"));
        verifyCleanup(connection);
        verifyOperations(connection);
    }

    private void verifyProfiles(Connection connection)
            throws SQLException, InvalidRecordException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, state, snapshot_json, policy_json
                     FROM bonded_companion_profile
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                requireState(rows.getString(2));
                requirePayload(rows.getString(3));
                requireStringMap(rows.getString(4));
            }
        }
    }

    private void verifyExtensions(Connection connection)
            throws SQLException, InvalidRecordException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT json_payload FROM bonded_companion_extension_data
                     """)) {
            while (rows.next()) requirePayload(rows.getString(1));
        }
    }

    private void verifyCleanup(Connection connection)
            throws SQLException, InvalidRecordException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, target_npc_uuid, world_key, target_kind,
                            cleanup_state, retained_until_ms
                     FROM bonded_companion_cleanup
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                requireUuid(rows.getString(2));
                if (rows.getString(3) == null || rows.getString(3).isBlank()
                        || !Set.of("SOURCE", "PROJECTION").contains(rows.getString(4))
                        || !Set.of("PENDING", "COMPLETED", "ABANDONED")
                        .contains(rows.getString(5)) || rows.getLong(6) == 0) {
                    throw invalid();
                }
            }
        }
    }

    private void verifyOperations(Connection connection)
            throws SQLException, InvalidRecordException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT owner_uuid, operation_type, operation_state,
                            request_hash, expires_at_ms, result_json
                     FROM bonded_companion_operation
                     """)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                if (!Set.of("CAPTURE", "PROVISION", "SUMMON", "STORE",
                                "REVIVE", "CLEANUP").contains(rows.getString(2))
                        || !Set.of("PENDING", "SUCCEEDED", "REJECTED", "FAILED")
                        .contains(rows.getString(3))
                        || !rows.getString(4).matches("[0-9a-f]{64}")
                        || rows.getLong(5) == 0) {
                    throw invalid();
                }
                requireOperationResult(rows.getString(3), rows.getString(6));
            }
        }
    }

    private void requirePayload(String json) throws InvalidRecordException {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("encoding") || !object.has("payload")) throw invalid();
            byte[] payload = switch (object.get("encoding").getAsString()) {
                case "base64" -> Base64.getDecoder().decode(
                        object.get("payload").getAsString());
                case "hex-utf8" -> HexFormat.of().parseHex(
                        object.get("payload").getAsString());
                default -> throw invalid();
            };
            if (payload.length == 0) throw invalid();
        } catch (InvalidRecordException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void requireStringMap(String json) throws InvalidRecordException {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            for (var entry : object.entrySet()) {
                if (entry.getValue().isJsonNull()
                        || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw invalid();
                }
            }
        } catch (InvalidRecordException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void requireOperationResult(String state, String json)
            throws InvalidRecordException {
        if ("PENDING".equals(state)) {
            if (json != null) throw invalid();
            return;
        }
        if (json == null) throw invalid();
        try {
            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
            if (!result.has("code") || !result.get("code").isJsonPrimitive()) {
                throw invalid();
            }
            BondedCompanionStoreResult.Code code =
                    BondedCompanionStoreResult.Code.valueOf(
                            result.get("code").getAsString());
            if (("SUCCEEDED".equals(state))
                    != (code == BondedCompanionStoreResult.Code.APPLIED)) {
                throw invalid();
            }
            requireOptionalString(result, "reason");
            requireStoredValue(result, code);
            requireOptionalJsonString(result, "value");
        } catch (InvalidRecordException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void requireStoredValue(
            JsonObject result,
            BondedCompanionStoreResult.Code code
    )
            throws InvalidRecordException {
        if (!result.has("valueType")
                || !result.get("valueType").isJsonPrimitive()) {
            throw invalid();
        }
        String type = result.get("valueType").getAsString();
        boolean hasValue = result.has("value") && !result.get("value").isJsonNull();
        if (!Set.of("PROFILE", "LEASE", "EXTENSION", "CLEANUP").contains(type)) {
            throw invalid();
        }
        if (code == BondedCompanionStoreResult.Code.APPLIED && !hasValue) {
            throw invalid();
        }
        if (!hasValue) return;
        try {
            String value = result.get("value").getAsString();
            switch (type) {
                case "PROFILE" -> verifyStoredProfile(value);
                case "LEASE" -> verifyStoredLease(value);
                case "EXTENSION" -> verifyStoredExtension(value);
                case "CLEANUP" -> verifyStoredCleanup(value);
                default -> throw invalid();
            }
        } catch (InvalidRecordException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void verifyStoredProfile(String json) throws InvalidRecordException {
        SqliteBondedCompanionProfileRow row = GSON.fromJson(
                json, SqliteBondedCompanionProfileRow.class);
        requirePayload(row.snapshotJson());
        requireStringMap(row.policyJson());
    }

    private void verifyStoredLease(String json) throws InvalidRecordException {
        SqliteBondedCompanionLeaseRow row = GSON.fromJson(
                json, SqliteBondedCompanionLeaseRow.class);
        if (!Set.of("PENDING", "LIVE", "REMOVE_PENDING")
                .contains(row.projectionState())) throw invalid();
    }

    private void verifyStoredExtension(String json) throws InvalidRecordException {
        SqliteBondedCompanionExtensionDataRow row = GSON.fromJson(
                json, SqliteBondedCompanionExtensionDataRow.class);
        requirePayload(row.jsonPayload());
    }

    private void verifyStoredCleanup(String json) throws InvalidRecordException {
        SqliteBondedCompanionCleanupRow row = GSON.fromJson(
                json, SqliteBondedCompanionCleanupRow.class);
        if (!Set.of("SOURCE", "PROJECTION").contains(row.targetKind())
                || !Set.of("PENDING", "COMPLETED", "ABANDONED")
                .contains(row.cleanupState())) throw invalid();
    }

    private void requireOptionalString(JsonObject object, String name)
            throws InvalidRecordException {
        if (object.has(name) && !object.get(name).isJsonNull()
                && !object.get(name).isJsonPrimitive()) throw invalid();
    }

    private void requireOptionalJsonString(JsonObject object, String name)
            throws InvalidRecordException {
        requireOptionalString(object, name);
        if (object.has(name) && !object.get(name).isJsonNull()) {
            try {
                JsonParser.parseString(object.get(name).getAsString());
            } catch (RuntimeException failure) {
                throw invalid();
            }
        }
    }

    private void verifyVocabulary(Connection connection, String sql,
                                  Set<String> vocabulary)
            throws SQLException, InvalidRecordException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                requireUuid(rows.getString(1));
                if (!vocabulary.contains(rows.getString(2))) throw invalid();
            }
        }
    }

    private void requireState(String value) throws InvalidRecordException {
        try {
            BondedCompanionState.valueOf(value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void requireUuid(String value) throws InvalidRecordException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw invalid();
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private InvalidRecordException invalid() {
        return new InvalidRecordException();
    }

    /** Signals a raw row that cannot be mapped into the bonded domain. */
    static final class InvalidRecordException extends Exception {
    }
}
