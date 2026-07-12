package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservationPersistResult;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservationPersistence;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.parseUuid;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setInteger;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setUuid;

/**
 * Persists coalesced live load, unload, owner, and physical-location observations.
 */
public final class CompanionPopulationObservationRepository
        implements CompanionPopulationObservationPersistence {
    private final PersistenceWriteQueue writeQueue;

    public CompanionPopulationObservationRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    @Override
    public CompletableFuture<CompanionPopulationObservationPersistResult> persistAsync(
            @Nonnull CompanionPopulationObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        return writeQueue.submitTracked(
                "companion_population_live_observation",
                connection -> persistInTransaction(connection, observation),
                null
        ).completion().thenApply(outcome -> {
            CompanionPopulationObservationPersistResult value = outcome.value();
            if (outcome.isCommitted() && value != null) {
                return value;
            }
            return new CompanionPopulationObservationPersistResult(
                    CompanionPopulationObservationPersistResult.Status.FAILED,
                    observation.expectedRevision(),
                    outcome.failureReason() == null ? "observation-write-failed" : outcome.failureReason()
            );
        });
    }

    @Nonnull
    private CompanionPopulationObservationPersistResult persistInTransaction(
            @Nonnull Connection connection,
            @Nonnull CompanionPopulationObservation observation
    ) throws Exception {
        if (hasNonterminalOperation(connection, observation.profileId())) {
            return result(
                    CompanionPopulationObservationPersistResult.Status.PENDING_OPERATION,
                    observation.expectedRevision(),
                    "population-operation-pending"
            );
        }
        if (hasIdentityConflict(connection, observation.currentNpcUuid(), observation.profileId())) {
            return result(
                    CompanionPopulationObservationPersistResult.Status.IDENTITY_CONFLICT,
                    observation.expectedRevision(),
                    "npc-uuid-in-use"
            );
        }
        ExistingState existing = findState(connection, observation.profileId());
        if (existing == null) {
            insertProfile(connection, observation);
            insertState(connection, observation);
            setCurrentAlias(connection, observation.profileId(), observation.currentNpcUuid());
            return result(CompanionPopulationObservationPersistResult.Status.CREATED, 0L, null);
        }
        if (existing.revision() < 0L) {
            updateProfile(connection, observation);
            insertState(connection, observation);
            setCurrentAlias(connection, observation.profileId(), observation.currentNpcUuid());
            return result(CompanionPopulationObservationPersistResult.Status.CREATED, 0L, null);
        }
        if (sameObservation(existing, observation)) {
            setCurrentAlias(connection, observation.profileId(), observation.currentNpcUuid());
            return result(
                    CompanionPopulationObservationPersistResult.Status.IDEMPOTENT,
                    existing.revision(),
                    null
            );
        }
        if (existing.revision() != observation.expectedRevision()) {
            return result(
                    CompanionPopulationObservationPersistResult.Status.REVISION_CONFLICT,
                    existing.revision(),
                    "population-revision-changed"
            );
        }
        updateProfile(connection, observation);
        updateState(connection, observation);
        setCurrentAlias(connection, observation.profileId(), observation.currentNpcUuid());
        return result(
                CompanionPopulationObservationPersistResult.Status.COMMITTED,
                existing.revision() + 1L,
                null
        );
    }

    @Nullable
    private ExistingState findState(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.current_npc_uuid, p.owner_uuid, p.last_world_name,
                       s.ownership_world_name, s.lifecycle_state, s.physical_world_name,
                       s.physical_chunk_x, s.physical_chunk_z, s.revision
                FROM npc_profiles p
                LEFT JOIN companion_population_state s ON s.profile_id = p.profile_id
                WHERE p.profile_id = ?
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long revision = resultSet.getLong(9);
                boolean hasState = !resultSet.wasNull();
                return new ExistingState(
                        parseUuid(resultSet.getString(1)),
                        parseUuid(resultSet.getString(2)),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getString(6),
                        nullableInteger(resultSet, 7),
                        nullableInteger(resultSet, 8),
                        hasState ? revision : -1L
                );
            }
        }
    }

    private void insertProfile(@Nonnull Connection connection,
                               @Nonnull CompanionPopulationObservation observation) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, observation.profileId());
            setUuid(statement, 2, observation.currentNpcUuid());
            setUuid(statement, 3, observation.ownerUuid());
            setText(statement, 4, observation.ownershipWorldName());
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private void insertState(@Nonnull Connection connection,
                             @Nonnull CompanionPopulationObservation observation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """)) {
            long now = System.currentTimeMillis();
            statement.setString(1, observation.profileId());
            setText(statement, 2, observation.ownershipWorldName());
            statement.setString(3, observation.lifecycleState().name());
            setText(statement, 4, observation.physicalWorldName());
            setInteger(statement, 5, observation.physicalChunkX());
            setInteger(statement, 6, observation.physicalChunkZ());
            statement.setString(7, observation.source());
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.executeUpdate();
        }
    }

    private void updateProfile(@Nonnull Connection connection,
                               @Nonnull CompanionPopulationObservation observation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_profiles
                SET current_npc_uuid = ?, owner_uuid = ?, last_world_name = ?,
                    updated_at_ms = ?, last_active_at_ms = ?
                WHERE profile_id = ?
                """)) {
            long now = System.currentTimeMillis();
            setUuid(statement, 1, observation.currentNpcUuid());
            setUuid(statement, 2, observation.ownerUuid());
            setText(statement, 3, observation.ownershipWorldName());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.setString(6, observation.profileId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Observed population profile disappeared.");
            }
        }
    }

    private void updateState(@Nonnull Connection connection,
                             @Nonnull CompanionPopulationObservation observation) throws Exception {
        if (observation.expectedRevision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Observed population revision exhausted.");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_population_state
                SET ownership_world_name = ?, lifecycle_state = ?, physical_world_name = ?,
                    physical_chunk_x = ?, physical_chunk_z = ?, revision = revision + 1,
                    source = ?, updated_at_ms = ?
                WHERE profile_id = ? AND revision = ?
                """)) {
            setText(statement, 1, observation.ownershipWorldName());
            statement.setString(2, observation.lifecycleState().name());
            setText(statement, 3, observation.physicalWorldName());
            setInteger(statement, 4, observation.physicalChunkX());
            setInteger(statement, 5, observation.physicalChunkZ());
            statement.setString(6, observation.source());
            statement.setLong(7, System.currentTimeMillis());
            statement.setString(8, observation.profileId());
            statement.setLong(9, observation.expectedRevision());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Observed population revision changed during update.");
            }
        }
    }

    private void setCurrentAlias(@Nonnull Connection connection,
                                 @Nonnull String profileId,
                                 @Nonnull UUID npcUuid) throws Exception {
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE npc_uuid_aliases SET is_current = 0 WHERE profile_id = ?"
        )) {
            clear.setString(1, profileId);
            clear.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    is_current = 1,
                    mapped_at_ms = excluded.mapped_at_ms
                """)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private boolean hasNonterminalOperation(@Nonnull Connection connection,
                                            @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM companion_population_operations
                WHERE profile_id = ? AND state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                LIMIT 1
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean hasIdentityConflict(@Nonnull Connection connection,
                                        @Nonnull UUID npcUuid,
                                        @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                """)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, npcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (!profileId.equals(resultSet.getString(1))) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    private boolean sameObservation(@Nonnull ExistingState existing,
                                    @Nonnull CompanionPopulationObservation observation) {
        return existing.revision() >= 0L
                && Objects.equals(existing.currentNpcUuid(), observation.currentNpcUuid())
                && Objects.equals(existing.ownerUuid(), observation.ownerUuid())
                && Objects.equals(normalize(existing.lastWorldName()), observation.ownershipWorldName())
                && Objects.equals(normalize(existing.ownershipWorldName()), observation.ownershipWorldName())
                && observation.lifecycleState().name().equals(existing.lifecycleState())
                && Objects.equals(normalize(existing.physicalWorldName()), observation.physicalWorldName())
                && Objects.equals(existing.physicalChunkX(), observation.physicalChunkX())
                && Objects.equals(existing.physicalChunkZ(), observation.physicalChunkZ());
    }

    @Nullable
    private static Integer nullableInteger(@Nonnull ResultSet resultSet, int column) throws Exception {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static CompanionPopulationObservationPersistResult result(
            @Nonnull CompanionPopulationObservationPersistResult.Status status,
            long revision,
            @Nullable String reason
    ) {
        return new CompanionPopulationObservationPersistResult(status, revision, reason);
    }

    private record ExistingState(@Nullable UUID currentNpcUuid,
                                 @Nullable UUID ownerUuid,
                                 @Nullable String lastWorldName,
                                 @Nullable String ownershipWorldName,
                                 @Nullable String lifecycleState,
                                 @Nullable String physicalWorldName,
                                 @Nullable Integer physicalChunkX,
                                 @Nullable Integer physicalChunkZ,
                                 long revision) {
    }
}
