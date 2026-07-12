package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared JDBC binding and row mapping for focused population repositories.
 */
final class CompanionPopulationSqlSupport {
    private CompanionPopulationSqlSupport() {
    }

    static void bindState(@Nonnull PreparedStatement statement,
                          @Nonnull CompanionPopulationStateRecord state) throws Exception {
        statement.setString(1, state.profileId());
        setText(statement, 2, state.ownershipWorldName());
        statement.setString(3, state.lifecycleState());
        setText(statement, 4, state.physicalWorldName());
        setInteger(statement, 5, state.physicalChunkX());
        setInteger(statement, 6, state.physicalChunkZ());
        statement.setLong(7, state.revision());
        setText(statement, 8, state.source());
        statement.setLong(9, state.createdAtMs());
        statement.setLong(10, state.updatedAtMs());
    }

    @Nonnull
    static CompanionPopulationStateRecord readState(@Nonnull ResultSet resultSet) throws Exception {
        return new CompanionPopulationStateRecord(
                resultSet.getString("profile_id"),
                parseUuid(resultSet.getString("current_npc_uuid")),
                parseUuid(resultSet.getString("owner_uuid")),
                resultSet.getString("last_world_name"),
                resultSet.getString("ownership_world_name"),
                resultSet.getString("lifecycle_state"),
                resultSet.getString("physical_world_name"),
                getNullableInt(resultSet, "physical_chunk_x"),
                getNullableInt(resultSet, "physical_chunk_z"),
                resultSet.getLong("revision"),
                resultSet.getString("source"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms")
        );
    }

    @Nonnull
    static CompanionPopulationOperationRecord readOperation(@Nonnull ResultSet resultSet) throws Exception {
        return new CompanionPopulationOperationRecord(
                resultSet.getString("operation_id"),
                resultSet.getString("profile_id"),
                resultSet.getString("operation_type"),
                CompanionPopulationOperationRecord.State.valueOf(resultSet.getString("state")),
                resultSet.getLong("expected_revision"),
                resultSet.getString("old_state_json"),
                resultSet.getString("new_state_json"),
                resultSet.getString("target_context_json"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms"),
                resultSet.getLong("completed_at_ms"),
                resultSet.getString("last_error")
        );
    }

    @Nonnull
    static CompanionPopulationCoverageRecord readCoverage(@Nonnull ResultSet resultSet) throws Exception {
        return new CompanionPopulationCoverageRecord(
                resultSet.getString("coverage_key"),
                CompanionPopulationCoverageRecord.Dimension.valueOf(resultSet.getString("coverage_dimension")),
                resultSet.getString("world_or_save_id"),
                resultSet.getString("scan_generation"),
                CompanionPopulationCoverageRecord.State.valueOf(resultSet.getString("state")),
                resultSet.getString("cursor_json"),
                resultSet.getLong("scanned_count"),
                resultSet.getLong("estimated_total"),
                resultSet.getLong("started_at_ms"),
                resultSet.getLong("updated_at_ms"),
                resultSet.getLong("completed_at_ms"),
                resultSet.getString("last_error")
        );
    }

    @Nullable
    static UUID parseUuid(@Nullable String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @Nullable
    static Integer getNullableInt(@Nonnull ResultSet resultSet, @Nonnull String column) throws Exception {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    static void setUuid(@Nonnull PreparedStatement statement,
                        int index,
                        @Nullable UUID value) throws Exception {
        setText(statement, index, value == null ? null : value.toString());
    }

    static void setText(@Nonnull PreparedStatement statement,
                        int index,
                        @Nullable String value) throws Exception {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    static void setInteger(@Nonnull PreparedStatement statement,
                           int index,
                           @Nullable Integer value) throws Exception {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
