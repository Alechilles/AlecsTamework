package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    private final CoopLifecycleOperationRepository coopLifecycleRepository;
    private final CompanionPopulationJournalStore journalStore;

    public CompanionPopulationRepository(@Nonnull SqliteConnectionManager connectionManager,
                                         @Nonnull PersistenceWriteQueue writeQueue) {
        this(
                connectionManager,
                writeQueue,
                new CoopLifecycleOperationRepository(
                        connectionManager,
                        writeQueue,
                        new ManagedCoopResidentRepository(connectionManager, writeQueue)
                )
        );
    }

    public CompanionPopulationRepository(
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull CoopLifecycleOperationRepository coopLifecycleRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.coopLifecycleRepository = coopLifecycleRepository;
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

    /** Prepares owner and group reservations in one rollback-safe SQLite transaction. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProvisionedDormantPreparationResult>
    prepareProvisionedDormantCompositeAsync(
            @Nonnull PopulationPersistenceTransition.Prepare ownerPrepare,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        Objects.requireNonNull(groupRepository, "groupRepository");
        List<PopulationGroupRepository.ReservationEvidence> frozenEvidence = List.copyOf(groupEvidence);
        return writeQueue.submitTracked(
                "provisioned_dormant_population_prepare",
                connection -> prepareProvisionedDormantCompositeInTransaction(
                        connection, ownerPrepare, groupRepository, groupOperation, frozenEvidence),
                null
        );
    }

    /**
     * Commits owner population state, null-NPC profile metadata, and group classification/journal
     * as one transaction.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProvisionedDormantCommitResult>
    commitProvisionedDormantCompositeAsync(
            @Nonnull PopulationPersistenceTransition.Commit ownerCommit,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull NpcProfileRepository.DormantProfileMutation profileMutation,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        Objects.requireNonNull(profileRepository, "profileRepository");
        Objects.requireNonNull(groupRepository, "groupRepository");
        AtomicReference<NpcProfileRepository.ProfileRecord> before = new AtomicReference<>();
        return writeQueue.submitTracked(
                "provisioned_dormant_population_commit",
                connection -> {
                    before.set(profileRepository.loadProfileByIdInTransaction(
                            connection, profileMutation.profileId()));
                    return commitProvisionedDormantCompositeInTransaction(
                            connection, ownerCommit, profileRepository, profileMutation,
                            groupRepository, groupOperationId, classification, nowMs);
                },
                result -> {
                    if (result != null && result.committed() && result.profileResult() != null) {
                        profileRepository.notifyProfileChanged(before.get(), result.profileResult().profile());
                    }
                }
        );
    }

    /** Commits a normal claim-bearing owner transition and its group classification atomically. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PopulationGroupCompositeCommitResult>
    commitPopulationGroupCompositeAsync(
            @Nonnull PopulationPersistenceTransition.Commit ownerCommit,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        Objects.requireNonNull(groupRepository, "groupRepository");
        return writeQueue.submitTracked(
                "population_group_composite_commit",
                connection -> commitPopulationGroupCompositeInTransaction(
                        connection, ownerCommit, groupRepository, groupOperationId,
                        classification, nowMs),
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

    /**
     * Closes an unapplied operation and, when coupled to a managed-coop release, restores the
     * resident and lifecycle claim in the same transaction.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> failOperationAsync(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull String error) {
        return writeQueue.submitTracked(
                "companion_population_operation_fail",
                connection -> failOperationInTransaction(connection, operation, error),
                null);
    }

    private boolean failOperationInTransaction(
            Connection connection,
            CompanionPopulationOperationRecord operation,
            String error) throws Exception {
        ManagedCoopPopulationMutationContext.ParsedMutation mutation =
                ManagedCoopPopulationMutationContext.parse(operation.targetContextJson());
        if (!journalStore.advance(
                connection, operation.operationId(), operation.state(),
                CompanionPopulationOperationRecord.State.FAILED, error)) {
            return false;
        }
        if (mutation == null || mutation.release() == null) {
            return true;
        }
        CoopLifecycleOperationRepository.MutationResult restored =
                coopLifecycleRepository.failPopulationReleaseBeforeProjectionInTransaction(
                        connection, mutation.release(), error, System.currentTimeMillis());
        if (restored == null || !restored.succeeded()
                || restored.operation() == null
                || restored.operation().state()
                != CoopLifecycleOperationRepository.OperationState.FAILED) {
            throw new IllegalStateException("Managed-coop release recovery failed: "
                    + (restored == null ? "result_missing" : restored.detail()));
        }
        return true;
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

        Savepoint commitBoundary = connection.setSavepoint();
        try {
            updateProfile(connection, request);
            updatePopulationState(connection, request);
            CompanionPopulationManagedCoopMutation.ApplyResult managedCoop =
                    CompanionPopulationManagedCoopMutation.applyIfPresent(
                            connection, coopLifecycleRepository, operation.targetContextJson()
                    );
            if (!managedCoop.applied()) {
                connection.rollback(commitBoundary);
                connection.releaseSavepoint(commitBoundary);
                return result(
                        PopulationPersistenceTransition.ResultStatus.MANAGED_COOP_CONFLICT,
                        revision,
                        managedCoop.detail()
                );
            }
            if (sourceFinalizationRequired) {
                journalStore.markApplied(connection, request.operationId());
                connection.releaseSavepoint(commitBoundary);
                return result(
                        PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING,
                        revision + 1L,
                        "source_finalization_pending"
                );
            }
            journalStore.finalizeCommitted(connection, request.operationId());
            connection.releaseSavepoint(commitBoundary);
            return result(PopulationPersistenceTransition.ResultStatus.COMMITTED, revision + 1L, null);
        } catch (Exception exception) {
            connection.rollback(commitBoundary);
            connection.releaseSavepoint(commitBoundary);
            throw exception;
        }
    }

    @Nonnull
    private ProvisionedDormantPreparationResult prepareProvisionedDormantCompositeInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationPersistenceTransition.Prepare ownerPrepare,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull List<PopulationGroupRepository.ReservationEvidence> groupEvidence) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner = prepareInTransaction(connection, ownerPrepare);
            if (owner.status() != PopulationPersistenceTransition.ResultStatus.PREPARED
                    && owner.status() != PopulationPersistenceTransition.ResultStatus.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return ProvisionedDormantPreparationResult.denied(owner, null,
                        owner.reason() == null ? "owner_population_prepare_denied" : owner.reason());
            }
            PopulationGroupRepository.ReservationResult groups =
                    groupRepository.reserveOperationInTransaction(
                            connection, groupOperation, groupEvidence);
            if (groups.status() != PopulationGroupRepository.Status.PREPARED
                    && groups.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return ProvisionedDormantPreparationResult.denied(owner, groups,
                        groups.reason() == null ? "population_group_prepare_denied" : groups.reason());
            }

            CompanionPopulationJournalStore.OperationIdentity ownerOperation =
                    journalStore.find(connection, ownerPrepare.operation().operationId());
            if (ownerOperation == null) {
                throw new IllegalStateException("Prepared owner operation disappeared.");
            }
            if (ownerOperation.state() == CompanionPopulationOperationRecord.State.PREPARED) {
                if (!journalStore.advance(connection, ownerPrepare.operation().operationId(),
                        CompanionPopulationOperationRecord.State.PREPARED,
                        CompanionPopulationOperationRecord.State.APPLYING, null)) {
                    throw new IllegalStateException(
                            "Owner operation changed during composite preparation.");
                }
            } else if (ownerOperation.state() != CompanionPopulationOperationRecord.State.APPLYING) {
                throw new IllegalStateException("Owner operation is not applying.");
            }

            PopulationGroupOperationRecord persistedGroup = groups.operation();
            if (persistedGroup == null) {
                throw new IllegalStateException("Prepared group operation disappeared.");
            }
            if (persistedGroup.state() == PopulationGroupOperationRecord.State.PREPARED) {
                PopulationGroupRepository.OperationResult applying =
                        groupRepository.advanceOperationInTransaction(
                                connection, persistedGroup.operationId(),
                                PopulationGroupOperationRecord.State.PREPARED,
                                PopulationGroupOperationRecord.State.APPLYING, null,
                                Math.max(ownerPrepare.operation().updatedAtMs(), groupOperation.updatedAtMs()));
                if (applying.status() != PopulationGroupRepository.Status.APPLYING
                        && applying.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                    throw new IllegalStateException(
                            "Group operation changed during composite preparation.");
                }
            } else if (persistedGroup.state() != PopulationGroupOperationRecord.State.APPLYING) {
                throw new IllegalStateException("Group operation is not applying.");
            }
            connection.releaseSavepoint(boundary);
            return new ProvisionedDormantPreparationResult(
                    ProvisionedDormantCompositeStatus.PREPARED, owner, groups, null);
        } catch (Exception failure) {
            connection.rollback(boundary);
            connection.releaseSavepoint(boundary);
            throw failure;
        }
    }

    @Nonnull
    private ProvisionedDormantCommitResult commitProvisionedDormantCompositeInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationPersistenceTransition.Commit ownerCommit,
            @Nonnull NpcProfileRepository profileRepository,
            @Nonnull NpcProfileRepository.DormantProfileMutation profileMutation,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner = commitInTransaction(connection, ownerCommit);
            if (owner.status() != PopulationPersistenceTransition.ResultStatus.COMMITTED
                    && owner.status() != PopulationPersistenceTransition.ResultStatus.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return ProvisionedDormantCommitResult.denied(owner, null, null,
                        owner.reason() == null ? "owner_population_commit_denied" : owner.reason());
            }
            NpcProfileRepository.DormantProfileResult profile =
                    profileRepository.applyDormantProfileInTransaction(connection, profileMutation);
            if (profile.status() != NpcProfileRepository.DormantProfileStatus.APPLIED
                    && profile.status() != NpcProfileRepository.DormantProfileStatus.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return ProvisionedDormantCommitResult.denied(owner, profile, null,
                        profile.reason() == null ? "dormant_profile_commit_denied" : profile.reason());
            }
            PopulationGroupRepository.OperationResult groups =
                    groupRepository.commitClassificationOperationInTransaction(
                            connection, groupOperationId, classification, nowMs);
            if (groups.status() != PopulationGroupRepository.Status.COMMITTED
                    && groups.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return ProvisionedDormantCommitResult.denied(owner, profile, groups,
                        groups.reason() == null ? "population_group_commit_denied" : groups.reason());
            }
            connection.releaseSavepoint(boundary);
            return new ProvisionedDormantCommitResult(
                    ProvisionedDormantCompositeStatus.COMMITTED, owner, profile, groups, null);
        } catch (Exception failure) {
            connection.rollback(boundary);
            connection.releaseSavepoint(boundary);
            throw failure;
        }
    }

    @Nonnull
    private PopulationGroupCompositeCommitResult commitPopulationGroupCompositeInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationPersistenceTransition.Commit ownerCommit,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner = commitInTransaction(connection, ownerCommit);
            if (owner.status() != PopulationPersistenceTransition.ResultStatus.COMMITTED
                    && owner.status() != PopulationPersistenceTransition.ResultStatus.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return PopulationGroupCompositeCommitResult.denied(owner, null,
                        owner.reason() == null ? "owner_population_commit_denied" : owner.reason());
            }
            PopulationGroupRepository.OperationResult groups =
                    groupRepository.commitClassificationOperationInTransaction(
                            connection, groupOperationId, classification, nowMs);
            if (groups.status() != PopulationGroupRepository.Status.COMMITTED
                    && groups.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                connection.rollback(boundary);
                connection.releaseSavepoint(boundary);
                return PopulationGroupCompositeCommitResult.denied(owner, groups,
                        groups.reason() == null ? "population_group_commit_denied" : groups.reason());
            }
            connection.releaseSavepoint(boundary);
            return new PopulationGroupCompositeCommitResult(
                    ProvisionedDormantCompositeStatus.COMMITTED, owner, groups, null);
        } catch (Exception failure) {
            connection.rollback(boundary);
            connection.releaseSavepoint(boundary);
            throw failure;
        }
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

    public enum ProvisionedDormantCompositeStatus { PREPARED, COMMITTED, DENIED }

    public record ProvisionedDormantPreparationResult(
            @Nonnull ProvisionedDormantCompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable PopulationGroupRepository.ReservationResult groupResult,
            @Nullable String reason) {
        public ProvisionedDormantPreparationResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static ProvisionedDormantPreparationResult denied(
                @Nullable PopulationPersistenceTransition.Result owner,
                @Nullable PopulationGroupRepository.ReservationResult groups,
                @Nonnull String reason) {
            return new ProvisionedDormantPreparationResult(
                    ProvisionedDormantCompositeStatus.DENIED, owner, groups, reason);
        }

        public boolean prepared() { return status == ProvisionedDormantCompositeStatus.PREPARED; }
    }

    public record ProvisionedDormantCommitResult(
            @Nonnull ProvisionedDormantCompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable NpcProfileRepository.DormantProfileResult profileResult,
            @Nullable PopulationGroupRepository.OperationResult groupResult,
            @Nullable String reason) {
        public ProvisionedDormantCommitResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static ProvisionedDormantCommitResult denied(
                @Nullable PopulationPersistenceTransition.Result owner,
                @Nullable NpcProfileRepository.DormantProfileResult profile,
                @Nullable PopulationGroupRepository.OperationResult groups,
                @Nonnull String reason) {
            return new ProvisionedDormantCommitResult(
                    ProvisionedDormantCompositeStatus.DENIED, owner, profile, groups, reason);
        }

        public boolean committed() { return status == ProvisionedDormantCompositeStatus.COMMITTED; }
    }

    public record PopulationGroupCompositeCommitResult(
            @Nonnull ProvisionedDormantCompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable PopulationGroupRepository.OperationResult groupResult,
            @Nullable String reason) {
        public PopulationGroupCompositeCommitResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static PopulationGroupCompositeCommitResult denied(
                @Nullable PopulationPersistenceTransition.Result owner,
                @Nullable PopulationGroupRepository.OperationResult groups,
                @Nonnull String reason) {
            return new PopulationGroupCompositeCommitResult(
                    ProvisionedDormantCompositeStatus.DENIED, owner, groups, reason);
        }

        public boolean committed() { return status == ProvisionedDormantCompositeStatus.COMMITTED; }
    }

}
