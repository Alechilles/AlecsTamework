package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationEventCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
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
                (transaction, operation) -> events(
                        operation.operationId(),
                        apply(transaction, operation.operationId(), mutation)
                ),
                requiredConsumers
        );
    }

    private AppliedMutation apply(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionProfileMutation mutation
    ) {
        if (mutation instanceof CompanionProfileMutation.Create create) {
            return create(transaction, create);
        }
        if (mutation instanceof CompanionProfileMutation.AdoptLive adoption) {
            return adoptLive(transaction, operationId, adoption);
        }
        return update(transaction, (CompanionProfileMutation.Update) mutation);
    }

    private AppliedMutation create(
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
            return unchanged(outcome(status, create, existing.metadataRevision()));
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
        CompanionProfileMutationOutcome outcome = outcome(
                CompanionProfileMutationOutcome.Status.CREATED,
                create,
                create.identity().metadataRevision()
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        create.profileId()
                );
        return changed(
                outcome,
                null,
                after,
                CompanionProfileProjectionChange.Source.METADATA,
                new CompanionLifecycleProjectionChange(
                        null,
                        create.lifecycle()
                )
        );
    }

    private AppliedMutation adoptLive(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionProfileMutation.AdoptLive adoption
    ) {
        CompanionIdentity existing =
                transaction.identities().findProfile(adoption.profileId()).orElse(null);
        CompanionAlias alias =
                transaction.identities().resolveAlias(adoption.alias()).orElse(null);
        if (existing != null) {
            CompanionLifecycle lifecycle = transaction.lifecycles()
                    .findByProfile(adoption.profileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "profile_lifecycle_missing"
                    ));
            List<CompanionToolLink> links =
                    transaction.toolLinks().findByProfile(adoption.profileId());
            CompanionProfileMutationOutcome.Status status =
                    adoptionMatches(existing, alias, lifecycle, links, adoption)
                            ? CompanionProfileMutationOutcome.Status.UNCHANGED
                            : CompanionProfileMutationOutcome.Status.CONFLICT;
            return unchanged(outcome(status, adoption, existing.metadataRevision()));
        }
        if (alias != null) {
            return unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.CONFLICT,
                    adoption,
                    0
            ));
        }
        requireApplied(
                transaction.identities().createProfile(adoption.identity()),
                "profile_adopt_identity"
        );
        requireApplied(
                transaction.identities().leaseAlias(
                        adoption.profileId(),
                        adoption.alias(),
                        operationId,
                        adoption.requestedAtMs()
                ),
                "profile_adopt_alias_lease"
        );
        CompanionAlias currentAlias = requireApplied(
                transaction.identities().promoteAlias(
                        adoption.alias(),
                        operationId,
                        adoption.requestedAtMs()
                ),
                "profile_adopt_alias_promote"
        );
        if (currentAlias.generation() != 0) {
            throw new IllegalStateException("profile_adopt_alias_generation_not_initial");
        }
        CompanionLifecycle lifecycle = adoption.initialLifecycle();
        requireApplied(
                transaction.lifecycles().create(lifecycle),
                "profile_adopt_lifecycle"
        );
        requireApplied(
                transaction.toolLinks().replace(
                        adoption.profileId(),
                        adoption.toolLinks()
                ),
                "profile_adopt_tool_links"
        );
        CompanionProfileMutationOutcome outcome = outcome(
                CompanionProfileMutationOutcome.Status.CREATED,
                adoption,
                adoption.identity().metadataRevision()
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        adoption.profileId()
                );
        return changed(
                outcome,
                null,
                after,
                CompanionProfileProjectionChange.Source.METADATA,
                new CompanionLifecycleProjectionChange(null, lifecycle)
        );
    }

    private boolean adoptionMatches(
            CompanionIdentity identity,
            CompanionAlias alias,
            CompanionLifecycle lifecycle,
            List<CompanionToolLink> links,
            CompanionProfileMutation.AdoptLive adoption
    ) {
        return identity.equals(adoption.identity())
                && alias != null
                && alias.profileId().equals(adoption.profileId())
                && alias.alias().equals(adoption.alias())
                && alias.generation() == 0
                && alias.state() == CompanionAlias.State.CURRENT
                && lifecycle.equals(adoption.initialLifecycle())
                && links.equals(adoption.toolLinks());
    }

    private AppliedMutation update(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation.Update update
    ) {
        CompanionIdentity existing =
                transaction.identities().findProfile(update.profileId()).orElse(null);
        if (existing == null) {
            return unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.NOT_FOUND,
                    update,
                    0
            ));
        }
        if (existing.metadataRevision() != update.expectedMetadataRevision()) {
            return unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                    update,
                    existing.metadataRevision()
            ));
        }
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        update.profileId()
                );
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
        CompanionProfileMutationOutcome outcome = outcome(
                CompanionProfileMutationOutcome.Status.UPDATED,
                update,
                identity.value().metadataRevision()
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        update.profileId()
                );
        return changed(
                outcome,
                before,
                after,
                CompanionProfileProjectionChange.Source.METADATA
        );
    }

    private AppliedMutation rejected(
            PersistenceMutationStatus status,
            CompanionProfileMutation mutation,
            long revision
    ) {
        return switch (status) {
            case NOT_FOUND -> unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.NOT_FOUND,
                    mutation,
                    revision
            ));
            case REVISION_MISMATCH -> unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.REVISION_MISMATCH,
                    mutation,
                    revision
            ));
            case CONFLICT -> unchanged(outcome(
                    CompanionProfileMutationOutcome.Status.CONFLICT,
                    mutation,
                    revision
            ));
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
                "profile-mutation-result:" + outcome.profileId(),
                outcome.metadataRevision(),
                CompanionProfileMutationEventCodec.VERSION,
                CompanionProfileMutationEventCodec.encode(outcome),
                outcome.updatedAtMs()
        );
    }

    private List<ProjectionEventDraft> events(
            OperationId operationId,
            AppliedMutation applied
    ) {
        java.util.ArrayList<ProjectionEventDraft> events =
                new java.util.ArrayList<>();
        events.add(event(operationId, applied.outcome()));
        if (applied.change() != null) {
            events.add(SqliteCompanionProfileProjectionComposer.event(
                    operationId,
                    applied.change()
            ));
        }
        if (applied.lifecycleChange() != null) {
            events.add(CompanionLifecycleProjectionChangeCodec.draft(
                    operationId,
                    applied.lifecycleChange().before(),
                    applied.lifecycleChange().after(),
                    applied.outcome().updatedAtMs()
            ));
        }
        return List.copyOf(events);
    }

    private AppliedMutation unchanged(CompanionProfileMutationOutcome outcome) {
        return new AppliedMutation(outcome, null, null);
    }

    private AppliedMutation changed(
            CompanionProfileMutationOutcome outcome,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            CompanionProfileProjectionChange.Source source
    ) {
        return changed(outcome, before, after, source, null);
    }

    private AppliedMutation changed(
            CompanionProfileMutationOutcome outcome,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            CompanionProfileProjectionChange.Source source,
            CompanionLifecycleProjectionChange lifecycleChange
    ) {
        return new AppliedMutation(
                outcome,
                new CompanionProfileProjectionChange(
                        source,
                        outcome.profileId(),
                        outcome.metadataRevision(),
                        before,
                        after,
                        outcome.updatedAtMs()
                ),
                lifecycleChange
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

    private record AppliedMutation(
            CompanionProfileMutationOutcome outcome,
            CompanionProfileProjectionChange change,
            CompanionLifecycleProjectionChange lifecycleChange
    ) {
    }
}
