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
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Reads and authors exact managed evidence for captured-artifact release. */
final class SqliteCaptureReleaseLifecycleAdmission {
    private final SqliteOperationReader reader;
    private final SqliteLifecycleAdmissionBinding gateway;

    SqliteCaptureReleaseLifecycleAdmission(
            @Nonnull SqliteOperationReader reader,
            @Nonnull SqliteLifecycleAdmissionBinding gateway
    ) {
        if (reader == null || gateway == null) {
            throw new IllegalArgumentException(
                    "Capture release admission dependencies are required"
            );
        }
        this.reader = reader;
        this.gateway = gateway;
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
        CompanionLifecycle source = sourceLifecycle(requested);
        OwnerId targetOwner = requested.ownerAssignment() == null
                ? source.ownerId() : requested.ownerAssignment();
        String roleId = artifactRole(requested);
        PopulationAdmissionRequestV2 candidate = new PopulationAdmissionRequestV2(
                new PopulationAdmissionRequest(
                        new PopulationAdmissionIdentity(
                                requested.profileId().toString(), null, null
                        ),
                        requested.sourceAlias().value(),
                        requested.expectedLifecycleRevision().value(),
                        source.ownerId() == null
                                ? null : source.ownerId().value(),
                        targetOwner == null ? null : targetOwner.value(),
                        new PopulationAdmissionLocation(
                                requested.source().worldKey(), 0, 0
                        ),
                        new PopulationAdmissionLocation(
                                requested.targetWorldKey(), 0, 0
                        ),
                        targetOwner == null
                                ? PopulationAdmissionOperation.LIFECYCLE_CHANGE
                                : PopulationAdmissionOperation.RESTORE,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        PopulationCompanionLifecycle.ACTIVE
                ),
                roleId,
                requested.targetWorldKey()
        );
        LifecycleAdmissionRequest request = LifecycleAdmissionRequest.managed(
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
        return gateway.authorize(request).thenApply(
                requested::withAdmissionEvidence
        );
    }

    private CompanionLifecycle sourceLifecycle(
            CompanionCaptureReleaseRequest release
    ) {
        String snapshotId = (release.modernRecovery() == null
                ? release.sourceSnapshot()
                : release.modernRecovery().supersededSnapshot())
                .snapshotId().toString();
        OwnerId owner = sourceOwner(release);
        return new CompanionLifecycle(
                release.profileId(),
                owner,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM, snapshotId
                ),
                release.expectedLifecycleRevision(),
                null,
                release.requestedAtMs(),
                release.modernRecovery() == null
                        ? ReconciliationGeneration.INITIAL
                        : release.modernRecovery().reconciliationGeneration(),
                null,
                owner == null ? null : release.source().worldKey()
        );
    }

    static OwnerId sourceOwner(CompanionCaptureReleaseRequest release) {
        BsonValue owner = BsonDocument.parse(
                release.source().sourceArtifact().metadataExtendedJson()
        ).get(TameworkMetadataKeys.OWNER_UUID);
        return owner == null || owner.isNull()
                ? null : OwnerId.parse(owner.asString().getValue());
    }

    private String artifactRole(CompanionCaptureReleaseRequest release) {
        BsonValue role = BsonDocument.parse(
                release.source().sourceArtifact().metadataExtendedJson()
        ).get(TameworkMetadataKeys.CAPTURE_ROLE_ID);
        return role != null && role.isString()
                && !role.asString().getValue().isBlank()
                ? role.asString().getValue()
                : "legacy:" + release.sourceAlias();
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
        return release.ownerAssignment() == null
                ? List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(release.profileId())
                )
                : List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(release.profileId()),
                        OperationScope.owner(release.ownerAssignment())
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
