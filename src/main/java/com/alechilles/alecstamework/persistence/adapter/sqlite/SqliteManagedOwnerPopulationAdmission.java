package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
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

/** Resolves managed evidence for one positive owner or owner-world transfer. */
final class SqliteManagedOwnerPopulationAdmission {
    private final SqliteOperationReader operations;
    private final SqliteLifecycleAdmissionBinding gateway;
    private final SqliteLifecycleAdmissionSourceReader sources;

    SqliteManagedOwnerPopulationAdmission(
            @Nonnull SqliteOperationReader operations,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sources
    ) {
        if (operations == null || gateway == null || sources == null) {
            throw new IllegalArgumentException(
                    "Managed owner transition dependencies are required"
            );
        }
        this.operations = operations;
        this.gateway = gateway;
        this.sources = sources;
    }

    @Nonnull
    CompletionStage<OwnerPopulationTransitionRequest> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull OwnerPopulationTransitionRequest requested
    ) {
        return operations.findByIdempotency(
                OwnerPopulationTransitionDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<OwnerPopulationTransitionRequest> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest requested,
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
            return failed(
                    failed.failure().cause(),
                    "owner_population_admission_read_failed"
            );
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
                return failed(
                        failed.failure().cause(),
                        "owner_population_admission_read_failed"
                );
            }
            if (requested.admissionEvidence() != null) {
                return failed(
                        null,
                        "lifecycle-admission-evidence-requires-existing-operation"
                );
            }
            return authorize(operationId, requested);
        });
    }

    private CompletionStage<OwnerPopulationTransitionRequest> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest requested
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !OwnerPopulationTransitionDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return failed(
                    null,
                    "owner_population_replay_operation_identity_mismatch"
            );
        }
        try {
            OwnerPopulationTransitionRequest durable =
                    OwnerPopulationTransitionDefinition.INSTANCE.decode(
                            model.operation().payloadJson()
                    );
            OwnerPopulationTransitionRequest comparable =
                    requested.admissionEvidence() == null
                            ? withoutEvidence(durable) : durable;
            if (!comparable.equals(requested)) {
                return failed(
                        null, "owner_population_replay_payload_conflict"
                );
            }
            return CompletableFuture.completedFuture(durable);
        } catch (RuntimeException invalid) {
            return failed(
                    invalid, "owner_population_replay_payload_invalid"
            );
        }
    }

    private CompletionStage<OwnerPopulationTransitionRequest> authorize(
            OperationId operationId,
            OwnerPopulationTransitionRequest requested
    ) {
        if (requested.targetOwnerWorldKey() == null) {
            return failed(null, "owner_population_target_world_required");
        }
        if (gateway.gateway()
                instanceof PersistenceLifecycleAdmissionGateway.Unbound) {
            return failed(null, "owner_population_lifecycle_admission_unbound");
        }
        return sources.findByProfile(requested.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed(null, "owner_population_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return failed(
                        failed.failure().cause(),
                        "owner_population_source_read_failed"
                );
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            CompanionLifecycle lifecycle = source.lifecycle();
            if (!lifecycle.revision().equals(
                    requested.expectedLifecycleRevision()
            )
                    || !Objects.equals(
                    lifecycle.ownerId(), requested.expectedOwnerId()
            )
                    || !Objects.equals(
                    lifecycle.ownerWorldKey(),
                    requested.expectedOwnerWorldKey()
            )
                    || lifecycle.activeOperationId() != null
                    || lifecycle.quarantined()) {
                return failed(
                        null, "owner_population_canonical_source_mismatch"
                );
            }
            return gateway.authorize(admissionRequest(
                    operationId,
                    requested,
                    lifecycle,
                    source.canonicalRoleId()
            )).thenApply(evidence -> attach(
                    operationId, requested, source, evidence
            ));
        });
    }

    private LifecycleAdmissionRequest admissionRequest(
            OperationId operationId,
            OwnerPopulationTransitionRequest requested,
            CompanionLifecycle source,
            String roleId
    ) {
        PopulationAdmissionOperation operation = operation(requested);
        PopulationAdmissionLocation sourceLocation =
                requested.expectedOwnerWorldKey() == null
                        ? null : location(requested.expectedOwnerWorldKey());
        PopulationAdmissionRequest base = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        requested.profileId().toString(), null, null
                ),
                operation == PopulationAdmissionOperation.NEW_OWNERSHIP
                        ? null : currentNpc(source),
                source.revision().value(),
                requested.expectedOwnerId() == null
                        ? null : requested.expectedOwnerId().value(),
                requested.targetOwnerId().value(),
                sourceLocation,
                location(requested.targetOwnerWorldKey()),
                operation,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                publicState(source.state())
        );
        PopulationAdmissionRequestV2 managed =
                new PopulationAdmissionRequestV2(
                        base, roleId, requested.targetOwnerWorldKey()
                );
        return LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                roleId,
                managed,
                source,
                source.state(),
                source.state(),
                source.ownerId(),
                source.ownerWorldKey()
        );
    }

    private OwnerPopulationTransitionRequest attach(
            OperationId operationId,
            OwnerPopulationTransitionRequest requested,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
            LifecycleAdmissionEvidence evidence
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
        CompanionLifecycle lifecycle = source.lifecycle();
        if (payload == null
                || !payload.profileId().equals(lifecycle.profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(), lifecycle.revision()
        )
                || payload.sourceLifecycle() != lifecycle.state()
                || payload.targetLifecycle() != lifecycle.state()
                || !Objects.equals(
                payload.sourceOwnerId(), lifecycle.ownerId()
        )
                || !Objects.equals(
                payload.sourceWorldKey(), lifecycle.ownerWorldKey()
        )
                || !Objects.equals(
                payload.ownerId(), requested.targetOwnerId()
        )
                || !Objects.equals(
                payload.ownerWorldKey(), requested.targetOwnerWorldKey()
        )) {
            throw new IllegalStateException(
                    "owner_population_admission_canonical_evidence_mismatch"
            );
        }
        PopulationDomainConvergencePlan convergence =
                PopulationDomainConvergencePlanner.plan(
                        lifecycle.profileId(),
                        lifecycle.revision(),
                        lifecycle.ownerId(),
                        lifecycle.ownerWorldKey(),
                        lifecycle.state(),
                        requested.targetOwnerId(),
                        requested.targetOwnerWorldKey(),
                        lifecycle.state(),
                        source.committedDomainRows(),
                        payload.reservations(operationId)
                );
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(convergence)) {
            throw new IllegalStateException(
                    "owner_population_admission_convergence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(
                LifecycleAdmissionEvidence.managed(
                        payload, evidence.composition(), convergence
                )
        );
    }

    private static PopulationAdmissionOperation operation(
            OwnerPopulationTransitionRequest request
    ) {
        if (request.expectedOwnerId() == null) {
            return PopulationAdmissionOperation.NEW_OWNERSHIP;
        }
        return request.expectedOwnerId().equals(request.targetOwnerId())
                ? PopulationAdmissionOperation.REHOME
                : PopulationAdmissionOperation.OWNER_TRANSFER;
    }

    private static PopulationCompanionLifecycle publicState(
            LifecycleState state
    ) {
        return state == LifecycleState.UNRESOLVED
                ? PopulationCompanionLifecycle.UNKNOWN_DORMANT
                : PopulationCompanionLifecycle.valueOf(state.name());
    }

    private static UUID currentNpc(CompanionLifecycle source) {
        String key = source.location().key();
        try {
            return key == null
                    ? source.profileId().value() : UUID.fromString(key);
        } catch (IllegalArgumentException invalid) {
            return UUID.nameUUIDFromBytes(
                    key.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static PopulationAdmissionLocation location(String world) {
        return new PopulationAdmissionLocation(world, 0, 0);
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    private static OwnerPopulationTransitionRequest withoutEvidence(
            OwnerPopulationTransitionRequest request
    ) {
        return new OwnerPopulationTransitionRequest(
                request.profileId(),
                request.expectedLifecycleRevision(),
                request.expectedOwnerId(),
                request.expectedOwnerWorldKey(),
                request.targetOwnerId(),
                request.targetOwnerWorldKey(),
                request.globalLimit(),
                request.perWorldLimit(),
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
