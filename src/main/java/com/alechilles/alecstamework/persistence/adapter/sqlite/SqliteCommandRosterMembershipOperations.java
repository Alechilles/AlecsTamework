package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Exact command membership mutations through the shared database operation protocol. */
public final class SqliteCommandRosterMembershipOperations {
    public static final String FEATURE_SCOPE = "command_roster";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCommandRosterMembershipOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Command roster operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact slot add, preference update, or removal. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CommandRosterMembershipRequest request
    ) {
        if (operationId == null || idempotencyKey == null
                || request == null) {
            throw new IllegalArgumentException(
                    "Complete command roster operation is required"
            );
        }
        return coordinator.execute(
                CommandRosterMembershipDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        request,
                        FEATURE_SCOPE,
                        request.expectedLifecycleRevision(),
                        List.of(
                                OperationScope.profile(request.profileId()),
                                OperationScope.owner(
                                        request.familyKey().ownerId()
                                ),
                                OperationScope.commandFamily(
                                        request.familyKey()
                                )
                        ),
                        request.requestedAtMs()
                ),
                new ExactMembershipDetail(request),
                (transaction, operation) -> List.of(event(
                        transaction,
                        operation.operationId(),
                        apply(transaction, request),
                        request.action(),
                        request.requestedAtMs()
                )),
                requiredConsumers
        );
    }

    private AppliedMembership apply(
            SqlitePersistenceTransactionContext transaction,
            CommandRosterMembershipRequest request
    ) {
        MembershipSource source = requireSource(transaction, request);
        CommandRosterMembership current = source.membership();
        PersistenceMutationResult<CommandRosterMutationOutcome> result =
                request.action()
                        == CommandRosterMembershipRequest.Action.UPSERT
                        ? transaction.commandRosters().upsert(
                        request.expectedRosterRevision(),
                        request.expectedMembershipRevision(),
                        new CommandRosterMembershipDraft(
                                request.slotId(),
                                request.familyKey(),
                                request.profileId(),
                                request.groupId(),
                                request.activeForBulkCommands(),
                                request.home(),
                                request.requestedAtMs()
                        )
                )
                        : transaction.commandRosters().remove(
                        request.expectedRosterRevision(),
                        request.expectedMembershipRevision(),
                        request.familyKey(),
                        request.profileId(),
                        request.requestedAtMs()
                );
        if (!result.applied()) {
            throw new IllegalStateException(
                    "command_roster_"
                            + result.status().name().toLowerCase()
            );
        }
        return new AppliedMembership(
                result.value(), source.lifecycle()
        );
    }

    private static MembershipSource requireSource(
            SqlitePersistenceTransactionContext transaction,
            CommandRosterMembershipRequest request
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "command_roster_profile_missing"
                ));
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "command_roster_lifecycle_missing"
                ));
        CommandRosterMembership membership =
                transaction.commandRosters()
                        .findByProfile(request.profileId())
                        .orElse(null);
        if (identity.metadataRevision()
                != request.expectedMetadataRevision()
                || !request.expectedRoleId().equals(identity.roleId())
                || !lifecycle.revision().equals(
                request.expectedLifecycleRevision()
        )
                || !request.familyKey().ownerId().equals(
                lifecycle.ownerId()
        )
                || !Objects.equals(
                request.expectedOwnerWorldKey(),
                lifecycle.ownerWorldKey()
        )
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || !membershipMatches(membership, request)
                || !storedSlotMatches(lifecycle, request)) {
            throw new IllegalStateException(
                    "command_roster_source_mismatch"
            );
        }
        return new MembershipSource(membership, lifecycle);
    }

    private static boolean membershipMatches(
            CommandRosterMembership current,
            CommandRosterMembershipRequest request
    ) {
        if (current == null) {
            return request.expectedMembershipRevision() == null
                    && request.action()
                    == CommandRosterMembershipRequest.Action.UPSERT;
        }
        return request.expectedMembershipRevision() != null
                && current.membershipRevision()
                == request.expectedMembershipRevision()
                && current.familyKey().equals(request.familyKey())
                && current.slotId().equals(request.slotId());
    }

    private static boolean storedSlotMatches(
            CompanionLifecycle lifecycle,
            CommandRosterMembershipRequest request
    ) {
        return lifecycle.state() != LifecycleState.ROSTER_STORED
                || lifecycle.location().kind()
                == LifecycleLocationKind.COMMAND_ROSTER
                && request.slotId().toString().equals(
                lifecycle.location().key()
        );
    }

    private ProjectionEventDraft event(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            AppliedMembership applied,
            CommandRosterMembershipRequest.Action action,
            long changedAtMs
    ) {
        return CommandRosterMembershipChangeCodec.draft(
                operationId,
                SqliteCommandSemanticEventEvidence.roster(
                        transaction,
                        applied.mutation(),
                        applied.lifecycle(),
                        action == CommandRosterMembershipRequest.Action.REMOVE
                                ? CommandRosterMembershipChangeEvidence
                                .Reason.REMOVED
                                : CommandRosterMembershipChangeEvidence
                                .Reason.UPSERTED
                ),
                changedAtMs
        );
    }

    private record MembershipSource(
            CommandRosterMembership membership,
            CompanionLifecycle lifecycle
    ) {
    }

    private record AppliedMembership(
            CommandRosterMutationOutcome mutation,
            CompanionLifecycle lifecycle
    ) {
    }

    /** Exact source validation keeps stale requests out of durable recovery. */
    private static final class ExactMembershipDetail
            implements PreparedOperationDetail {
        private final CommandRosterMembershipRequest request;

        private ExactMembershipDetail(
                CommandRosterMembershipRequest request
        ) {
            this.request = request;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            requireSource(transaction, request);
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return true;
            }
            try {
                requireSource(transaction, request);
                return operation.phase() == OperationPhase.PREPARED
                        || operation.phase() == OperationPhase.RETRYABLE;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }
}
