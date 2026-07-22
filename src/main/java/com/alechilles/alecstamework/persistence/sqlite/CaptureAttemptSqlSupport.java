package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps capture-attempt rows and nullable formula evidence without owning state transitions. */
final class CaptureAttemptSqlSupport {
    static final String SELECT_COLUMNS = """
            SELECT attempt_id, caller_namespace, idempotency_key, actor_uuid, target_npc_uuid,
                   profile_id, expected_profile_revision, source_item_id, source_role_id,
                   source_context_json, spawner_config_id, spawner_config_revision,
                   target_policy_config_id, target_policy_config_revision, target_policy_bypassed,
                   source_consumption, success_disposition, command_family_id,
                   required_command_config_id, require_command_access_item,
                   state, population_operation_id, capture_operation_id, power, minimum_power,
                   current_health, maximum_health, missing_health_fraction, condition_bonus,
                   effective_chance, entropy_sample, guaranteed, outcome, reason_code,
                   failure_cooldown_until_ms, event_emitted_at_ms, recovery_status, expires_at_ms,
                   resolved_at_ms, created_at_ms, updated_at_ms, completed_at_ms, last_error,
                   source_spend_state, source_spend_before_fingerprint,
                   source_spend_after_fingerprint, source_spend_receipted_at_ms,
                   source_spend_at_ms
            FROM capture_attempts
            """;

    private CaptureAttemptSqlSupport() {
    }

    @Nonnull
    static CaptureAttemptRecord read(@Nonnull ResultSet result) throws Exception {
        CaptureAttemptRecord.Identity identity = new CaptureAttemptRecord.Identity(
                result.getString("attempt_id"),
                result.getString("caller_namespace"),
                result.getString("idempotency_key"),
                UUID.fromString(result.getString("actor_uuid")),
                UUID.fromString(result.getString("target_npc_uuid")),
                result.getString("profile_id"),
                nullableLong(result, "expected_profile_revision"),
                result.getString("source_item_id"),
                result.getString("source_role_id"),
                result.getString("source_context_json")
        );
        CaptureAttemptRecord.ConfigEvidence config = new CaptureAttemptRecord.ConfigEvidence(
                result.getString("spawner_config_id"),
                result.getLong("spawner_config_revision"),
                result.getString("target_policy_config_id"),
                nullableLong(result, "target_policy_config_revision"),
                result.getInt("target_policy_bypassed") != 0,
                result.getInt("guaranteed") != 0,
                com.alechilles.alecstamework.api.CaptureSourceConsumption.valueOf(
                        result.getString("source_consumption")),
                com.alechilles.alecstamework.api.CaptureSuccessDisposition.valueOf(
                        result.getString("success_disposition")),
                result.getString("command_family_id"),
                result.getString("required_command_config_id"),
                result.getInt("require_command_access_item") != 0
        );
        Double power = nullableDouble(result, "power");
        CaptureAttemptRecord.Resolution resolution = power == null ? null : new CaptureAttemptRecord.Resolution(
                power,
                result.getDouble("minimum_power"),
                result.getDouble("current_health"),
                result.getDouble("maximum_health"),
                result.getDouble("missing_health_fraction"),
                result.getDouble("condition_bonus"),
                result.getDouble("effective_chance"),
                nullableDouble(result, "entropy_sample"),
                result.getString("outcome"),
                result.getString("reason_code"),
                result.getLong("failure_cooldown_until_ms"),
                result.getLong("resolved_at_ms")
        );
        return new CaptureAttemptRecord(
                identity,
                config,
                CaptureAttemptRecord.State.valueOf(result.getString("state")),
                resolution,
                result.getString("population_operation_id"),
                result.getString("capture_operation_id"),
                result.getLong("event_emitted_at_ms"),
                result.getString("recovery_status"),
                result.getLong("expires_at_ms"),
                result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"),
                result.getLong("completed_at_ms"),
                result.getString("last_error"),
                new CaptureAttemptRecord.SourceSpend(
                        CaptureAttemptRecord.SourceSpendState.valueOf(
                                result.getString("source_spend_state")),
                        result.getString("source_spend_before_fingerprint"),
                        result.getString("source_spend_after_fingerprint"),
                        result.getLong("source_spend_receipted_at_ms"),
                        result.getLong("source_spend_at_ms"))
        );
    }

    static void setText(PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    static void setLong(PreparedStatement statement, int index, @Nullable Long value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    static void setDouble(PreparedStatement statement, int index, @Nullable Double value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.DOUBLE);
        else statement.setDouble(index, value);
    }

    @Nullable
    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    @Nullable
    private static Double nullableDouble(ResultSet result, String column) throws Exception {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }
}
