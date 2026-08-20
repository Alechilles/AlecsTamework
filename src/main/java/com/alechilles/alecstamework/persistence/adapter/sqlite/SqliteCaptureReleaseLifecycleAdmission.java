package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Reads and authors exact managed evidence for captured-artifact release. */
final class SqliteCaptureReleaseLifecycleAdmission {
    private final SqliteOperationReader reader;
    private final SqliteLifecycleAdmissionBinding gateway;
    @Nonnull
    private final SqliteLifecycleAdmissionSourceReader sourceReader;

    SqliteCaptureReleaseLifecycleAdmission(
            @Nonnull SqliteOperationReader reader,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sourceReader
    ) {
        if (reader == null || gateway == null || sourceReader == null) {
            throw new IllegalArgumentException(
                    "Capture release admission dependencies are required"
            );
        }
        this.reader = reader;
        this.gateway = gateway;
        this.sourceReader = sourceReader;
    }

    @Nonnull
    CompletionStage<ResolvedRelease> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureReleaseRequest requested
    ) {
        return reader.findByIdempotency(
                CompanionCaptureReleaseDefinition.KIND,
                idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<ResolvedRelease> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureReleaseRequest requested,
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
                            "capture_release_admission_read_failed"
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
                                "capture_release_admission_read_failed"
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
                    payload -> new ResolvedRelease(operationId, payload)
            );
        });
    }

    private CompletionStage<ResolvedRelease> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !CompanionCaptureReleaseDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "capture_release_replay_operation_identity_mismatch"
                    )
            );
        }
        try {
            return CompletableFuture.completedFuture(
                    new ResolvedRelease(
                            model.operation().operationId(),
                            CompanionCaptureReleaseDefinition.INSTANCE.decode(
                                    model.operation().payloadJson()
                            )
                    )
            );
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<CompanionCaptureReleaseRequest> authorize(
            CompanionCaptureReleaseRequest requested,
            OperationId operationId
    ) {
        return sourceReader.findForRelease(requested).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed("capture_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return CompletableFuture.failedFuture(
                        failed.failure().cause() == null
                                ? new IllegalStateException("capture_source_read_failed")
                                : failed.failure().cause()
                );
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            return authorizeCanonical(requested, operationId, source);
        });
    }

    private CompletionStage<CompanionCaptureReleaseRequest> authorizeCanonical(
            CompanionCaptureReleaseRequest requested,
            OperationId operationId,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel sourceModel
    ) {
        CompanionLifecycle source = sourceModel.lifecycle();
        requireCapturedSource(requested, source);
        if (source.ownerId() != null && requested.ownerAssignment() != null) {
            return failed("capture_release_owner_assignment_conflict");
        }
        OwnerId targetOwner = source.ownerId() == null
                ? requested.ownerAssignment() : source.ownerId();
        String targetWorld = targetOwner == null
                ? null : requested.targetWorldKey();
        String roleId = sourceModel.canonicalRoleId();
        PopulationAdmissionOperation operation = targetOwner == null
                ? PopulationAdmissionOperation.LIFECYCLE_CHANGE
                : PopulationAdmissionOperation.RESTORE;
        PopulationAdmissionLocation destination = new PopulationAdmissionLocation(
                requested.targetWorldKey(), 0, 0
        );
        PopulationAdmissionRequestV2 candidate = new PopulationAdmissionRequestV2(
                new PopulationAdmissionRequest(
                        new PopulationAdmissionIdentity(
                                requested.profileId().toString(), null, null
                        ),
                        targetOwner == null ? requested.sourceAlias().value() : null,
                        source.revision().value(),
                        source.ownerId() == null ? null : source.ownerId().value(),
                        targetOwner == null ? null : targetOwner.value(),
                        source.ownerId() == null ? null
                                : new PopulationAdmissionLocation(
                                source.ownerWorldKey(), 0, 0
                        ),
                        destination,
                        operation,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        PopulationCompanionLifecycle.ACTIVE
                ),
                roleId,
                requested.targetWorldKey()
        );
        LifecycleAdmissionRequest admission = LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                roleId,
                candidate,
                source,
                source.state(),
                LifecycleState.ACTIVE,
                source.ownerId(),
                source.ownerWorldKey()
        );
        return gateway.authorize(admission).thenApply(evidence -> attachConvergence(
                requested, evidence, sourceModel, targetOwner, targetWorld,
                operationId
        ));
    }

    private void requireCapturedSource(
            CompanionCaptureReleaseRequest release,
            CompanionLifecycle source
    ) {
        String snapshotId = (release.modernRecovery() == null
                ? release.sourceSnapshot()
                : release.modernRecovery().supersededSnapshot())
                .snapshotId().toString();
        if (!source.profileId().equals(release.profileId())
                || source.revision().value()
                != release.expectedLifecycleRevision().value()
                || source.state() != LifecycleState.CAPTURED
                || source.activeOperationId() != null
                || source.quarantined()
                || !source.location().equals(LifecycleLocation.keyed(
                LifecycleLocationKind.CAPTURE_ITEM, snapshotId
        ))) {
            throw new IllegalStateException("capture_release_canonical_source_mismatch");
        }
    }

    private static <T> CompletionStage<T> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    static OwnerId sourceOwner(CompanionCaptureReleaseRequest release) {
        LifecycleAdmissionEvidence evidence = release.admissionEvidence();
        return evidence != null
                && evidence.status() == LifecycleAdmissionEvidence.Status.MANAGED
                && evidence.payload() != null
                ? evidence.payload().sourceOwnerId()
                : null;
    }

    private static CompanionCaptureReleaseRequest attachConvergence(
            CompanionCaptureReleaseRequest requested,
            LifecycleAdmissionEvidence evidence,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            OwnerId targetOwner,
            String targetWorld,
            OperationId operationId
    ) {
        if (evidence == null) {
            throw new IllegalStateException("Lifecycle admission returned no evidence");
        }
        if (evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return requested.withAdmissionEvidence(evidence);
        }
        var payload = evidence.payload();
        if (payload == null
                || !payload.profileId().equals(requested.profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(), source.lifecycle().revision()
        )
                || payload.sourceLifecycle() != source.lifecycle().state()
                || !Objects.equals(payload.sourceOwnerId(), source.lifecycle().ownerId())
                || !Objects.equals(
                payload.sourceWorldKey(), source.lifecycle().ownerWorldKey()
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.ownerId(), targetOwner)
                || !Objects.equals(payload.ownerWorldKey(), targetWorld)) {
            throw new IllegalStateException(
                    "lifecycle_admission_canonical_evidence_mismatch"
            );
        }
        PopulationDomainConvergencePlan plan = PopulationDomainConvergencePlanner.plan(
                requested.profileId(),
                source.lifecycle().revision(),
                source.lifecycle().ownerId(),
                source.lifecycle().ownerWorldKey(),
                source.lifecycle().state(),
                targetOwner,
                targetWorld,
                LifecycleState.ACTIVE,
                source.committedDomainRows(),
                payload.reservations(operationId)
        );
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(plan)) {
            throw new IllegalStateException(
                    "lifecycle_admission_convergence_evidence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(LifecycleAdmissionEvidence.managed(
                payload, evidence.composition(), plan
        ));
    }

    @Nonnull
    static List<OperationScope> participantScopes(
            CompanionCaptureReleaseRequest release
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(release.profileId()));
        if (release.ownerAssignment() != null) {
            scopes.add(OperationScope.owner(release.ownerAssignment()));
        }
        OwnerId sourceOwner = sourceOwner(release);
        if (sourceOwner != null) {
            scopes.add(OperationScope.owner(sourceOwner));
        }
        return List.copyOf(scopes);
    }

    @Nonnull
    static List<OperationScope> containmentScopes(
            OperationEnvelope operation,
            CompanionCaptureReleaseRequest release
    ) {
        OwnerId owner = release.ownerAssignment() == null
                ? sourceOwner(release) : release.ownerAssignment();
        return owner == null
                ? List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(release.profileId())
                )
                : List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(release.profileId()),
                        OperationScope.owner(owner)
                );
    }

    @Nonnull
    static CompletionStage<OperationWorkflowResult> releaseProjectionHold(
            CompanionCaptureReleaseLiveBoundary liveBoundary,
            CompanionCaptureReleaseRequest release,
            OperationWorkflowResult result
    ) {
        if (result.status() != OperationWorkflowResult.Status.PUBLISHED
                || result.operation() == null) {
            return CompletableFuture.completedFuture(result);
        }
        CompletionStage<Void> releaseStage;
        try {
            releaseStage = liveBoundary.releaseProjectionHold(
                    release, result.operation()
            );
        } catch (Throwable ignored) {
            return CompletableFuture.completedFuture(result);
        }
        if (releaseStage == null) {
            return CompletableFuture.completedFuture(result);
        }
        return releaseStage.handle((ignored, failure) -> result);
    }

    static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value().toString()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    record ResolvedRelease(
            @Nonnull OperationId operationId,
            @Nonnull CompanionCaptureReleaseRequest payload
    ) {
    }
}
