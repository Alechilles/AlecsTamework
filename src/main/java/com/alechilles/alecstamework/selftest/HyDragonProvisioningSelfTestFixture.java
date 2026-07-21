package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.CompanionProvisioningDisposition;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import com.alechilles.alecstamework.provisioning.CompanionProvisioningCoordinator;
import com.alechilles.alecstamework.provisioning.ProvisioningOperationJournal;
import com.alechilles.alecstamework.provisioning.ProvisioningPopulationBackend;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** In-memory dormant/active/projection-failure fixtures for provisioning release checks. */
final class HyDragonProvisioningSelfTestFixture {
    private static final UUID OWNER_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID ACTIVE_NPC_ID = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final long NOW_MS = 75_000L;

    private HyDragonProvisioningSelfTestFixture() {
    }

    static List<ApiSelfTestAssertion> run() {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        try {
            InMemoryProvisioningJournal journal = new InMemoryProvisioningJournal();
            InMemoryProvisioningBackend backend = new InMemoryProvisioningBackend();
            CompanionProvisioningCoordinator coordinator =
                    new CompanionProvisioningCoordinator(journal, backend, () -> NOW_MS);

            CompanionProvisioningResult dormant = coordinator.provision(
                    request("dormant", CompanionProvisioningDisposition.PROVISIONED_DORMANT)).toCompletableFuture().join();
            assertions.add(new ApiSelfTestAssertion(
                    "isolated provisioning commits dormant profile",
                    dormant.status() == CompanionProvisioningResult.Status.PROVISIONED_DORMANT
                            && dormant.lifecycle() == PopulationCompanionLifecycle.PROVISIONED_DORMANT
                            && dormant.projectionStatus() == CompanionProvisioningProjectionStatus.NOT_REQUESTED,
                    "status=" + dormant.status() + " lifecycle=" + dormant.lifecycle()
                            + " projection=" + dormant.projectionStatus()));

            CompanionProvisioningResult active = coordinator.provision(
                    request("active", CompanionProvisioningDisposition.ACTIVE)).toCompletableFuture().join();
            var activeView = coordinator.getByOrigin("tamework-self-test", "active").orElse(null);
            assertions.add(new ApiSelfTestAssertion(
                    "isolated provisioning projects active profile",
                    active.status() == CompanionProvisioningResult.Status.PROVISIONED_ACTIVE
                            && active.lifecycle() == PopulationCompanionLifecycle.ACTIVE
                            && active.projectionStatus() == CompanionProvisioningProjectionStatus.ACTIVE
                            && activeView != null
                            && ACTIVE_NPC_ID.equals(activeView.currentNpcUuid()),
                    "status=" + active.status() + " lifecycle=" + active.lifecycle()
                            + " projection=" + active.projectionStatus()));

            backend.failProjectionKey = "failed-projection";
            CompanionProvisioningResult failed = coordinator.provision(
                    request("failed-projection", CompanionProvisioningDisposition.ACTIVE))
                    .toCompletableFuture().join();
            String failedProfile = failed.profileId();

            // A fresh coordinator over the same isolated durable fixtures simulates restart diagnostics.
            CompanionProvisioningCoordinator restarted =
                    new CompanionProvisioningCoordinator(journal, backend, () -> NOW_MS + 1L);
            var diagnostic = restarted.findOperation("tamework-self-test", "failed-projection")
                    .toCompletableFuture().join().orElse(null);
            boolean failedPassed = failed.status() == CompanionProvisioningResult.Status.PARTIAL_DORMANT
                    && failed.lifecycle() == PopulationCompanionLifecycle.PROVISIONED_DORMANT
                    && failed.projectionStatus() == CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE
                    && failedProfile != null
                    && diagnostic != null
                    && diagnostic.status() == CompanionProvisioningOperationStatus.PARTIAL_DORMANT
                    && failedProfile.equals(diagnostic.profileId())
                    && backend.dormantCommitCount("failed-projection") == 1;
            assertions.add(new ApiSelfTestAssertion(
                    "isolated failed projection stays durable and recoverable",
                    failedPassed,
                    "status=" + failed.status() + " projection=" + failed.projectionStatus()
                            + " diagnostic=" + (diagnostic == null ? "missing" : diagnostic.status())
                            + " dormantCommits=" + backend.dormantCommitCount("failed-projection")));
        } catch (RuntimeException failure) {
            assertions.add(new ApiSelfTestAssertion(
                    "isolated provisioning fixtures execute",
                    false,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())));
        }
        return List.copyOf(assertions);
    }

    private static CompanionProvisioningRequest request(
            String key,
            CompanionProvisioningDisposition disposition) {
        PopulationAdmissionLocation destination = disposition == CompanionProvisioningDisposition.ACTIVE
                ? new PopulationAdmissionLocation("self-test-world", 1, -1)
                : null;
        return new CompanionProvisioningRequest(
                "tamework-self-test",
                key,
                UUID.nameUUIDFromBytes(("provision-correlation:" + key).getBytes(StandardCharsets.UTF_8)),
                OWNER_ID,
                "HyDragon_SelfTest_Miniwyvern",
                disposition,
                "self-test-world",
                destination,
                "Self Test Miniwyvern",
                null,
                -1L);
    }

    private static final class InMemoryProvisioningBackend implements ProvisioningPopulationBackend {
        private final Map<String, ProfileSnapshot> profiles = new LinkedHashMap<>();
        private final Map<UUID, DormantRequest> dormantRequests = new LinkedHashMap<>();
        private final Map<UUID, ActiveRequest> activeRequests = new LinkedHashMap<>();
        private final Map<String, Integer> dormantCommitsByKey = new LinkedHashMap<>();
        private String failProjectionKey;

        @Override
        public PolicyResolution resolvePolicy(String roleId, long requestedRevision) {
            return new PolicyResolution(
                    true,
                    requestedRevision == -1L || requestedRevision == 3L,
                    3L,
                    requestedRevision == -1L || requestedRevision == 3L
                            ? "self-test-policy-resolved" : "self-test-policy-changed");
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareDormant(DormantRequest request) {
            UUID operationId = stable("dormant", request.provisioningOperationId().toString());
            dormantRequests.putIfAbsent(operationId, request);
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.PREPARED,
                    "self-test-dormant-prepared",
                    operationId,
                    null));
        }

        @Override
        public ClaimResult claimDormant(UUID populationOperationId) {
            return new ClaimResult(
                    dormantRequests.containsKey(populationOperationId),
                    "self-test-dormant-claimed",
                    null);
        }

        @Override
        public CompletionStage<DormantCommit> commitDormant(
                UUID populationOperationId,
                DormantProfileDraft draft) {
            DormantRequest request = dormantRequests.get(populationOperationId);
            if (request == null) {
                return CompletableFuture.completedFuture(new DormantCommit(
                        DormantCommit.Status.QUARANTINED,
                        "self-test-dormant-operation-missing",
                        null,
                        null));
            }
            ProfileSnapshot snapshot = profiles.computeIfAbsent(draft.provisionalProfileId(), ignored -> {
                String key = provisioningKey(request.provisioningOperationId());
                dormantCommitsByKey.merge(key, 1, Integer::sum);
                return new ProfileSnapshot(
                        draft.provisionalProfileId(),
                        draft.ownerUuid(),
                        draft.roleId(),
                        PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                        CompanionProvisioningProjectionStatus.NOT_REQUESTED,
                        null,
                        1L,
                        NOW_MS);
            });
            return CompletableFuture.completedFuture(new DormantCommit(
                    DormantCommit.Status.COMMITTED,
                    "self-test-dormant-committed",
                    snapshot,
                    null));
        }

        @Override
        public CompletionStage<Void> cancelDormant(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareActive(ActiveRequest request) {
            UUID operationId = stable("active", request.provisioningOperationId().toString());
            activeRequests.put(operationId, request);
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.PREPARED,
                    "self-test-active-prepared",
                    operationId,
                    null));
        }

        @Override
        public ClaimResult claimActive(UUID populationOperationId) {
            return new ClaimResult(
                    activeRequests.containsKey(populationOperationId),
                    "self-test-active-claimed",
                    null);
        }

        @Override
        public CompletionStage<ProfileSnapshot> commitActive(UUID populationOperationId) {
            ActiveRequest request = activeRequests.get(populationOperationId);
            if (request == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("active operation missing"));
            }
            String key = provisioningKey(request.provisioningOperationId());
            if (key.equals(failProjectionKey)) {
                return CompletableFuture.failedFuture(new IllegalStateException("self-test projection failure"));
            }
            ProfileSnapshot prior = profiles.get(request.profileId());
            ProfileSnapshot active = new ProfileSnapshot(
                    prior.profileId(),
                    prior.ownerUuid(),
                    prior.roleId(),
                    PopulationCompanionLifecycle.ACTIVE,
                    CompanionProvisioningProjectionStatus.ACTIVE,
                    ACTIVE_NPC_ID,
                    prior.profileRevision() + 1L,
                    NOW_MS);
            profiles.put(active.profileId(), active);
            return CompletableFuture.completedFuture(active);
        }

        @Override
        public CompletionStage<Void> cancelActive(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<TransitionOutcome> transition(TransitionRequest request) {
            return CompletableFuture.completedFuture(new TransitionOutcome(
                    TransitionOutcome.Status.DENIED,
                    "self-test-transition-not-used",
                    null,
                    null));
        }

        @Override
        public Optional<ProfileSnapshot> findProfile(String profileId) {
            return Optional.ofNullable(profiles.get(profileId));
        }

        private int dormantCommitCount(String key) {
            return dormantCommitsByKey.getOrDefault(key, 0);
        }

        private String provisioningKey(UUID provisioningOperationId) {
            return dormantRequests.values().stream()
                    .filter(request -> request.provisioningOperationId().equals(provisioningOperationId))
                    .map(request -> operationOriginKey(request.provisioningOperationId()))
                    .findFirst()
                    .orElseGet(() -> operationOriginKey(provisioningOperationId));
        }

        private String operationOriginKey(UUID provisioningOperationId) {
            for (String key : List.of("dormant", "active", "failed-projection")) {
                UUID expected = UUID.nameUUIDFromBytes(
                        ("tamework:provision:tamework-self-test\0" + key).getBytes(StandardCharsets.UTF_8));
                if (expected.equals(provisioningOperationId)) return key;
            }
            return "unknown";
        }

        private UUID stable(String kind, String value) {
            return UUID.nameUUIDFromBytes((kind + ':' + value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class InMemoryProvisioningJournal implements ProvisioningOperationJournal {
        private final Map<String, CompanionProvisioningOperationRecord> operations = new LinkedHashMap<>();

        @Override
        public CompletionStage<CompanionProvisioningRepository.MutationResult> create(
                CompanionProvisioningOperationRecord operation) {
            CompanionProvisioningOperationRecord existing =
                    findByOrigin(operation.callerNamespace(), operation.idempotencyKey());
            if (existing != null) {
                return completed(CompanionProvisioningRepository.Status.IDEMPOTENT, existing, "operation_exists");
            }
            operations.put(operation.operationId(), operation);
            return completed(CompanionProvisioningRepository.Status.CREATED, operation, null);
        }

        @Override
        public CompletionStage<CompanionProvisioningRepository.MutationResult> advance(
                CompanionProvisioningRepository.AdvanceMutation mutation) {
            CompanionProvisioningOperationRecord current = operations.get(mutation.operationId());
            if (current == null) {
                return completed(CompanionProvisioningRepository.Status.NOT_FOUND, null, "operation_not_found");
            }
            if (current.state() == mutation.next()) {
                return completed(CompanionProvisioningRepository.Status.IDEMPOTENT, current, "already_advanced");
            }
            if (current.state() != mutation.expected() || !current.state().canTransitionTo(mutation.next())) {
                return completed(CompanionProvisioningRepository.Status.INVALID_STATE, current, "state_changed");
            }
            CompanionProvisioningOperationRecord updated = copy(current, mutation);
            operations.put(updated.operationId(), updated);
            CompanionProvisioningRepository.Status status = switch (mutation.next()) {
                case COMMITTED -> CompanionProvisioningRepository.Status.COMMITTED;
                case PARTIAL_DORMANT -> CompanionProvisioningRepository.Status.PARTIAL_DORMANT;
                case DENIED -> CompanionProvisioningRepository.Status.DENIED;
                case CANCELED -> CompanionProvisioningRepository.Status.CANCELED;
                case QUARANTINED -> CompanionProvisioningRepository.Status.QUARANTINED;
                default -> CompanionProvisioningRepository.Status.ADVANCED;
            };
            return completed(status, updated, null);
        }

        @Override
        public CompanionProvisioningOperationRecord find(String operationId) {
            return operations.get(operationId);
        }

        @Override
        public CompanionProvisioningOperationRecord findByOrigin(String callerNamespace, String idempotencyKey) {
            return operations.values().stream()
                    .filter(operation -> operation.callerNamespace().equals(callerNamespace)
                            && operation.idempotencyKey().equals(idempotencyKey))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public CompanionProvisioningOperationRecord findByProfile(String profileId) {
            return operations.values().stream()
                    .filter(operation -> profileId.equals(operation.canonicalProfileId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<CompanionProvisioningOperationRecord> loadRecoverable(int limit) {
            return operations.values().stream()
                    .filter(operation -> !operation.state().isTerminal())
                    .limit(limit)
                    .toList();
        }

        private CompletionStage<CompanionProvisioningRepository.MutationResult> completed(
                CompanionProvisioningRepository.Status status,
                CompanionProvisioningOperationRecord operation,
                String reason) {
            return CompletableFuture.completedFuture(
                    new CompanionProvisioningRepository.MutationResult(status, operation, reason));
        }

        private CompanionProvisioningOperationRecord copy(
                CompanionProvisioningOperationRecord current,
                CompanionProvisioningRepository.AdvanceMutation mutation) {
            boolean terminal = mutation.next().isTerminal();
            return new CompanionProvisioningOperationRecord(
                    current.operationId(),
                    current.callerNamespace(),
                    current.idempotencyKey(),
                    current.correlationId(),
                    current.ownerUuid(),
                    current.targetRoleId(),
                    current.requestedDisposition(),
                    current.ownershipWorldName(),
                    current.destinationContextJson(),
                    current.initialProfileJson(),
                    current.expectedPolicyRevision(),
                    current.provisionalProfileId(),
                    current.canonicalProfileId() == null ? mutation.canonicalProfileId()
                            : current.canonicalProfileId(),
                    mutation.next(),
                    current.dormantPopulationOperationId() == null
                            ? mutation.dormantPopulationOperationId()
                            : current.dormantPopulationOperationId(),
                    current.activePopulationOperationId() == null
                            ? mutation.activePopulationOperationId()
                            : current.activePopulationOperationId(),
                    mutation.resultCode() == null ? current.resultCode() : mutation.resultCode(),
                    mutation.projectionReason() == null ? current.projectionReason()
                            : mutation.projectionReason(),
                    mutation.recoveryStatus() == null ? current.recoveryStatus()
                            : mutation.recoveryStatus(),
                    current.createdAtMs(),
                    mutation.updatedAtMs(),
                    terminal ? mutation.updatedAtMs() : 0L);
        }
    }
}
