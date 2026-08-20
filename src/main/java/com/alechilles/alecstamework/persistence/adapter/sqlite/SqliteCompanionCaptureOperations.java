package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One lifecycle-fenced live capture through the shared operation protocol.
 *
 * <p>The operation payload is durable source evidence and the capture snapshot ID is the
 * canonical capture-artifact claim. No independent capture attempt or lifecycle table exists.</p>
 */
public final class SqliteCompanionCaptureOperations {
    public static final String FEATURE_SCOPE = "companion_capture";
    public static final ProjectionEventType EVENT_TYPE =
            SqliteCompanionCaptureCommit.CAPTURED_EVENT_TYPE;

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteCaptureCompensation compensation;
    @Nullable
    private final SqliteOperationReader reader;
    @Nullable
    private final SqliteLifecycleAdmissionBinding lifecycleAdmission;
    private final SqliteCompanionCaptureCommit commit =
            new SqliteCompanionCaptureCommit();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCaptureOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(
                operations,
                publisher,
                clock,
                refunds,
                null,
                null,
                requiredConsumers
        );
    }

    SqliteCompanionCaptureOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nullable SqliteOperationReader reader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || refunds == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Companion capture dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        compensation = new SqliteCaptureCompensation(
                operations,
                clock,
                refunds
        );
        this.reader = reader;
        this.lifecycleAdmission = lifecycleAdmission;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact source-correlated capture. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureRequest capture,
            @Nonnull CompanionCaptureLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || capture == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete companion capture is required");
        }
        if (!capture.tameAndCommandLink()
                || reader == null || lifecycleAdmission == null) {
            return execute(
                    operationId, idempotencyKey, capture, liveBoundary
            );
        }
        return admissionAwareSubmit(
                operationId, idempotencyKey, capture, liveBoundary
        );
    }

    private Submission admissionAwareSubmit(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureRequest requested,
            CompanionCaptureLiveBoundary liveBoundary
    ) {
        CompletionStage<ResolvedCapture> resolved = reader
                .findByIdempotency(
                        CompanionCaptureDefinition.KIND, idempotencyKey
                )
                .thenCompose(read -> resolveRead(
                        operationId, idempotencyKey, requested, read
                ));
        CompletionStage<OperationWorkflowResult> completion = resolved
                .thenCompose(value -> execute(
                        value.operationId(),
                        idempotencyKey,
                        value.payload(),
                        liveBoundary
                ).completion());
        return new Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                completion.exceptionally(failure ->
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                unwrap(failure)
                        )
                )
        );
    }

    private CompletionStage<ResolvedCapture> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureRequest requested,
            PersistenceReadResult<SqliteOperationReader.OperationReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Found<
                SqliteOperationReader.OperationReadModel> found) {
            return decodeExisting(
                    found.value(), operationId, idempotencyKey
            );
        }
        if (read instanceof PersistenceReadResult.Failed<
                SqliteOperationReader.OperationReadModel> failed) {
            return CompletableFuture.failedFuture(
                    failed.failure().cause() == null
                            ? new IllegalStateException(
                            "capture_admission_read_failed"
                    )
                            : failed.failure().cause()
            );
        }
        return reader.find(operationId).thenCompose(byId -> {
            if (byId instanceof PersistenceReadResult.Found<
                    SqliteOperationReader.OperationReadModel> found) {
                return decodeExisting(
                        found.value(), operationId, idempotencyKey
                );
            }
            if (byId instanceof PersistenceReadResult.Failed<
                    SqliteOperationReader.OperationReadModel> failed) {
                return CompletableFuture.failedFuture(
                        failed.failure().cause() == null
                                ? new IllegalStateException(
                                "capture_admission_read_failed"
                        )
                                : failed.failure().cause()
                );
            }
            if (requested.admissionEvidence() != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "lifecycle-admission-evidence-requires-existing-operation"
                        )
                );
            }
            return authorize(requested, operationId).thenApply(
                    payload -> new ResolvedCapture(operationId, payload)
            );
        });
    }

    private CompletionStage<ResolvedCapture> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !CompanionCaptureDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "capture_replay_operation_identity_mismatch"
                    )
            );
        }
        try {
            return CompletableFuture.completedFuture(
                    new ResolvedCapture(
                            model.operation().operationId(),
                            CompanionCaptureDefinition.INSTANCE.decode(
                                    model.operation().payloadJson()
                            )
                    )
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<CompanionCaptureRequest> authorize(
            CompanionCaptureRequest requested,
            OperationId operationId
    ) {
        CaptureTameAndLinkEvidence tame = requested.tameAndLinkEvidence();
        CompanionLifecycle source = tame.expectedLifecycle();
        PopulationAdmissionRequestV2 candidate = new PopulationAdmissionRequestV2(
                new PopulationAdmissionRequest(
                        new PopulationAdmissionIdentity(
                                requested.profileId().toString(), null, null
                        ),
                        null,
                        source.revision().value(),
                        null,
                        tame.finalLifecycle().ownerId().value(),
                        new PopulationAdmissionLocation(
                                source.location().worldKey(), 0, 0
                        ),
                        new PopulationAdmissionLocation(
                                tame.finalLifecycle().location().worldKey(),
                                0,
                                0
                        ),
                        PopulationAdmissionOperation.NEW_OWNERSHIP,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        com.alechilles.alecstamework.api
                                .PopulationCompanionLifecycle.ACTIVE
                ),
                tame.live().targetRoleId(),
                tame.finalLifecycle().location().worldKey()
        );
        LifecycleAdmissionRequest request = LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                tame.live().targetRoleId(),
                candidate,
                source,
                source.state(),
                LifecycleState.ACTIVE,
                source.ownerId(),
                source.ownerWorldKey()
        );
        return lifecycleAdmission.authorize(request).thenApply(
                requested::withAdmissionEvidence
        );
    }

    private Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureRequest capture,
            CompanionCaptureLiveBoundary liveBoundary
    ) {
        OperationId envelopeOperationId = operationId;
        OperationRequest<CompanionCaptureRequest> request =
                new OperationRequest<>(
                        envelopeOperationId,
                        idempotencyKey,
                        capture,
                        FEATURE_SCOPE,
                        capture.expectedLifecycleRevision(),
                        participants(capture),
                        capture.requestedAtMs()
                );
        SqliteCompanionCapturePreparation base =
                new SqliteCompanionCapturePreparation(capture);
        PreparedOperationDetail detail = base;
        SqliteOwnerPopulationParticipant owner = null;
        SqliteCapturePopulationGroupParticipant groups = null;
        SqliteManagedAdmissionParticipant managed = null;
        if (capture.tameAndCommandLink()) {
            SqliteCaptureCommandActivationParticipant command =
                    new SqliteCaptureCommandActivationParticipant(
                            capture.tameAndLinkEvidence()
                    );
            if (capture.admissionEvidence() != null
                    && capture.admissionEvidence().status()
                    == LifecycleAdmissionEvidence.Status.MANAGED) {
                managed = SqliteManagedAdmissionParticipant.from(
                        envelopeOperationId, capture.admissionEvidence()
                );
                detail = PreparedOperationDetail.compose(
                        command, managed, base
                );
            } else {
                owner = new SqliteOwnerPopulationParticipant(
                        capture.tameAndLinkEvidence().ownerPopulation()
                );
                groups = new SqliteCapturePopulationGroupParticipant(
                        capture.tameAndLinkEvidence()
                );
                detail = PreparedOperationDetail.compose(
                        command, owner, groups, base
                );
            }
        }
        SqliteOwnerPopulationParticipant ownerParticipant = owner;
        SqliteCapturePopulationGroupParticipant groupParticipant = groups;
        SqliteManagedAdmissionParticipant managedParticipant = managed;
        SqliteLiveOperationCoordinator.Submission submission = workflow.execute(
                CompanionCaptureDefinition.INSTANCE,
                request,
                detail,
                liveBoundary,
                (transaction, operation, payload, committedAtMs) -> {
                    if (managedParticipant != null) {
                        return managedParticipant.decorate(
                                (current, envelope) -> commit.commit(
                                        current,
                                        envelope,
                                        payload,
                                        committedAtMs
                                )
                        ).execute(transaction, operation);
                    }
                    if (ownerParticipant == null || groupParticipant == null) {
                        return commit.commit(
                                transaction, operation, payload, committedAtMs
                        );
                    }
                    return ownerParticipant.decorate(
                            groupParticipant.decorate(
                                    (current, envelope) -> commit.commit(
                                            current,
                                            envelope,
                                            payload,
                                            committedAtMs
                                    )
                            )
                    ).execute(transaction, operation);
                },
                requiredConsumers,
                "companion_capture"
        );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result -> {
                    OperationEnvelope operation = result.operation();
                    if (operation != null
                            && (result.status()
                            == OperationWorkflowResult.Status.COMPENSATION_REQUIRED
                            || operation.phase() == OperationPhase.COMPENSATING
                            || operation.phase() == OperationPhase.COMPENSATED)) {
                        return compensation.resume(operation, capture);
                    }
                    return CompletableFuture.completedFuture(result);
                });
        return new Submission(submission.acceptance(), completion);
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value().toString()
                + ":lifecycle-admission").getBytes(
                java.nio.charset.StandardCharsets.UTF_8
        ));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private record ResolvedCapture(
            OperationId operationId,
            CompanionCaptureRequest payload
    ) {
    }

    private List<OperationScope> participants(
            CompanionCaptureRequest capture
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(capture.profileId()));
        scopes.add(OperationScope.owner(OwnerId.parse(
                capture.source().actorUuid().toString()
        )));
        if (capture.tameAndCommandLink()) {
            scopes.add(OperationScope.commandFamily(
                    capture.tameAndLinkEvidence()
                            .rosterMembership().familyKey()
            ));
        }
        return List.copyOf(scopes);
    }

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Companion capture submission is incomplete");
            }
        }
    }
}
