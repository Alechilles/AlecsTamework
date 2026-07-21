package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns rollback-safe SQLite boundaries that span owner population, population groups, and
 * intentionally null-NPC profile metadata.
 */
public final class UnifiedPopulationCompositeStore {
    private final CompanionPopulationRepository populationRepository;
    private final PersistenceWriteQueue writeQueue;
    private final CompanionPopulationJournalStore journalStore;

    UnifiedPopulationCompositeStore(
            @Nonnull CompanionPopulationRepository populationRepository,
            @Nonnull PersistenceWriteQueue writeQueue,
            @Nonnull CompanionPopulationJournalStore journalStore) {
        this.populationRepository = Objects.requireNonNull(
                populationRepository, "populationRepository");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
        this.journalStore = Objects.requireNonNull(journalStore, "journalStore");
    }

    /** Prepares owner and group reservations in one rollback-safe SQLite transaction. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProvisionedDormantPreparationResult>
    prepareProvisionedDormantAsync(
            @Nonnull PopulationPersistenceTransition.Prepare ownerPrepare,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull PopulationGroupOperationRecord groupOperation,
            @Nonnull List<PopulationGroupRepository.ReservationEvidence> groupEvidence) {
        Objects.requireNonNull(groupRepository, "groupRepository");
        List<PopulationGroupRepository.ReservationEvidence> frozenEvidence =
                List.copyOf(groupEvidence);
        return writeQueue.submitTracked(
                "provisioned_dormant_population_prepare",
                connection -> prepareProvisionedDormantInTransaction(
                        connection, ownerPrepare, groupRepository, groupOperation, frozenEvidence),
                null);
    }

    /** Commits owner state, null-NPC profile metadata, and group classification atomically. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProvisionedDormantCommitResult>
    commitProvisionedDormantAsync(
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
                    return commitProvisionedDormantInTransaction(
                            connection, ownerCommit, profileRepository, profileMutation,
                            groupRepository, groupOperationId, classification, nowMs);
                },
                result -> {
                    if (result != null && result.committed() && result.profileResult() != null) {
                        profileRepository.notifyProfileChanged(
                                before.get(), result.profileResult().profile());
                    }
                });
    }

    /** Commits a normal claim-bearing owner transition and group classification atomically. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PopulationGroupCompositeCommitResult>
    commitPopulationGroupsAsync(
            @Nonnull PopulationPersistenceTransition.Commit ownerCommit,
            @Nonnull PopulationGroupRepository groupRepository,
            @Nonnull String groupOperationId,
            @Nonnull PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) {
        Objects.requireNonNull(groupRepository, "groupRepository");
        return writeQueue.submitTracked(
                "population_group_composite_commit",
                connection -> commitPopulationGroupsInTransaction(
                        connection, ownerCommit, groupRepository, groupOperationId,
                        classification, nowMs),
                null);
    }

    private ProvisionedDormantPreparationResult prepareProvisionedDormantInTransaction(
            Connection connection,
            PopulationPersistenceTransition.Prepare ownerPrepare,
            PopulationGroupRepository groupRepository,
            PopulationGroupOperationRecord groupOperation,
            List<PopulationGroupRepository.ReservationEvidence> groupEvidence) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner =
                    populationRepository.prepareInTransaction(connection, ownerPrepare);
            if (owner.status() != PopulationPersistenceTransition.ResultStatus.PREPARED
                    && owner.status() != PopulationPersistenceTransition.ResultStatus.IDEMPOTENT) {
                rollback(connection, boundary);
                return ProvisionedDormantPreparationResult.denied(owner, null,
                        owner.reason() == null ? "owner_population_prepare_denied" : owner.reason());
            }
            PopulationGroupRepository.ReservationResult groups =
                    groupRepository.reserveOperationInTransaction(
                            connection, groupOperation, groupEvidence);
            if (groups.status() != PopulationGroupRepository.Status.PREPARED
                    && groups.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                rollback(connection, boundary);
                return ProvisionedDormantPreparationResult.denied(owner, groups,
                        groups.reason() == null
                                ? "population_group_prepare_denied" : groups.reason());
            }
            advancePreparedOwner(connection, ownerPrepare);
            advancePreparedGroups(connection, groupRepository, groupOperation, groups,
                    ownerPrepare.operation().updatedAtMs());
            connection.releaseSavepoint(boundary);
            return new ProvisionedDormantPreparationResult(
                    CompositeStatus.PREPARED, owner, groups, null);
        } catch (Exception failure) {
            rollback(connection, boundary);
            throw failure;
        }
    }

    private void advancePreparedOwner(
            Connection connection,
            PopulationPersistenceTransition.Prepare ownerPrepare) throws Exception {
        CompanionPopulationJournalStore.OperationIdentity operation =
                journalStore.find(connection, ownerPrepare.operation().operationId());
        if (operation == null) throw new IllegalStateException("Prepared owner operation disappeared.");
        if (operation.state() == CompanionPopulationOperationRecord.State.PREPARED) {
            if (!journalStore.advance(connection, ownerPrepare.operation().operationId(),
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING, null)) {
                throw new IllegalStateException("Owner operation changed during composite preparation.");
            }
        } else if (operation.state() != CompanionPopulationOperationRecord.State.APPLYING) {
            throw new IllegalStateException("Owner operation is not applying.");
        }
    }

    private void advancePreparedGroups(
            Connection connection,
            PopulationGroupRepository groupRepository,
            PopulationGroupOperationRecord requested,
            PopulationGroupRepository.ReservationResult groups,
            long ownerUpdatedAtMs) throws Exception {
        PopulationGroupOperationRecord persisted = groups.operation();
        if (persisted == null) throw new IllegalStateException("Prepared group operation disappeared.");
        if (persisted.state() == PopulationGroupOperationRecord.State.PREPARED) {
            PopulationGroupRepository.OperationResult applying =
                    groupRepository.advanceOperationInTransaction(
                            connection, persisted.operationId(),
                            PopulationGroupOperationRecord.State.PREPARED,
                            PopulationGroupOperationRecord.State.APPLYING, null,
                            Math.max(ownerUpdatedAtMs, requested.updatedAtMs()));
            if (applying.status() != PopulationGroupRepository.Status.APPLYING
                    && applying.status() != PopulationGroupRepository.Status.IDEMPOTENT) {
                throw new IllegalStateException("Group operation changed during composite preparation.");
            }
        } else if (persisted.state() != PopulationGroupOperationRecord.State.APPLYING) {
            throw new IllegalStateException("Group operation is not applying.");
        }
    }

    private ProvisionedDormantCommitResult commitProvisionedDormantInTransaction(
            Connection connection,
            PopulationPersistenceTransition.Commit ownerCommit,
            NpcProfileRepository profileRepository,
            NpcProfileRepository.DormantProfileMutation profileMutation,
            PopulationGroupRepository groupRepository,
            String groupOperationId,
            PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner =
                    populationRepository.commitInTransaction(connection, ownerCommit);
            if (!ownerCommitted(owner)) {
                rollback(connection, boundary);
                return ProvisionedDormantCommitResult.denied(owner, null, null,
                        reason(owner.reason(), "owner_population_commit_denied"));
            }
            NpcProfileRepository.DormantProfileResult profile =
                    profileRepository.applyDormantProfileInTransaction(connection, profileMutation);
            if (profile.status() != NpcProfileRepository.DormantProfileStatus.APPLIED
                    && profile.status() != NpcProfileRepository.DormantProfileStatus.IDEMPOTENT) {
                rollback(connection, boundary);
                return ProvisionedDormantCommitResult.denied(owner, profile, null,
                        reason(profile.reason(), "dormant_profile_commit_denied"));
            }
            PopulationGroupRepository.OperationResult groups = commitGroups(
                    connection, groupRepository, groupOperationId, classification, nowMs);
            if (!groupsCommitted(groups)) {
                rollback(connection, boundary);
                return ProvisionedDormantCommitResult.denied(owner, profile, groups,
                        reason(groups.reason(), "population_group_commit_denied"));
            }
            connection.releaseSavepoint(boundary);
            return new ProvisionedDormantCommitResult(
                    CompositeStatus.COMMITTED, owner, profile, groups, null);
        } catch (Exception failure) {
            rollback(connection, boundary);
            throw failure;
        }
    }

    private PopulationGroupCompositeCommitResult commitPopulationGroupsInTransaction(
            Connection connection,
            PopulationPersistenceTransition.Commit ownerCommit,
            PopulationGroupRepository groupRepository,
            String groupOperationId,
            PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) throws Exception {
        Savepoint boundary = connection.setSavepoint();
        try {
            PopulationPersistenceTransition.Result owner =
                    populationRepository.commitInTransaction(connection, ownerCommit);
            if (!ownerCommitted(owner)) {
                rollback(connection, boundary);
                return PopulationGroupCompositeCommitResult.denied(owner, null,
                        reason(owner.reason(), "owner_population_commit_denied"));
            }
            PopulationGroupRepository.OperationResult groups = commitGroups(
                    connection, groupRepository, groupOperationId, classification, nowMs);
            if (!groupsCommitted(groups)) {
                rollback(connection, boundary);
                return PopulationGroupCompositeCommitResult.denied(owner, groups,
                        reason(groups.reason(), "population_group_commit_denied"));
            }
            connection.releaseSavepoint(boundary);
            return new PopulationGroupCompositeCommitResult(
                    CompositeStatus.COMMITTED, owner, groups, null);
        } catch (Exception failure) {
            rollback(connection, boundary);
            throw failure;
        }
    }

    private PopulationGroupRepository.OperationResult commitGroups(
            Connection connection,
            PopulationGroupRepository repository,
            String operationId,
            PopulationGroupRepository.ClassificationMutation classification,
            long nowMs) throws Exception {
        return repository.commitClassificationOperationInTransaction(
                connection, operationId, classification, nowMs);
    }

    private static boolean ownerCommitted(PopulationPersistenceTransition.Result owner) {
        return owner.status() == PopulationPersistenceTransition.ResultStatus.COMMITTED
                || owner.status() == PopulationPersistenceTransition.ResultStatus.IDEMPOTENT;
    }

    private static boolean groupsCommitted(PopulationGroupRepository.OperationResult groups) {
        return groups.status() == PopulationGroupRepository.Status.COMMITTED
                || groups.status() == PopulationGroupRepository.Status.IDEMPOTENT;
    }

    private static String reason(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static void rollback(Connection connection, Savepoint boundary) throws Exception {
        connection.rollback(boundary);
        connection.releaseSavepoint(boundary);
    }

    public enum CompositeStatus { PREPARED, COMMITTED, DENIED }

    public record ProvisionedDormantPreparationResult(
            @Nonnull CompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable PopulationGroupRepository.ReservationResult groupResult,
            @Nullable String reason) {
        public ProvisionedDormantPreparationResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static ProvisionedDormantPreparationResult denied(
                PopulationPersistenceTransition.Result owner,
                PopulationGroupRepository.ReservationResult groups,
                String reason) {
            return new ProvisionedDormantPreparationResult(
                    CompositeStatus.DENIED, owner, groups, reason);
        }

        public boolean prepared() { return status == CompositeStatus.PREPARED; }
    }

    public record ProvisionedDormantCommitResult(
            @Nonnull CompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable NpcProfileRepository.DormantProfileResult profileResult,
            @Nullable PopulationGroupRepository.OperationResult groupResult,
            @Nullable String reason) {
        public ProvisionedDormantCommitResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static ProvisionedDormantCommitResult denied(
                PopulationPersistenceTransition.Result owner,
                NpcProfileRepository.DormantProfileResult profile,
                PopulationGroupRepository.OperationResult groups,
                String reason) {
            return new ProvisionedDormantCommitResult(
                    CompositeStatus.DENIED, owner, profile, groups, reason);
        }

        public boolean committed() { return status == CompositeStatus.COMMITTED; }
    }

    public record PopulationGroupCompositeCommitResult(
            @Nonnull CompositeStatus status,
            @Nullable PopulationPersistenceTransition.Result ownerResult,
            @Nullable PopulationGroupRepository.OperationResult groupResult,
            @Nullable String reason) {
        public PopulationGroupCompositeCommitResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static PopulationGroupCompositeCommitResult denied(
                PopulationPersistenceTransition.Result owner,
                PopulationGroupRepository.OperationResult groups,
                String reason) {
            return new PopulationGroupCompositeCommitResult(
                    CompositeStatus.DENIED, owner, groups, reason);
        }

        public boolean committed() { return status == CompositeStatus.COMMITTED; }
    }
}
