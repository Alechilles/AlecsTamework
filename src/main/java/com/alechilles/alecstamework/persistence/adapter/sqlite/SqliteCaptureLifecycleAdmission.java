package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reads and authors exact managed evidence for companion capture. */
final class SqliteCaptureLifecycleAdmission {
    private final SqliteOperationReader reader;
    private final SqliteLifecycleAdmissionBinding gateway;
    @Nullable
    private final SqliteLifecycleAdmissionSourceReader sourceReader;

    SqliteCaptureLifecycleAdmission(
            @Nonnull SqliteOperationReader reader,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nullable SqliteLifecycleAdmissionSourceReader sourceReader
    ) {
        if (reader == null || gateway == null) {
            throw new IllegalArgumentException(
                    "Capture admission dependencies are required"
            );
        }
        this.reader = reader;
        this.gateway = gateway;
        this.sourceReader = sourceReader;
    }

    boolean supports(CompanionCaptureRequest request) {
        return request.tameAndCommandLink()
                || request.capturedItem() && sourceReader != null;
    }

    @Nonnull
    CompletionStage<ResolvedCapture> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureRequest requested
    ) {
        return reader.findByIdempotency(
                CompanionCaptureDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<ResolvedCapture> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCaptureRequest requested,
            PersistenceReadResult<SqliteOperationReader.OperationReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Found<
                SqliteOperationReader.OperationReadModel> found) {
            return decodeExisting(found.value(), operationId, idempotencyKey);
        }
        if (read instanceof PersistenceReadResult.Failed<
                SqliteOperationReader.OperationReadModel> failed) {
            return failedRead(failed);
        }
        return reader.find(operationId).thenCompose(byId -> {
            if (byId instanceof PersistenceReadResult.Found<
                    SqliteOperationReader.OperationReadModel> found) {
                return decodeExisting(found.value(), operationId, idempotencyKey);
            }
            if (byId instanceof PersistenceReadResult.Failed<
                    SqliteOperationReader.OperationReadModel> failed) {
                return failedRead(failed);
            }
            if (requested.admissionEvidence() != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "lifecycle-admission-evidence-requires-existing-operation"
                ));
            }
            return authorize(requested, operationId).thenApply(
                    payload -> new ResolvedCapture(operationId, payload)
            );
        });
    }

    private CompletionStage<ResolvedCapture> failedRead(
            PersistenceReadResult.Failed<
                    SqliteOperationReader.OperationReadModel> failed
    ) {
        return CompletableFuture.failedFuture(
                failed.failure().cause() == null
                        ? new IllegalStateException("capture_admission_read_failed")
                        : failed.failure().cause()
        );
    }

    private CompletionStage<ResolvedCapture> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !CompanionCaptureDefinition.KIND.equals(model.operation().kind())
                || !model.operation().idempotencyKey().equals(idempotencyKey)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "capture_replay_operation_identity_mismatch"
            ));
        }
        try {
            return CompletableFuture.completedFuture(new ResolvedCapture(
                    model.operation().operationId(),
                    CompanionCaptureDefinition.INSTANCE.decode(
                            model.operation().payloadJson()
                    )
            ));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<CompanionCaptureRequest> authorize(
            CompanionCaptureRequest requested,
            OperationId operationId
    ) {
        if (sourceReader == null && requested.tameAndCommandLink()) {
            return authorizeLegacyTame(requested, operationId);
        }
        if (sourceReader == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "lifecycle-admission-source-unavailable"
            ));
        }
        return sourceReader.findByProfile(requested.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "capture_source_profile_absent"
                ));
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return CompletableFuture.failedFuture(
                        failed.failure().cause() == null
                                ? new IllegalStateException("capture_source_read_failed")
                                : failed.failure().cause()
                );
            }
            var sourceModel = ((PersistenceReadResult.Found<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read).value();
            return authorizeCanonical(requested, operationId, sourceModel);
        });
    }

    private CompletionStage<CompanionCaptureRequest> authorizeCanonical(
            CompanionCaptureRequest requested,
            OperationId operationId,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel sourceModel
    ) {
        CompanionLifecycle source = sourceModel.lifecycle();
        requireCaptureSource(requested, source);
        LifecycleState targetState = requested.capturedItem()
                ? LifecycleState.CAPTURED : LifecycleState.ACTIVE;
        OwnerId targetOwner = requested.capturedItem()
                ? requested.resultingOwnerId()
                : requested.tameAndLinkEvidence().finalLifecycle().ownerId();
        String targetWorld = requested.capturedItem()
                ? targetOwner == null ? null : requested.targetWorldKey()
                : requested.tameAndLinkEvidence().finalLifecycle().ownerWorldKey();
        PopulationAdmissionOperation operation = operation(
                source, targetOwner
        );
        String targetRoleId = requested.tameAndCommandLink()
                ? requested.tameAndLinkEvidence().live().targetRoleId()
                : sourceModel.canonicalRoleId();
        requireSourceRole(requested, sourceModel.canonicalRoleId());
        PopulationAdmissionRequestV2 candidate = new PopulationAdmissionRequestV2(
                new PopulationAdmissionRequest(
                        new PopulationAdmissionIdentity(
                                requested.profileId().toString(), null, null
                        ),
                        currentNpc(source, operation),
                        source.revision().value(),
                        source.ownerId() == null ? null : source.ownerId().value(),
                        targetOwner == null ? null : targetOwner.value(),
                        new PopulationAdmissionLocation(
                                source.location().worldKey(), 0, 0
                        ),
                        new PopulationAdmissionLocation(
                                requested.targetWorldKey(), 0, 0
                        ),
                        operation,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        PopulationCompanionLifecycle.valueOf(targetState.name())
                ),
                targetRoleId,
                targetWorld == null ? source.location().worldKey() : targetWorld
        );
        LifecycleAdmissionRequest admission = LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                targetRoleId,
                candidate,
                source,
                source.state(),
                targetState,
                source.ownerId(),
                source.ownerWorldKey()
        );
        return gateway.authorize(admission).thenApply(evidence -> attachConvergence(
                requested,
                evidence,
                sourceModel,
                targetOwner,
                targetWorld,
                targetState,
                operationId
        ));
    }

    private CompletionStage<CompanionCaptureRequest> authorizeLegacyTame(
            CompanionCaptureRequest requested,
            OperationId operationId
    ) {
        var tame = requested.tameAndLinkEvidence();
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
                                tame.finalLifecycle().location().worldKey(), 0, 0
                        ),
                        PopulationAdmissionOperation.NEW_OWNERSHIP,
                        1,
                        PopulationAdmissionForcePolicy.ENFORCE,
                        PopulationCompanionLifecycle.ACTIVE
                ),
                tame.live().targetRoleId(),
                tame.finalLifecycle().location().worldKey()
        );
        LifecycleAdmissionRequest admission = LifecycleAdmissionRequest.managed(
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
        return gateway.authorize(admission).thenApply(
                requested::withAdmissionEvidence
        );
    }

    private void requireCaptureSource(
            CompanionCaptureRequest capture,
            CompanionLifecycle source
    ) {
        if (!source.profileId().equals(capture.profileId())
                || source.revision().value()
                != capture.expectedLifecycleRevision().value()
                || source.state() != LifecycleState.ACTIVE
                || source.activeOperationId() != null
                || source.quarantined()
                || source.location().kind() != LifecycleLocationKind.LIVE_ENTITY
                || !capture.targetAlias().toString().equals(source.location().key())
                || !capture.targetWorldKey().equals(source.location().worldKey())) {
            throw new IllegalStateException("capture_canonical_source_mismatch");
        }
    }

    private void requireSourceRole(
            CompanionCaptureRequest capture,
            String canonicalRoleId
    ) {
        if (capture.tameAndCommandLink()
                && !canonicalRoleId.equalsIgnoreCase(
                capture.tameAndLinkEvidence().expectedIdentity().roleId()
        )) {
            throw new IllegalStateException("capture_canonical_role_mismatch");
        }
    }

    private static PopulationAdmissionOperation operation(
            CompanionLifecycle source,
            OwnerId targetOwner
    ) {
        if (source.ownerId() == null && targetOwner != null) {
            return PopulationAdmissionOperation.NEW_OWNERSHIP;
        }
        if (source.ownerId() != null && targetOwner == null) {
            return PopulationAdmissionOperation.OWNER_CLEAR;
        }
        if (source.ownerId() != null && !source.ownerId().equals(targetOwner)) {
            return PopulationAdmissionOperation.OWNER_TRANSFER;
        }
        return PopulationAdmissionOperation.LIFECYCLE_CHANGE;
    }

    private static UUID currentNpc(
            CompanionLifecycle source,
            PopulationAdmissionOperation operation
    ) {
        return switch (operation) {
            case NEW_OWNERSHIP -> null;
            default -> UUID.fromString(source.location().key());
        };
    }

    private static CompanionCaptureRequest attachConvergence(
            CompanionCaptureRequest requested,
            LifecycleAdmissionEvidence evidence,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            OwnerId targetOwner,
            String targetWorld,
            LifecycleState targetState,
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
                || payload.targetLifecycle() != targetState
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
                targetState,
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

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value().toString()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    record ResolvedCapture(
            @Nonnull OperationId operationId,
            @Nonnull CompanionCaptureRequest payload
    ) {
    }
}
