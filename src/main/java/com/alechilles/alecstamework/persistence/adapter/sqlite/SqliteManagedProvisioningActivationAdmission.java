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
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
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

/** Resolves managed evidence for a provisioned-dormant-to-live activation. */
final class SqliteManagedProvisioningActivationAdmission {
    private static final int CHUNK_SIZE = 32;

    private final SqliteOperationReader operations;
    private final SqliteLifecycleAdmissionBinding gateway;
    private final SqliteLifecycleAdmissionSourceReader sources;

    SqliteManagedProvisioningActivationAdmission(
            @Nonnull SqliteOperationReader operations,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sources
    ) {
        if (operations == null || gateway == null || sources == null) {
            throw new IllegalArgumentException(
                    "Managed provisioning activation admission dependencies are required"
            );
        }
        this.operations = operations;
        this.gateway = gateway;
        this.sources = sources;
    }

    @Nonnull
    CompletionStage<ProvisioningActivationRequest> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull ProvisioningActivationRequest requested
    ) {
        return operations.findByIdempotency(
                ProvisioningActivationDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<ProvisioningActivationRequest> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            ProvisioningActivationRequest requested,
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
                    "provisioning_activation_admission_read_failed");
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
                        "provisioning_activation_admission_read_failed");
            }
            if (requested.admissionEvidence() != null) {
                return failed(null,
                        "lifecycle-admission-evidence-requires-existing-operation");
            }
            return authorize(operationId, requested);
        });
    }

    private CompletionStage<ProvisioningActivationRequest> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            ProvisioningActivationRequest requested
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !ProvisioningActivationDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return failed(null,
                    "provisioning_activation_replay_operation_identity_mismatch");
        }
        try {
            ProvisioningActivationRequest persisted =
                    ProvisioningActivationDefinition.INSTANCE.decode(
                            model.operation().payloadJson()
                    );
            ProvisioningActivationRequest comparable =
                    requested.admissionEvidence() == null
                            ? withoutEvidence(persisted) : persisted;
            if (!comparable.equals(requested)) {
                return failed(null,
                        "provisioning_activation_replay_payload_conflict");
            }
            return CompletableFuture.completedFuture(persisted);
        } catch (RuntimeException invalid) {
            return failed(invalid,
                    "provisioning_activation_replay_payload_invalid");
        }
    }

    private CompletionStage<ProvisioningActivationRequest> authorize(
            OperationId operationId,
            ProvisioningActivationRequest requested
    ) {
        if (gateway.gateway()
                instanceof com.alechilles.alecstamework.persistence.runtime
                .PersistenceLifecycleAdmissionGateway.Unbound) {
            return failed(null, "provisioning_activation_lifecycle_admission_unbound");
        }
        CompanionLifecycle expected = requested.groupAdmission().before();
        CompanionLifecycle target = requested.groupAdmission().after();
        return sources.findByProfile(expected.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed(null, "provisioning_activation_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return failed(failed.failure().cause(),
                        "provisioning_activation_source_read_failed");
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            if (!source.lifecycle().equals(expected)
                    || !requested.expectedRoleId().equals(
                    source.canonicalRoleId()
            )
                    || target.state() != LifecycleState.ACTIVE
                    || target.location().kind() != LifecycleLocationKind.LIVE_ENTITY
                    || !requested.targetAlias().toString().equals(
                    target.location().key()
            )
                    || !requested.placement().worldKey().equals(
                    target.location().worldKey()
            )
                    || !Objects.equals(
                    target.ownerId(), source.lifecycle().ownerId()
            )
                    || !requested.placement().worldKey().equals(
                    target.ownerWorldKey()
            )) {
                return failed(null,
                        "provisioning_activation_canonical_source_mismatch");
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

    private LifecycleAdmissionRequest request(
            OperationId operationId,
            CompanionLifecycle source,
            String roleId,
            CompanionLifecycle target,
            ProvisioningActivationRequest requested
    ) {
        var placement = requested.placement();
        String targetWorld = placement.worldKey();
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        source.profileId().toString(), null, null
                ),
                targetNpcUuid(requested.targetAlias().toString()),
                source.revision().value(),
                source.ownerId() == null ? null : source.ownerId().value(),
                target.ownerId() == null ? null : target.ownerId().value(),
                null,
                new PopulationAdmissionLocation(
                        targetWorld,
                        chunkCoordinate(placement.x()),
                        chunkCoordinate(placement.z())
                ),
                PopulationAdmissionOperation.LIFECYCLE_CHANGE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        PopulationAdmissionRequestV2 managed =
                new PopulationAdmissionRequestV2(
                        admission, roleId, targetWorld
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

    private ProvisioningActivationRequest attach(
            ProvisioningActivationRequest requested,
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
                || !Objects.equals(payload.ownerWorldKey(), target.ownerWorldKey())) {
            throw new IllegalStateException(
                    "provisioning_activation_admission_canonical_evidence_mismatch"
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
                    "provisioning_activation_admission_convergence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(
                LifecycleAdmissionEvidence.managed(
                        payload, evidence.composition(), plan
                )
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

    private static ProvisioningActivationRequest withoutEvidence(
            ProvisioningActivationRequest request
    ) {
        return new ProvisioningActivationRequest(
                request.origin(),
                request.groupAdmission(),
                request.targetAlias(),
                request.expectedRoleId(),
                request.fullState(),
                request.placement(),
                request.spawnReceiptKey(),
                request.timedActivation(),
                request.requestedAtMs()
        );
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
