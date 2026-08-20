package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
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

/** Resolves managed evidence only for a cross-owner-world coop release. */
final class SqliteManagedCoopReleaseAdmission {
    private static final int CHUNK_SIZE = 32;

    private final SqliteOperationReader operations;
    private final SqliteLifecycleAdmissionBinding gateway;
    private final SqliteLifecycleAdmissionSourceReader sources;

    SqliteManagedCoopReleaseAdmission(
            @Nonnull SqliteOperationReader operations,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sources
    ) {
        if (operations == null || gateway == null || sources == null) {
            throw new IllegalArgumentException(
                    "Managed coop release admission dependencies are required"
            );
        }
        this.operations = operations;
        this.gateway = gateway;
        this.sources = sources;
    }

    @Nonnull
    CompletionStage<CompanionCoopReleaseRequest> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCoopReleaseRequest requested
    ) {
        return operations.findByIdempotency(
                CompanionCoopReleaseDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<CompanionCoopReleaseRequest> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopReleaseRequest requested,
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
                    "coop_release_admission_read_failed");
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
                        "coop_release_admission_read_failed");
            }
            if (requested.admissionEvidence() != null) {
                return failed(null,
                        "lifecycle-admission-evidence-requires-existing-operation");
            }
            return authorize(operationId, requested);
        });
    }

    private CompletionStage<CompanionCoopReleaseRequest> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopReleaseRequest requested
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !CompanionCoopReleaseDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return failed(null,
                    "coop_release_replay_operation_identity_mismatch");
        }
        try {
            CompanionCoopReleaseRequest persisted =
                    CompanionCoopReleaseDefinition.INSTANCE.decode(
                            model.operation().payloadJson()
                    );
            CompanionCoopReleaseRequest comparable =
                    requested.admissionEvidence() == null
                            ? withoutEvidence(persisted) : persisted;
            if (!comparable.equals(requested)) {
                return failed(null, "coop_release_replay_payload_conflict");
            }
            return CompletableFuture.completedFuture(persisted);
        } catch (RuntimeException invalid) {
            return failed(invalid, "coop_release_replay_payload_invalid");
        }
    }

    private CompletionStage<CompanionCoopReleaseRequest> authorize(
            OperationId operationId,
            CompanionCoopReleaseRequest requested
    ) {
        return sources.findByProfile(requested.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed(null, "coop_release_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return failed(failed.failure().cause(),
                        "coop_release_source_read_failed");
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            CompanionLifecycle lifecycle = source.lifecycle();
            if (!canonicalSourceMatches(requested, lifecycle)) {
                return failed(null, "coop_release_canonical_source_mismatch");
            }
            String targetOwnerWorld = targetOwnerWorld(requested, lifecycle);
            if (Objects.equals(lifecycle.ownerWorldKey(), targetOwnerWorld)) {
                return CompletableFuture.completedFuture(
                        requested.withAdmissionEvidence(
                                LifecycleAdmissionEvidence.neutral()
                        )
                );
            }
            if (gateway.gateway()
                    instanceof PersistenceLifecycleAdmissionGateway.Unbound) {
                return failed(null, "coop_release_lifecycle_admission_unbound");
            }
            return gateway.authorize(request(
                    operationId, requested, lifecycle, source.canonicalRoleId()
            )).thenApply(evidence -> attach(
                    requested, evidence, source, operationId
            ));
        });
    }

    private boolean canonicalSourceMatches(
            CompanionCoopReleaseRequest requested,
            CompanionLifecycle source
    ) {
        return source.profileId().equals(requested.profileId())
                && source.revision().equals(requested.expectedLifecycleRevision())
                && source.state() == LifecycleState.COOP
                && source.location().equals(LifecycleLocation.keyed(
                LifecycleLocationKind.COOP_SLOT,
                requested.sourceResidency().slotKey().toString()
        ))
                && source.activeOperationId() == null
                && !source.quarantined();
    }

    private LifecycleAdmissionRequest request(
            OperationId operationId,
            CompanionCoopReleaseRequest requested,
            CompanionLifecycle source,
            String roleId
    ) {
        String targetWorld = requested.targetWorldKey();
        OwnerId owner = source.ownerId();
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        source.profileId().toString(), null, null
                ),
                requested.targetAlias().value(),
                source.revision().value(),
                owner == null ? null : owner.value(),
                owner == null ? null : owner.value(),
                null,
                new PopulationAdmissionLocation(
                        targetWorld,
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
                        admission, roleId, targetWorld
                );
        return LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                roleId,
                managed,
                source,
                LifecycleState.COOP,
                LifecycleState.ACTIVE,
                source.ownerId(),
                source.ownerWorldKey()
        );
    }

    private CompanionCoopReleaseRequest attach(
            CompanionCoopReleaseRequest requested,
            LifecycleAdmissionEvidence evidence,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source,
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
        CompanionLifecycle lifecycle = source.lifecycle();
        OwnerId targetOwner = lifecycle.ownerId();
        String targetOwnerWorld = targetOwner == null
                ? null : requested.targetWorldKey();
        if (payload == null
                || !payload.profileId().equals(lifecycle.profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(), lifecycle.revision()
        )
                || payload.sourceLifecycle() != LifecycleState.COOP
                || !Objects.equals(payload.sourceOwnerId(), lifecycle.ownerId())
                || !Objects.equals(
                payload.sourceWorldKey(), lifecycle.ownerWorldKey()
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.ownerId(), targetOwner)
                || !Objects.equals(payload.ownerWorldKey(), targetOwnerWorld)) {
            throw new IllegalStateException(
                    "coop_release_admission_canonical_evidence_mismatch"
            );
        }
        PopulationDomainConvergencePlan plan =
                PopulationDomainConvergencePlanner.plan(
                        lifecycle.profileId(),
                        lifecycle.revision(),
                        lifecycle.ownerId(),
                        lifecycle.ownerWorldKey(),
                        lifecycle.state(),
                        targetOwner,
                        targetOwnerWorld,
                        LifecycleState.ACTIVE,
                        source.committedDomainRows(),
                        payload.reservations(operationId)
                );
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(plan)) {
            throw new IllegalStateException(
                    "coop_release_admission_convergence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(
                LifecycleAdmissionEvidence.managed(
                        payload, evidence.composition(), plan
                )
        );
    }

    private static String targetOwnerWorld(
            CompanionCoopReleaseRequest requested,
            CompanionLifecycle source
    ) {
        return source.ownerId() == null ? null : requested.targetWorldKey();
    }

    private static int chunkCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CHUNK_SIZE);
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    private static CompanionCoopReleaseRequest withoutEvidence(
            CompanionCoopReleaseRequest request
    ) {
        return new CompanionCoopReleaseRequest(
                request.profileId(),
                request.expectedLifecycleRevision(),
                request.sourceResidency(),
                request.sourceSnapshot(),
                request.targetAlias(),
                request.placement(),
                request.spawnReceiptKey(),
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
