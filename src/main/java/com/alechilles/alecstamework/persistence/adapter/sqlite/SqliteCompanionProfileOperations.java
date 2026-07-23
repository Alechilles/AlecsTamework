package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationEventCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Profile identity creation and metadata/tool-link updates through one shared operation workflow.
 */
public final class SqliteCompanionProfileOperations {
    public static final String FEATURE_SCOPE = "companion_profile";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_profile_mutated");

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionProfileOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Profile operation dependencies are required");
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact profile create/update operation. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionProfileMutation mutation
    ) {
        if (operationId == null || idempotencyKey == null || mutation == null) {
            throw new IllegalArgumentException("Complete profile operation is required");
        }
        return coordinator.execute(
                CompanionProfileMutationDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        mutation,
                        FEATURE_SCOPE,
                        null,
                        List.of(OperationScope.profile(mutation.profileId())),
                        mutation.requestedAtMs()
                ),
                (transaction, operation) -> List.of(event(
                        operation.operationId(),
                        apply(transaction, mutation)
                )),
                requiredConsumers
        );
    }

    private CompanionProfileMutationOutcome apply(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation mutation
    ) {
        if (mutation instanceof CompanionProfileMutation.Create create) {
            return create(transaction, create);
        }
        return update(transaction, (CompanionProfileMutation.Update) mutation);
    }

    private CompanionProfileMutationOutcome create(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation.Create create
    ) {
        CompanionIdentity existing =
                transaction.identities().findProfile(create.profileId()).orElse(null);
        if (existing != null) {
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(create.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "profile_lifecycle_missing"
                    ));
            List<CompanionToolLink> links =
                    transaction.toolLinks().findByProfile(create.profileId());
            CompanionProfileMutationOutcome.Status status =
                    existing.equals(create.identity())
                            && lifecycle.equals(create.lifecycle())
                            && links.equals(create.toolLinks())
                            ? CompanionProfileMutationOutcome.Status.UNCHANGED
                            : CompanionProfileMutationOutcome.Status.CONFLICT;
            return outcome(
                    status,
                    create,
                    existing.metadataRevision()
            );
        }
        requireApplied(
                transaction.identities().createProfile(create.identity()),
                "profile_create_identity"
        );
        requireApplied(
                transaction.lifecycles().create(create.lifecycle()),
                "profile_create_lifecycle"
        );
        requireApplied(
                transaction.toolLinks().replace(create.profileId(), create.toolLinks()),
                "profile_create_tool_links"
        );
        return outcome(
                CompanionProfileMutationOutcome.Status.CREATED,
                create,
                create.identity().metadataRevision()
        );
    }

    private CompanionProfileMutationOutcome update(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation.Update update
    ) {
        CompanionIdentity existing =
                transaction.identities().findProfile(update.profileId()).orElse(null);
        if (existing == null) {
            return outcome(
                    CompanionProfileMutationOutcome.Status.NOT_FOUND,
                    update,
                    0
            );
        }
        if (existing.metadataRevision() != update.expectedMetadataRevision()) {
            return outcome(
                    CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                    update,
                    existing.metadataRevision()
            );
        }
        PersistenceMutationResult<CompanionIdentity> identity =
                transaction.identities().updateProfile(
                        update.nextIdentity(),
                        update.expectedMetadataRevision()
                );
        if (!identity.applied()) {
            return rejected(identity.status(), update, existing.metadataRevision());
        }
        PersistenceMutationResult<List<CompanionToolLink>> links =
                transaction.toolLinks().replace(update.profileId(), update.toolLinks());
        requireApplied(links, "profile_update_tool_links");
        return outcome(
                CompanionProfileMutationOutcome.Status.UPDATED,
                update,
                identity.value().metadataRevision()
        );
    }

    private CompanionProfileMutationOutcome rejected(
            PersistenceMutationStatus status,
            CompanionProfileMutation mutation,
            long revision
    ) {
        return switch (status) {
            case NOT_FOUND -> outcome(
                    CompanionProfileMutationOutcome.Status.NOT_FOUND,
                    mutation,
                    revision
            );
            case REVISION_MISMATCH -> outcome(
                    CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                    mutation,
                    revision
            );
            case CONFLICT -> outcome(
                    CompanionProfileMutationOutcome.Status.CONFLICT,
                    mutation,
                    revision
            );
            default -> throw new IllegalStateException(
                    "profile_mutation_" + status.name().toLowerCase()
            );
        };
    }

    private CompanionProfileMutationOutcome outcome(
            CompanionProfileMutationOutcome.Status status,
            CompanionProfileMutation mutation,
            long revision
    ) {
        return new CompanionProfileMutationOutcome(
                status,
                mutation.profileId(),
                revision,
                mutation.requestedAtMs()
        );
    }

    private ProjectionEventDraft event(
            OperationId operationId,
            CompanionProfileMutationOutcome outcome
    ) {
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                outcome.profileId().toString(),
                outcome.metadataRevision(),
                CompanionProfileMutationEventCodec.VERSION,
                CompanionProfileMutationEventCodec.encode(outcome),
                outcome.updatedAtMs()
        );
    }

    private <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }
}
