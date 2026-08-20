package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Resolves managed evidence for a paid dead-companion revival. */
final class SqliteManagedPaidRevivalAdmission {
    private static final int CHUNK_SIZE = 32;

    private final SqliteOperationReader operations;
    private final SqliteLifecycleAdmissionBinding gateway;
    private final SqliteLifecycleAdmissionSourceReader sources;

    SqliteManagedPaidRevivalAdmission(
            @Nonnull SqliteOperationReader operations,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sources
    ) {
        if (operations == null || gateway == null || sources == null) {
            throw new IllegalArgumentException(
                    "Managed paid revival admission dependencies are required"
            );
        }
        this.operations = operations;
        this.gateway = gateway;
        this.sources = sources;
    }

    @Nonnull
    CompletionStage<PaidRevivalRequest> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull PaidRevivalRequest requested
    ) {
        return operations.findByIdempotency(
                PaidRevivalDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<PaidRevivalRequest> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            PaidRevivalRequest requested,
            PersistenceReadResult<SqliteOperationReader.OperationReadModel> read
    ) {
        if (read instanceof PersistenceReadResult.Found<
                SqliteOperationReader.OperationReadModel> found) {
            return decodeExisting(
                    found.value(), operationId, idempotencyKey, requested
            );
        }
        if (read instanceof PersistenceReadResult.Failed<
                SqliteOperationReader.OperationReadModel> failed) {
            return failed(failed.failure().cause(),
                    "paid_revival_admission_read_failed");
        }
        return operations.find(operationId).thenCompose(byId -> {
            if (byId instanceof PersistenceReadResult.Found<
                    SqliteOperationReader.OperationReadModel> found) {
                return decodeExisting(
                        found.value(), operationId, idempotencyKey, requested
                );
            }
            if (byId instanceof PersistenceReadResult.Failed<
                    SqliteOperationReader.OperationReadModel> failed) {
                return failed(failed.failure().cause(),
                        "paid_revival_admission_read_failed");
            }
            if (requested.admissionEvidence() != null) {
                return failed(null,
                        "lifecycle-admission-evidence-requires-existing-operation");
            }
            return authorize(operationId, requested);
        });
    }

    private CompletionStage<PaidRevivalRequest> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            PaidRevivalRequest requested
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !PaidRevivalDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return failed(null,
                    "paid_revival_replay_operation_identity_mismatch");
        }
        try {
            PaidRevivalRequest durable = PaidRevivalDefinition.INSTANCE.decode(
                    model.operation().payloadJson()
            );
            if (!sameCallerRequest(requested, durable)) {
                return failed(null, "paid_revival_replay_payload_conflict");
            }
            return CompletableFuture.completedFuture(durable);
        } catch (RuntimeException invalid) {
            return failed(invalid, "paid_revival_replay_payload_invalid");
        }
    }

    private CompletionStage<PaidRevivalRequest> authorize(
            OperationId operationId,
            PaidRevivalRequest requested
    ) {
        if (gateway.gateway()
                instanceof PersistenceLifecycleAdmissionGateway.Unbound) {
            return failed(null, "paid_revival_lifecycle_admission_unbound");
        }
        CompanionLifecycle expected = requested.groupAdmission().before();
        CompanionLifecycle target = requested.groupAdmission().after();
        if (expected.state() != LifecycleState.DEAD_REVIVABLE) {
            return CompletableFuture.completedFuture(requested);
        }
        return sources.findByProfile(expected.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed(null, "paid_revival_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return failed(failed.failure().cause(),
                        "paid_revival_source_read_failed");
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            if (!canonicalSourceMatches(requested, source, expected, target)) {
                return failed(null, "paid_revival_canonical_source_mismatch");
            }
            return gateway.authorize(request(
                    operationId,
                    source.lifecycle(),
                    source.canonicalRoleId(),
                    target,
                    requested
            )).thenApply(evidence -> attach(
                    requested, evidence, source, target, operationId
            ));
        });
    }

    private boolean canonicalSourceMatches(
            PaidRevivalRequest requested,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            CompanionLifecycle expected,
            CompanionLifecycle target
    ) {
        return source.lifecycle().equals(expected)
                && expected.state() == LifecycleState.DEAD_REVIVABLE
                && target.state() == LifecycleState.ACTIVE
                && target.location().kind() == LifecycleLocationKind.LIVE_ENTITY
                && requested.targetAlias().toString().equals(
                target.location().key()
        )
                && requested.targetWorldKey().equals(
                target.location().worldKey()
        )
                && Objects.equals(target.ownerId(), expected.ownerId())
                && Objects.equals(
                target.ownerWorldKey(), requested.targetWorldKey()
        )
                && !expected.quarantined()
                && expected.activeOperationId() == null;
    }

    private LifecycleAdmissionRequest request(
            OperationId operationId,
            CompanionLifecycle source,
            String roleId,
            CompanionLifecycle target,
            PaidRevivalRequest requested
    ) {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        source.profileId().toString(), null, null
                ),
                targetNpcUuid(target.location().key()),
                source.revision().value(),
                source.ownerId() == null ? null : source.ownerId().value(),
                target.ownerId() == null ? null : target.ownerId().value(),
                null,
                new PopulationAdmissionLocation(
                        requested.targetWorldKey(),
                        chunkCoordinate(requested.placement().x()),
                        chunkCoordinate(requested.placement().z())
                ),
                PopulationAdmissionOperation.LIFECYCLE_CHANGE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        PopulationAdmissionRequestV2 managed =
                new PopulationAdmissionRequestV2(
                        admission,
                        roleId,
                        requested.targetWorldKey()
                );
        return LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                roleId,
                managed,
                source,
                source.state(),
                LifecycleState.ACTIVE,
                source.ownerId(),
                source.ownerWorldKey()
        );
    }

    private PaidRevivalRequest attach(
            PaidRevivalRequest requested,
            LifecycleAdmissionEvidence evidence,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            CompanionLifecycle target,
            OperationId operationId
    ) {
        if (evidence == null) {
            throw new IllegalStateException(
                    "Lifecycle admission returned no evidence"
            );
        }
        if (evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return requested.withAdmissionEvidence(evidence);
        }
        var payload = evidence.payload();
        if (payload == null
                || !payload.profileId().equals(source.lifecycle().profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(), source.lifecycle().revision()
        )
                || payload.sourceLifecycle() != source.lifecycle().state()
                || !Objects.equals(
                payload.sourceOwnerId(), source.lifecycle().ownerId()
        )
                || !Objects.equals(
                payload.sourceWorldKey(), source.lifecycle().ownerWorldKey()
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.ownerId(), target.ownerId())
                || !Objects.equals(
                payload.ownerWorldKey(), target.ownerWorldKey()
        )) {
            throw new IllegalStateException(
                    "paid_revival_admission_canonical_evidence_mismatch"
            );
        }
        PopulationDomainConvergencePlan plan =
                PopulationDomainConvergencePlanner.plan(
                        source.lifecycle().profileId(),
                        source.lifecycle().revision(),
                        source.lifecycle().ownerId(),
                        source.lifecycle().ownerWorldKey(),
                        source.lifecycle().state(),
                        target.ownerId(),
                        target.ownerWorldKey(),
                        LifecycleState.ACTIVE,
                        source.committedDomainRows(),
                        payload.reservations(operationId)
                );
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(plan)) {
            throw new IllegalStateException(
                    "paid_revival_admission_convergence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(
                LifecycleAdmissionEvidence.managed(
                        payload, evidence.composition(), plan
                )
        );
    }

    private static boolean sameCallerRequest(
            PaidRevivalRequest requested,
            PaidRevivalRequest durable
    ) {
        if (requested.admissionEvidence() != null) {
            return requested.equals(durable);
        }
        return requested.equals(withoutEvidence(durable));
    }

    private static PaidRevivalRequest withoutEvidence(
            PaidRevivalRequest request
    ) {
        return new PaidRevivalRequest(
                request.callerNamespace(),
                request.callerIdempotencyKey(),
                request.familyKey(),
                request.slotId(),
                request.expectedMembershipRevision(),
                request.expectedProfileRevision(),
                request.groupAdmission(),
                request.sourceSnapshot(),
                request.projection(),
                request.targetAlias(),
                request.placement(),
                request.configId(),
                request.configRevision(),
                request.exactCost(),
                request.reservations(),
                request.chargeReceiptKey(),
                request.spawnReceiptKey(),
                request.timedActivation(),
                request.requestedAtMs()
        );
    }

    private static UUID targetNpcUuid(String locationKey) {
        try {
            return UUID.fromString(locationKey);
        } catch (IllegalArgumentException invalid) {
            return UUID.nameUUIDFromBytes(
                    locationKey.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static int chunkCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CHUNK_SIZE);
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    private static <T> CompletionStage<T> failed(
            Throwable cause,
            String fallback
    ) {
        Throwable failure = cause == null
                ? new IllegalStateException(fallback) : cause;
        return CompletableFuture.failedFuture(failure);
    }
}
