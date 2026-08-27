package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceLifecycleAdmissionGateway;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Resolves one managed death-to-active restoration before the shared workflow prepares. */
final class SqliteFreeRestorationLifecycleAdmission {
    private static final int CHUNK_SIZE = 32;

    private final SqliteOperationReader operations;
    private final SqliteLifecycleAdmissionBinding gateway;
    private final SqliteLifecycleAdmissionSourceReader sources;

    SqliteFreeRestorationLifecycleAdmission(
            @Nonnull SqliteOperationReader operations,
            @Nonnull SqliteLifecycleAdmissionBinding gateway,
            @Nonnull SqliteLifecycleAdmissionSourceReader sources
    ) {
        if (operations == null || gateway == null || sources == null) {
            throw new IllegalArgumentException(
                    "Free restoration admission dependencies are required"
            );
        }
        this.operations = operations;
        this.gateway = gateway;
        this.sources = sources;
    }

    @Nonnull
    CompletionStage<CompanionRestorationRequest> resolve(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionRestorationRequest requested
    ) {
        return operations.findByIdempotency(
                CompanionRestorationDefinition.KIND, idempotencyKey
        ).thenCompose(read -> resolveRead(
                operationId, idempotencyKey, requested, read
        ));
    }

    private CompletionStage<CompanionRestorationRequest> resolveRead(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionRestorationRequest requested,
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
                    "free_restoration_admission_read_failed");
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
                        "free_restoration_admission_read_failed");
            }
            if (requested.admissionEvidence() != null) {
                return failed(null,
                        "lifecycle-admission-evidence-requires-existing-operation");
            }
            return authorize(operationId, requested);
        });
    }

    private CompletionStage<CompanionRestorationRequest> decodeExisting(
            SqliteOperationReader.OperationReadModel model,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionRestorationRequest requested
    ) {
        if (!model.operation().operationId().equals(operationId)
                || !CompanionRestorationDefinition.KIND.equals(
                model.operation().kind()
        )
                || !model.operation().idempotencyKey().equals(
                idempotencyKey
        )) {
            return failed(null,
                    "free_restoration_replay_operation_identity_mismatch");
        }
        try {
            CompanionRestorationRequest persisted =
                    CompanionRestorationDefinition.INSTANCE.decode(
                            model.operation().payloadJson()
                    );
            if (!sameCallerRequest(requested, persisted)) {
                return failed(null, "free_restoration_replay_payload_conflict");
            }
            return CompletableFuture.completedFuture(persisted);
        } catch (RuntimeException invalid) {
            return failed(invalid, "free_restoration_replay_payload_invalid");
        }
    }

    private CompletionStage<CompanionRestorationRequest> authorize(
            OperationId operationId,
            CompanionRestorationRequest requested
    ) {
        if (gateway.gateway()
                instanceof PersistenceLifecycleAdmissionGateway.Unbound) {
            return failed(null, "free_restoration_lifecycle_admission_unbound");
        }
        return sources.findByProfile(requested.profileId()).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Absent<?>) {
                return failed(null, "free_restoration_source_profile_absent");
            }
            if (read instanceof PersistenceReadResult.Failed<
                    SqliteLifecycleAdmissionSourceReader.SourceReadModel> failed) {
                return failed(failed.failure().cause(),
                        "free_restoration_source_read_failed");
            }
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source =
                    ((PersistenceReadResult.Found<
                            SqliteLifecycleAdmissionSourceReader.SourceReadModel>) read)
                            .value();
            if (!canonicalSourceMatches(requested, source.lifecycle())) {
                return failed(null, "free_restoration_canonical_source_mismatch");
            }
            return gateway.authorize(request(
                    operationId, requested, source
            )).thenApply(evidence -> attach(
                    requested, evidence, source, operationId
            ));
        });
    }

    private LifecycleAdmissionRequest request(
            OperationId operationId,
            CompanionRestorationRequest requested,
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source
    ) {
        CompanionLifecycle lifecycle = source.lifecycle();
        boolean owned = lifecycle.ownerId() != null;
        boolean initializesDomains = initializesDomains(source);
        PopulationAdmissionOperation operation = owned
                ? PopulationAdmissionOperation.RESTORE
                : PopulationAdmissionOperation.LIFECYCLE_CHANGE;
        var placement = requested.placement();
        PopulationAdmissionLocation destination =
                new PopulationAdmissionLocation(
                        placement.worldKey(),
                        chunkCoordinate(placement.x()),
                        chunkCoordinate(placement.z())
                );
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        requested.profileId().toString(), null, null
                ),
                owned ? null : aliasUuid(
                        requested.projection().sourceAlias()
                ),
                lifecycle.revision().value(),
                owned ? lifecycle.ownerId().value() : null,
                owned ? lifecycle.ownerId().value() : null,
                null,
                destination,
                operation,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        PopulationAdmissionRequestV2 managed =
                new PopulationAdmissionRequestV2(
                        admission,
                        source.canonicalRoleId(),
                        requested.targetWorldKey()
                );
        return LifecycleAdmissionRequest.managed(
                operationId,
                reservationId(operationId),
                source.canonicalRoleId(),
                managed,
                lifecycle,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleState.ACTIVE,
                initializesDomains ? null : lifecycle.ownerId(),
                initializesDomains ? null : lifecycle.ownerWorldKey()
        );
    }

    private CompanionRestorationRequest attach(
            CompanionRestorationRequest requested,
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
        boolean initializesDomains = initializesDomains(source);
        var admissionSourceOwner = initializesDomains
                ? null : source.lifecycle().ownerId();
        String admissionSourceWorld = initializesDomains
                ? null : source.lifecycle().ownerWorldKey();
        String targetWorld = source.lifecycle().ownerId() == null
                ? null : requested.targetWorldKey();
        if (payload == null
                || !payload.profileId().equals(requested.profileId())
                || !Objects.equals(
                payload.expectedLifecycleRevision(),
                source.lifecycle().revision()
        )
                || payload.sourceLifecycle() != LifecycleState.DEAD_REVIVABLE
                || !Objects.equals(
                payload.sourceOwnerId(), admissionSourceOwner
        )
                || !Objects.equals(
                payload.sourceWorldKey(), admissionSourceWorld
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.ownerId(), source.lifecycle().ownerId())
                || !Objects.equals(payload.ownerWorldKey(), targetWorld)) {
            throw new IllegalStateException(
                    "free_restoration_admission_canonical_evidence_mismatch"
            );
        }
        PopulationDomainConvergencePlan plan;
        try {
            plan = PopulationDomainConvergencePlanner.plan(
                    requested.profileId(),
                    source.lifecycle().revision(),
                    admissionSourceOwner,
                    admissionSourceWorld,
                    LifecycleState.DEAD_REVIVABLE,
                    source.lifecycle().ownerId(),
                    targetWorld,
                    LifecycleState.ACTIVE,
                    source.committedDomainRows(),
                    payload.reservations(operationId)
            );
        } catch (IllegalStateException mismatch) {
            if (!"population_domain_source_state_mismatch".equals(
                    mismatch.getMessage()
            ) || !legacyClaimCoversAdmission(
                    payload, operationId, source.committedDomainRows()
            )) {
                throw mismatch;
            }
            // Older builds left the deployed row in place on death. It has
            // already consumed capacity, so restoration must not reserve it twice.
            PopulationDomainAdmissionOperation.Payload repaired =
                    withoutDomainReservations(payload);
            return requested.withAdmissionEvidence(
                    LifecycleAdmissionEvidence.managed(
                            repaired, evidence.composition(), null
                    )
            );
        }
        if (evidence.convergencePlan() != null
                && !evidence.convergencePlan().equals(plan)) {
            throw new IllegalStateException(
                    "free_restoration_admission_convergence_mismatch"
            );
        }
        return requested.withAdmissionEvidence(
                LifecycleAdmissionEvidence.managed(
                        payload, evidence.composition(), plan
                )
        );
    }

    private static boolean initializesDomains(
            SqliteLifecycleAdmissionSourceReader.SourceReadModel source
    ) {
        return source.lifecycle().ownerId() != null
                && source.committedDomainRows().isEmpty();
    }

    private static boolean legacyClaimCoversAdmission(
            PopulationDomainAdmissionOperation.Payload payload,
            OperationId operationId,
            List<PopulationDomainReservation> committed
    ) {
        if (committed.isEmpty()
                || payload.sourceLifecycle() != LifecycleState.DEAD_REVIVABLE
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !Objects.equals(payload.sourceOwnerId(), payload.ownerId())) {
            return false;
        }
        List<PopulationDomainReservation> requested =
                payload.reservations(operationId);
        if (requested.isEmpty()) return false;
        return requested.stream().allMatch(target -> committed.stream()
                .anyMatch(source -> source.profileId().equals(target.profileId())
                        && source.bucket().equals(target.bucket())
                        && source.weight() == target.weight()
                        && source.ownedDelta() >= target.ownedDelta()
                        && source.deployableDelta()
                        >= target.deployableDelta()));
    }

    private static PopulationDomainAdmissionOperation.Payload
            withoutDomainReservations(
            PopulationDomainAdmissionOperation.Payload source
    ) {
        return new PopulationDomainAdmissionOperation.Payload(
                source.reservationId(),
                source.profileId(),
                source.ownerId(),
                source.expectedLifecycleRevision(),
                source.ownerWorldKey(),
                source.sourceOwnerId(),
                source.sourceWorldKey(),
                source.sourceLifecycle(),
                source.targetLifecycle(),
                source.familyGroupId(),
                source.providerId(),
                source.providerContractVersion(),
                source.providerGenerationToken(),
                source.providerSnapshotRevision(),
                source.managedConfigRevision(),
                source.expiresAtMs(),
                source.requestedCount(),
                List.of(),
                source.provisionalChildIds(),
                source.createdAtMs()
        );
    }

    private static boolean canonicalSourceMatches(
            CompanionRestorationRequest requested,
            CompanionLifecycle source
    ) {
        return source.profileId().equals(requested.profileId())
                && source.revision().equals(
                requested.expectedLifecycleRevision()
        )
                && source.state() == LifecycleState.DEAD_REVIVABLE
                && source.location().equals(LifecycleLocation.none())
                && source.activeOperationId() == null
                && !source.quarantined();
    }

    static List<OperationScope> participantScopes(
            CompanionRestorationRequest restoration
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(restoration.profileId()));
        addOwners(scopes, restoration.admissionEvidence());
        return List.copyOf(scopes);
    }

    static List<OperationScope> containmentScopes(
            CompanionRestorationRequest restoration,
            OperationId operationId
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.operation(operationId));
        scopes.add(OperationScope.profile(restoration.profileId()));
        addOwners(scopes, restoration.admissionEvidence());
        return List.copyOf(scopes);
    }

    private static void addOwners(
            TreeSet<OperationScope> scopes,
            LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null
                || evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED
                || evidence.payload() == null) {
            return;
        }
        if (evidence.payload().sourceOwnerId() != null) {
            scopes.add(OperationScope.owner(evidence.payload().sourceOwnerId()));
        }
        if (evidence.payload().ownerId() != null) {
            scopes.add(OperationScope.owner(evidence.payload().ownerId()));
        }
    }

    private static CompanionRestorationRequest withoutEvidence(
            CompanionRestorationRequest request
    ) {
        return new CompanionRestorationRequest(
                request.profileId(),
                request.expectedLifecycleRevision(),
                request.sourceState(),
                request.sourceSnapshot(),
                request.targetState(),
                request.projection(),
                request.targetAlias(),
                request.placement(),
                request.spawnReceiptKey(),
                request.requestedAtMs(),
                null
        );
    }

    private static boolean sameCallerRequest(
            CompanionRestorationRequest requested,
            CompanionRestorationRequest persisted
    ) {
        if (requested.admissionEvidence() != null) {
            return requested.equals(persisted);
        }
        return requested.equals(withoutEvidence(persisted));
    }

    private static UUID reservationId(OperationId operationId) {
        return UUID.nameUUIDFromBytes((operationId.value()
                + ":lifecycle-admission").getBytes(StandardCharsets.UTF_8));
    }

    private static int chunkCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CHUNK_SIZE);
    }

    private static UUID aliasUuid(com.alechilles.alecstamework.companion.identity.NpcAlias alias) {
        return alias.value();
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
