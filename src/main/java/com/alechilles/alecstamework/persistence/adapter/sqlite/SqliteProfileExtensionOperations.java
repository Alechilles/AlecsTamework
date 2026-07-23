package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataDecoder;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataPort;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationEventCodec;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationOutcome;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Profile extension puts and deletes composed through the shared database-operation workflow.
 *
 * <p>Domain denials produce durable outbox outcomes. Storage failures roll back and remain
 * retryable instead of masquerading as absence or a revision mismatch.</p>
 */
public final class SqliteProfileExtensionOperations {
    public static final String FEATURE_SCOPE = "profile_extension";
    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteProfileExtensionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Extension operation dependencies are required");
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact put/delete operation. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull ProfileExtensionMutation mutation
    ) {
        if (operationId == null || idempotencyKey == null || mutation == null) {
            throw new IllegalArgumentException("Complete extension operation is required");
        }
        OperationRequest<ProfileExtensionMutation> request = new OperationRequest<>(
                operationId,
                idempotencyKey,
                mutation,
                FEATURE_SCOPE,
                null,
                List.of(OperationScope.profile(mutation.key().profileId())),
                mutation.requestedAtMs()
        );
        return coordinator.execute(
                ProfileExtensionMutationDefinition.INSTANCE,
                request,
                (transaction, operation) -> List.of(event(
                        operation.operationId(),
                        apply(transaction, mutation)
                )),
                requiredConsumers
        );
    }

    private ProfileExtensionMutationOutcome apply(
            SqlitePersistenceTransactionContext transaction,
            ProfileExtensionMutation mutation
    ) {
        ProfileExtensionDataPort store = transaction.profileExtensions();
        ProfileExtensionData current = read(store, mutation);
        long currentRevision = current == null ? 0 : current.revision();
        if (mutation.expectedRevision() != null
                && mutation.expectedRevision() != currentRevision) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.REVISION_MISMATCH,
                    mutation,
                    currentRevision,
                    null
            );
        }
        if (current == null && transaction.identities()
                .findProfile(mutation.key().profileId()).isEmpty()) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.PROFILE_NOT_FOUND,
                    mutation,
                    0,
                    null
            );
        }
        return mutation.action() == ProfileExtensionMutationAction.PUT
                ? put(store, mutation, current, currentRevision)
                : delete(store, mutation, current, currentRevision);
    }

    private ProfileExtensionMutationOutcome put(
            ProfileExtensionDataPort store,
            ProfileExtensionMutation mutation,
            ProfileExtensionData current,
            long currentRevision
    ) {
        requireAdvanceable(currentRevision);
        String json = mutation.jsonPayload();
        ProfileExtensionData next = new ProfileExtensionData(
                mutation.key(),
                ProfileExtensionDataDecoder.JSON_VERSION,
                json,
                Sha256Hash.ofUtf8(json),
                currentRevision + 1,
                current == null ? mutation.requestedAtMs() : current.createdAtMs(),
                mutation.requestedAtMs(),
                null
        );
        PersistenceMutationResult<ProfileExtensionData> result =
                store.put(next, currentRevision);
        if (result.applied()) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.APPLIED,
                    mutation,
                    result.value().revision(),
                    result.value().jsonPayload()
            );
        }
        return rejected(result.status(), mutation, currentRevision);
    }

    private ProfileExtensionMutationOutcome delete(
            ProfileExtensionDataPort store,
            ProfileExtensionMutation mutation,
            ProfileExtensionData current,
            long currentRevision
    ) {
        if (current == null || current.deleted()) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.UNCHANGED,
                    mutation,
                    currentRevision,
                    null
            );
        }
        requireAdvanceable(currentRevision);
        PersistenceMutationResult<ProfileExtensionData> result = store.delete(
                mutation.key(),
                currentRevision,
                mutation.requestedAtMs()
        );
        if (result.applied()) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.DELETED,
                    mutation,
                    result.value().revision(),
                    null
            );
        }
        return rejected(result.status(), mutation, currentRevision);
    }

    private ProfileExtensionData read(
            ProfileExtensionDataPort store,
            ProfileExtensionMutation mutation
    ) {
        PersistenceReadResult<ProfileExtensionData> result = store.find(mutation.key());
        if (result instanceof PersistenceReadResult.Found<ProfileExtensionData> found) {
            return found.value();
        }
        if (result instanceof PersistenceReadResult.Failed<ProfileExtensionData> failed) {
            throw new PersistenceStoreException(
                    failed.failure().operation(),
                    failed.failure().cause() == null
                            ? new IllegalStateException(failed.failure().code())
                            : failed.failure().cause()
            );
        }
        return null;
    }

    private ProfileExtensionMutationOutcome rejected(
            PersistenceMutationStatus status,
            ProfileExtensionMutation mutation,
            long currentRevision
    ) {
        if (status == PersistenceMutationStatus.NOT_FOUND) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.PROFILE_NOT_FOUND,
                    mutation,
                    currentRevision,
                    null
            );
        }
        if (status == PersistenceMutationStatus.REVISION_MISMATCH) {
            return outcome(
                    ProfileExtensionMutationOutcome.Status.REVISION_MISMATCH,
                    mutation,
                    currentRevision,
                    null
            );
        }
        throw new IllegalStateException(
                "extension_mutation_" + status.name().toLowerCase()
        );
    }

    private ProfileExtensionMutationOutcome outcome(
            ProfileExtensionMutationOutcome.Status status,
            ProfileExtensionMutation mutation,
            long revision,
            String jsonPayload
    ) {
        return new ProfileExtensionMutationOutcome(
                status,
                mutation.key(),
                revision,
                jsonPayload,
                mutation.requestedAtMs()
        );
    }

    private ProjectionEventDraft event(
            OperationId operationId,
            ProfileExtensionMutationOutcome outcome
    ) {
        return new ProjectionEventDraft(
                operationId,
                ProfileExtensionMutationEventCodec.EVENT_TYPE,
                outcome.key().aggregateId(),
                outcome.revision(),
                ProfileExtensionMutationEventCodec.VERSION,
                ProfileExtensionMutationEventCodec.encode(outcome),
                outcome.updatedAtMs()
        );
    }

    private void requireAdvanceable(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("extension_revision_exhausted");
        }
    }
}
