package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.bindState;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.parseUuid;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.readState;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setInteger;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setUuid;

/**
 * Owns durable population state, recovery journal, and reconciliation coverage SQL.
 */
public final class CompanionPopulationRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final CoopLedgerRepository coopLedgerRepository;
    private final CompanionPopulationJournalStore journalStore;

    public CompanionPopulationRepository(@Nonnull SqliteConnectionManager connectionManager,
                                         @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.coopLedgerRepository = new CoopLedgerRepository(connectionManager, writeQueue);
        this.journalStore = new CompanionPopulationJournalStore(connectionManager);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PopulationPersistenceTransition.Result> prepareAsync(
            @Nonnull PopulationPersistenceTransition.Prepare request
    ) {
        return writeQueue.submitTracked(
                "companion_population_prepare",
                connection -> prepareInTransaction(connection, request),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PopulationPersistenceTransition.Result> commitAsync(
            @Nonnull PopulationPersistenceTransition.Commit request
    ) {
        return writeQueue.submitTracked(
                "companion_population_commit",
                connection -> commitInTransaction(connection, request),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> advanceOperationAsync(
            @Nonnull String operationId,
            @Nonnull CompanionPopulationOperationRecord.State expected,
            @Nonnull CompanionPopulationOperationRecord.State next,
            @Nullable String error
    ) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid population operation transition: " + expected + " -> " + next);
        }
        return writeQueue.submitTracked(
                "companion_population_operation_advance",
                connection -> journalStore.advance(connection, operationId, expected, next, error),
                null
        );
    }

    /** Completes an exact source-bearing spawn after its world-thread CAS finalizer succeeds. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> completeSourceFinalizationAsync(
            @Nonnull String operationId
    ) {
        return writeQueue.submitTracked(
                "companion_population_source_finalize",
                connection -> journalStore.completeSourceFinalization(connection, operationId),
                null
        );
    }

    @Nonnull
    public List<CompanionPopulationStateRecord> loadAllStates() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT s.profile_id, p.current_npc_uuid, p.owner_uuid, p.last_world_name,
                            s.ownership_world_name, s.lifecycle_state, s.physical_world_name,
                            s.physical_chunk_x, s.physical_chunk_z, s.revision, s.source,
                            s.created_at_ms, s.updated_at_ms
                     FROM companion_population_state s
                     INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                     ORDER BY s.profile_id
                     """
             );
             ResultSet resultSet = statement.executeQuery()) {
            List<CompanionPopulationStateRecord> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(readState(resultSet));
            }
            return List.copyOf(rows);
        }
    }

    @Nonnull
    public List<CompanionPopulationOperationRecord> loadNonterminalOperations() throws Exception {
        return journalStore.loadNonterminalOperations();
    }

    /** Loads retained breeding rows, including COMMITTED rows used as restart birth evidence. */
    @Nonnull
    public List<CompanionPopulationOperationRecord> loadBreedingOperations() throws Exception {
        return journalStore.loadBreedingOperations();
    }

    @Nonnull
    private PopulationPersistenceTransition.Result prepareInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationPersistenceTransition.Prepare request
    ) throws Exception {
        CompanionPopulationOperationRecord operation = request.operation();
        CompanionPopulationStateRecord baseline = request.baselineState();
        CompanionPopulationJournalStore.OperationIdentity existingOperation =
                journalStore.find(connection, operation.operationId());
        if (existingOperation != null) {
            if (existingOperation.profileId().equals(operation.profileId())) {
                return result(PopulationPersistenceTransition.ResultStatus.IDEMPOTENT, baseline.revision(), "operation_exists");
            }
            return result(PopulationPersistenceTransition.ResultStatus.OPERATION_CONFLICT, -1L, "operation_id_in_use");
        }
        if (journalStore.hasNonterminal(connection, operation.profileId())) {
            return result(PopulationPersistenceTransition.ResultStatus.OPERATION_CONFLICT, -1L, "profile_operation_in_flight");
        }

        ExistingProfile existingProfile = findProfile(connection, operation.profileId());
        if (hasIdentityConflict(connection, baseline.currentNpcUuid(), operation.profileId())) {
            return result(PopulationPersistenceTransition.ResultStatus.IDENTITY_CONFLICT, -1L, "npc_uuid_in_use");
        }
        Long existingRevision = findRevision(connection, operation.profileId());
        if (existingRevision != null && existingRevision != operation.expectedRevision()) {
            return result(PopulationPersistenceTransition.ResultStatus.REVISION_CONFLICT, existingRevision, "profile_revision_changed");
        }
        if (existingProfile != null && !sameUuid(existingProfile.currentNpcUuid(), baseline.currentNpcUuid())) {
            return result(PopulationPersistenceTransition.ResultStatus.IDENTITY_CONFLICT, existingRevisionOrZero(existingRevision), "current_uuid_changed");
        }

        if (existingProfile == null) {
            insertProfile(connection, baseline);
        }
        if (existingRevision == null) {
            insertPopulationState(connection, baseline);
        }
        journalStore.insert(connection, operation);
        return result(PopulationPersistenceTransition.ResultStatus.PREPARED, baseline.revision(), null);
    }

    @Nonnull
    private PopulationPersistenceTransition.Result commitInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationPersistenceTransition.Commit request
    ) throws Exception {
        CompanionPopulationJournalStore.OperationIdentity operation =
                journalStore.find(connection, request.operationId());
        if (operation == null) {
            return result(PopulationPersistenceTransition.ResultStatus.NOT_FOUND, -1L, "operation_not_found");
        }
        if (!operation.profileId().equals(request.profileId())) {
            return result(PopulationPersistenceTransition.ResultStatus.OPERATION_CONFLICT, -1L, "operation_profile_mismatch");
        }
        if (operation.state() == CompanionPopulationOperationRecord.State.COMMITTED) {
            return result(PopulationPersistenceTransition.ResultStatus.IDEMPOTENT, request.expectedRevision() + 1L, "already_committed");
        }
        boolean sourceFinalizationRequired = CompanionSpawnSourceFinalizationContext.required(
                operation.targetContextJson()
        );
        if (operation.state() == CompanionPopulationOperationRecord.State.APPLIED
                && sourceFinalizationRequired) {
            return result(
                    PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING,
                    request.expectedRevision() + 1L,
                    "source_finalization_pending"
            );
        }
        if (operation.state() != CompanionPopulationOperationRecord.State.APPLYING
                && operation.state() != CompanionPopulationOperationRecord.State.APPLIED) {
            return result(PopulationPersistenceTransition.ResultStatus.INVALID_STATE, -1L, "operation_not_applying");
        }

        Long revision = findRevision(connection, request.profileId());
        if (revision == null) {
            return result(PopulationPersistenceTransition.ResultStatus.NOT_FOUND, -1L, "population_state_not_found");
        }
        if (revision != request.expectedRevision() || operation.expectedRevision() != request.expectedRevision()) {
            return result(PopulationPersistenceTransition.ResultStatus.REVISION_CONFLICT, revision, "profile_revision_changed");
        }
        if (hasIdentityConflict(connection, request.currentNpcUuid(), request.profileId())) {
            return result(PopulationPersistenceTransition.ResultStatus.IDENTITY_CONFLICT, revision, "npc_uuid_in_use");
        }

        updateProfile(connection, request);
        updatePopulationState(connection, request);
        CompanionPopulationCoopLedgerMutation.applyIfPresent(
                connection, coopLedgerRepository, operation.targetContextJson()
        );
        if (sourceFinalizationRequired) {
            journalStore.markApplied(connection, request.operationId());
            return result(
                    PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING,
                    revision + 1L,
                    "source_finalization_pending"
            );
        }
        journalStore.finalizeCommitted(connection, request.operationId());
        return result(PopulationPersistenceTransition.ResultStatus.COMMITTED, revision + 1L, null);
    }

    private void insertProfile(@Nonnull Connection connection,
                               @Nonnull CompanionPopulationStateRecord baseline) throws Exception {
        long createdAt = baseline.createdAtMs();
        long updatedAt = baseline.updatedAtMs();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, baseline.profileId());
            setUuid(statement, 2, baseline.currentNpcUuid());
            setUuid(statement, 3, baseline.ownerUuid());
            setText(statement, 4, baseline.profileLastWorldName());
            statement.setLong(5, createdAt);
            statement.setLong(6, updatedAt);
            statement.setLong(7, updatedAt);
            statement.executeUpdate();
        }
        setCurrentAlias(connection, baseline.profileId(), baseline.currentNpcUuid(), updatedAt);
    }

    private void insertPopulationState(@Nonnull Connection connection,
                                       @Nonnull CompanionPopulationStateRecord state) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            bindState(statement, state);
            statement.executeUpdate();
        }
    }

    private void updateProfile(@Nonnull Connection connection,
                               @Nonnull PopulationPersistenceTransition.Commit request) throws Exception {
        String ownerExpression = switch (request.ownerMutation().kind()) {
            case UNCHANGED -> "owner_uuid";
            case SET, CLEAR -> "?";
        };
        String ownerStateExpression = request.ownerMutation().kind() == ProfileOwnerMutation.Kind.UNCHANGED
                ? "state_json" : "CASE WHEN json_valid(state_json) THEN NULLIF(json_remove(state_json, '$.owner_name'), '{}') ELSE state_json END";
        String uuidExpression = request.currentNpcUuid() == null ? "current_npc_uuid" : "?";
        String sql = "UPDATE npc_profiles SET owner_uuid = " + ownerExpression
                + ", current_npc_uuid = " + uuidExpression
                + ", state_json = " + ownerStateExpression
                + ", last_world_name = ?, updated_at_ms = ?, last_active_at_ms = ? WHERE profile_id = ?";
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (request.ownerMutation().kind() != ProfileOwnerMutation.Kind.UNCHANGED) {
                setUuid(statement, index++, request.ownerMutation().ownerUuid());
            }
            if (request.currentNpcUuid() != null) {
                setUuid(statement, index++, request.currentNpcUuid());
            }
            setText(statement, index++, request.ownershipWorldName());
            statement.setLong(index++, now);
            statement.setLong(index++, now);
            statement.setString(index, request.profileId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population profile disappeared during commit.");
            }
        }
        if (request.currentNpcUuid() != null) {
            setCurrentAlias(connection, request.profileId(), request.currentNpcUuid(), now);
        }
    }
    private void updatePopulationState(@Nonnull Connection connection,
                                       @Nonnull PopulationPersistenceTransition.Commit request) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_state
                SET ownership_world_name = ?, lifecycle_state = ?, physical_world_name = ?,
                    physical_chunk_x = ?, physical_chunk_z = ?, revision = revision + 1,
                    source = ?, updated_at_ms = ?
                WHERE profile_id = ? AND revision = ?
                """
        )) {
            setText(statement, 1, request.ownershipWorldName());
            statement.setString(2, request.lifecycleState());
            setText(statement, 3, request.physicalWorldName());
            setInteger(statement, 4, request.physicalChunkX());
            setInteger(statement, 5, request.physicalChunkZ());
            setText(statement, 6, request.source());
            statement.setLong(7, System.currentTimeMillis());
            statement.setString(8, request.profileId());
            statement.setLong(9, request.expectedRevision());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population revision changed during commit.");
            }
        }
    }
    @Nullable
    private ExistingProfile findProfile(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? new ExistingProfile(parseUuid(resultSet.getString(1))) : null;
            }
        }
    }

    @Nullable
    private Long findRevision(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM companion_population_state WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private boolean hasIdentityConflict(@Nonnull Connection connection,
                                        @Nullable UUID npcUuid,
                                        @Nonnull String profileId) throws Exception {
        if (npcUuid == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                """
        )) {
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

    private void setCurrentAlias(@Nonnull Connection connection,
                                 @Nonnull String profileId,
                                 @Nullable UUID npcUuid,
                                 long mappedAtMs) throws Exception {
        if (npcUuid == null) {
            return;
        }
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE npc_uuid_aliases SET is_current = 0 WHERE profile_id = ?"
        )) {
            clear.setString(1, profileId);
            clear.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    is_current = 1,
                    mapped_at_ms = excluded.mapped_at_ms
                """
        )) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setLong(3, mappedAtMs);
            statement.executeUpdate();
        }
    }

    @Nonnull
    private static PopulationPersistenceTransition.Result result(
            @Nonnull PopulationPersistenceTransition.ResultStatus status,
            long revision,
            @Nullable String reason
    ) {
        return new PopulationPersistenceTransition.Result(status, revision, reason);
    }

    private static long existingRevisionOrZero(@Nullable Long revision) {
        return revision == null ? 0L : revision;
    }

    private static boolean sameUuid(@Nullable UUID left, @Nullable UUID right) {
        return left == null ? right == null : left.equals(right);
    }

    private record ExistingProfile(@Nullable UUID currentNpcUuid) {
    }

}
