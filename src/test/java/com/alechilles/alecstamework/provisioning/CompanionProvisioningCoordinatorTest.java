package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningDisposition;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for restart/idempotency and failed optional projection retry. */
class CompanionProvisioningCoordinatorTest {
    @Test
    void projectionRetryReusesTheCommittedDormantProfileAndNeverClaimsOwnershipTwice() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeBackend backend = new FakeBackend();
        backend.failNextActiveCommit = true;
        CompanionProvisioningCoordinator coordinator =
                new CompanionProvisioningCoordinator(journal, backend, () -> 100L);
        CompanionProvisioningRequest request = activeRequest("hydragon", "soul-bond-1");

        CompanionProvisioningResult first = await(coordinator.provision(request));
        assertEquals(CompanionProvisioningResult.Status.PARTIAL_DORMANT, first.status());
        assertEquals(PopulationCompanionLifecycle.PROVISIONED_DORMANT, first.lifecycle());
        assertEquals(CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE,
                first.projectionStatus());
        assertEquals(1, backend.dormantCommits);
        String profileId = first.profileId();
        assertEquals(CompanionProvisioningOperationStatus.PARTIAL_DORMANT,
                await(coordinator.findOperation("hydragon", "soul-bond-1")).orElseThrow().status());

        CompanionProvisioningResult retried = await(coordinator.provision(request));
        assertEquals(CompanionProvisioningResult.Status.PROVISIONED_ACTIVE, retried.status());
        assertEquals(profileId, retried.profileId());
        assertEquals(1, backend.dormantCommits, "retry must not execute dormant ownership again");
        assertEquals(2, backend.activeCommitAttempts);
        assertEquals(CompanionProvisioningOperationStatus.COMMITTED,
                await(coordinator.findOperation("hydragon", "soul-bond-1")).orElseThrow().status());
        assertEquals(profileId, coordinator.getByOrigin("hydragon", "soul-bond-1")
                .orElseThrow().profileId());
    }

    @Test
    void equalKeysInDifferentNamespacesProduceDistinctOrigins() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeBackend backend = new FakeBackend();
        CompanionProvisioningCoordinator coordinator =
                new CompanionProvisioningCoordinator(journal, backend, () -> 100L);

        CompanionProvisioningResult first = await(coordinator.provision(
                dormantRequest("hydragon", "one-time")));
        CompanionProvisioningResult second = await(coordinator.provision(
                dormantRequest("another-plugin", "one-time")));

        assertEquals(CompanionProvisioningResult.Status.PROVISIONED_DORMANT, first.status());
        assertEquals(CompanionProvisioningResult.Status.PROVISIONED_DORMANT, second.status());
        assertNotEquals(first.operationId(), second.operationId());
        assertNotEquals(first.profileId(), second.profileId());
        assertEquals(2, backend.dormantCommits);
    }

    @Test
    void recoveryResumesApplyingDormantRowWithoutAllocatingAReplacementOperation() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeBackend backend = new FakeBackend();
        CompanionProvisioningCoordinator first =
                new CompanionProvisioningCoordinator(journal, backend, () -> 100L);
        backend.pauseDormantCommit = true;
        CompanionProvisioningResult interrupted = await(first.provision(
                dormantRequest("hydragon", "restart")));
        assertEquals(CompanionProvisioningResult.Status.UNAVAILABLE, interrupted.status());
        assertEquals(CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                journal.findByOrigin("hydragon", "restart").state());

        backend.pauseDormantCommit = false;
        CompanionProvisioningCoordinator restarted =
                new CompanionProvisioningCoordinator(journal, backend, () -> 200L);
        CompanionProvisioningCoordinator.RecoveryReport report = await(restarted.recover(8));
        assertEquals(1, report.attempted());
        assertEquals(1, report.completed());
        assertEquals(0, report.failures());
        assertTrue(restarted.getByOrigin("hydragon", "restart").isPresent());
        assertEquals(1, backend.dormantCommits);
    }

    private static CompanionProvisioningRequest activeRequest(String namespace, String key) {
        return new CompanionProvisioningRequest(namespace, key, null,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "miniwyvern",
                CompanionProvisioningDisposition.ACTIVE, "default",
                new PopulationAdmissionLocation("default", 2, -4), "Spark", null, -1L);
    }

    private static CompanionProvisioningRequest dormantRequest(String namespace, String key) {
        return new CompanionProvisioningRequest(namespace, key, null,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "miniwyvern",
                CompanionProvisioningDisposition.PROVISIONED_DORMANT, "default",
                null, "Spark", null, -1L);
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get();
    }

    private static final class FakeBackend implements ProvisioningPopulationBackend {
        private final Map<String, ProfileSnapshot> profiles = new LinkedHashMap<>();
        private final Map<UUID, DormantRequest> dormantRequests = new LinkedHashMap<>();
        private final Map<UUID, ActiveRequest> activeRequests = new LinkedHashMap<>();
        private int dormantCommits;
        private int activeCommitAttempts;
        private boolean failNextActiveCommit;
        private boolean pauseDormantCommit;

        @Override
        public PolicyResolution resolvePolicy(String roleId, long requestedRevision) {
            return new PolicyResolution(true, requestedRevision == -1L || requestedRevision == 8L,
                    8L, requestedRevision == -1L || requestedRevision == 8L
                    ? "policy-resolved" : "policy-revision-changed");
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareDormant(DormantRequest request) {
            UUID operationId = UUID.nameUUIDFromBytes(("dormant:" + request.provisioningOperationId()).getBytes());
            dormantRequests.putIfAbsent(operationId, request);
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.PREPARED, "dormant-prepared", operationId, null));
        }

        @Override
        public ClaimResult claimDormant(UUID populationOperationId) {
            return new ClaimResult(dormantRequests.containsKey(populationOperationId),
                    "dormant-claimed", null);
        }

        @Override
        public CompletionStage<DormantCommit> commitDormant(
                UUID populationOperationId, DormantProfileDraft profile) {
            if (pauseDormantCommit) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated-crash"));
            }
            DormantRequest request = dormantRequests.get(populationOperationId);
            if (request == null) {
                return CompletableFuture.completedFuture(new DormantCommit(
                        DormantCommit.Status.QUARANTINED, "dormant-operation-missing", null, null));
            }
            ProfileSnapshot snapshot = profiles.computeIfAbsent(profile.provisionalProfileId(), ignored -> {
                dormantCommits++;
                return new ProfileSnapshot(profile.provisionalProfileId(), profile.ownerUuid(),
                        profile.roleId(), PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                        CompanionProvisioningProjectionStatus.NOT_REQUESTED, null, 1L, 100L);
            });
            return CompletableFuture.completedFuture(new DormantCommit(
                    DormantCommit.Status.COMMITTED, "dormant-committed", snapshot, null));
        }

        @Override
        public CompletionStage<Void> cancelDormant(UUID populationOperationId, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<AdmissionPreparation> prepareActive(ActiveRequest request) {
            UUID operationId = UUID.nameUUIDFromBytes(("active:" + request.provisioningOperationId()).getBytes());
            activeRequests.put(operationId, request);
            return CompletableFuture.completedFuture(new AdmissionPreparation(
                    AdmissionPreparation.Status.PREPARED, "active-prepared", operationId, null));
        }

        @Override
        public ClaimResult claimActive(UUID populationOperationId) {
            return new ClaimResult(activeRequests.containsKey(populationOperationId),
                    "active-claimed", null);
        }

        @Override
        public CompletionStage<ProfileSnapshot> commitActive(UUID populationOperationId) {
            activeCommitAttempts++;
            if (failNextActiveCommit) {
                failNextActiveCommit = false;
                return CompletableFuture.failedFuture(new IllegalStateException("projection-failed"));
            }
            ActiveRequest request = activeRequests.get(populationOperationId);
            ProfileSnapshot prior = profiles.get(request.profileId());
            ProfileSnapshot active = new ProfileSnapshot(prior.profileId(), prior.ownerUuid(), prior.roleId(),
                    PopulationCompanionLifecycle.ACTIVE,
                    CompanionProvisioningProjectionStatus.ACTIVE,
                    UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                    prior.profileRevision() + 1L, 200L);
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
                    TransitionOutcome.Status.DENIED, "not-used", null, null));
        }

        @Override
        public Optional<ProfileSnapshot> findProfile(String profileId) {
            return Optional.ofNullable(profiles.get(profileId));
        }
    }

    private static final class InMemoryJournal implements ProvisioningOperationJournal {
        private final Map<String, CompanionProvisioningOperationRecord> byId = new LinkedHashMap<>();

        @Override
        public CompletionStage<CompanionProvisioningRepository.MutationResult> create(
                CompanionProvisioningOperationRecord operation) {
            CompanionProvisioningOperationRecord existing = findByOrigin(
                    operation.callerNamespace(), operation.idempotencyKey());
            if (existing != null) {
                return completed(CompanionProvisioningRepository.Status.IDEMPOTENT,
                        existing, "operation_exists");
            }
            byId.put(operation.operationId(), operation);
            return completed(CompanionProvisioningRepository.Status.CREATED, operation, null);
        }

        @Override
        public CompletionStage<CompanionProvisioningRepository.MutationResult> advance(
                CompanionProvisioningRepository.AdvanceMutation mutation) {
            CompanionProvisioningOperationRecord current = byId.get(mutation.operationId());
            if (current == null) {
                return completed(CompanionProvisioningRepository.Status.NOT_FOUND, null,
                        "operation_not_found");
            }
            if (current.state() == mutation.next()) {
                return completed(CompanionProvisioningRepository.Status.IDEMPOTENT, current,
                        "already_advanced");
            }
            if (current.state() != mutation.expected()
                    || !current.state().canTransitionTo(mutation.next())) {
                return completed(CompanionProvisioningRepository.Status.INVALID_STATE, current,
                        "operation_state_changed");
            }
            CompanionProvisioningOperationRecord updated = copy(current, mutation);
            byId.put(updated.operationId(), updated);
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
            return byId.get(operationId);
        }

        @Override
        public CompanionProvisioningOperationRecord findByOrigin(String namespace, String key) {
            return byId.values().stream().filter(row -> row.callerNamespace().equals(namespace)
                    && row.idempotencyKey().equals(key)).findFirst().orElse(null);
        }

        @Override
        public CompanionProvisioningOperationRecord findByProfile(String profileId) {
            return byId.values().stream().filter(row -> profileId.equals(row.canonicalProfileId()))
                    .findFirst().orElse(null);
        }

        @Override
        public List<CompanionProvisioningOperationRecord> loadRecoverable(int limit) {
            List<CompanionProvisioningOperationRecord> rows = new ArrayList<>();
            for (CompanionProvisioningOperationRecord row : byId.values()) {
                if (!row.state().isTerminal() && rows.size() < limit) rows.add(row);
            }
            return List.copyOf(rows);
        }

        private CompletionStage<CompanionProvisioningRepository.MutationResult> completed(
                CompanionProvisioningRepository.Status status,
                CompanionProvisioningOperationRecord operation, String reason) {
            return CompletableFuture.completedFuture(
                    new CompanionProvisioningRepository.MutationResult(status, operation, reason));
        }

        private CompanionProvisioningOperationRecord copy(
                CompanionProvisioningOperationRecord current,
                CompanionProvisioningRepository.AdvanceMutation mutation) {
            boolean terminal = mutation.next().isTerminal();
            return new CompanionProvisioningOperationRecord(
                    current.operationId(), current.callerNamespace(), current.idempotencyKey(),
                    current.correlationId(), current.ownerUuid(), current.targetRoleId(),
                    current.requestedDisposition(), current.ownershipWorldName(),
                    current.destinationContextJson(), current.initialProfileJson(),
                    current.expectedPolicyRevision(), current.provisionalProfileId(),
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
                    current.createdAtMs(), mutation.updatedAtMs(),
                    terminal ? mutation.updatedAtMs() : 0L);
        }
    }
}
