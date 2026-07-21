package com.alechilles.alecstamework.items.capturepolicy.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaptureAttemptCoordinatorTest {
    private static final long NOW = 10_000L;

    @Test
    void retryReturnsDurableFailureWithoutRollingAgainOrReemitting() {
        FakeJournal journal = new FakeJournal();
        AtomicInteger entropyCalls = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        CaptureAttemptCoordinator coordinator = coordinator(
                journal,
                ignored -> {
                    entropyCalls.incrementAndGet();
                    return 0.9D;
                },
                ignored -> events.incrementAndGet());
        CaptureAttemptCoordinator.AttemptRequest request = request(
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.PROBABILITY, 1, 0.5D, 0.0D,
                        0.0D, 1.0D, 2_000, null, null));

        CaptureAttemptCoordinator.ResolutionResult first = coordinator.resolve(request).join();
        CaptureAttemptCoordinator.ResolutionResult retry = coordinator.resolve(request).join();

        assertEquals(CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL, first.status());
        assertEquals(CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL, retry.status());
        assertEquals(1, entropyCalls.get());
        assertEquals(1, events.get());
        assertTrue(first.attempt().resolution().failureCooldownUntilMs() > NOW);
    }

    @Test
    void certainOutcomeDoesNotObtainEntropyAndCommitsEventAfterApply() {
        FakeJournal journal = new FakeJournal();
        AtomicInteger entropyCalls = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        CaptureAttemptCoordinator coordinator = coordinator(
                journal,
                ignored -> {
                    entropyCalls.incrementAndGet();
                    return 0.2D;
                },
                ignored -> events.incrementAndGet());
        CaptureAttemptCoordinator.AttemptRequest request = request(
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.PROBABILITY, 1, 1.0D, 0.0D,
                        0.0D, 1.0D, 0, null, null));

        CaptureAttemptCoordinator.ResolutionResult result = coordinator.resolve(request).join();

        assertEquals(CaptureAttemptCoordinator.ResultStatus.SUCCESS, result.status());
        assertEquals(0, entropyCalls.get());
        assertEquals(0, events.get());
        assertTrue(coordinator.beginApply(request.attemptId()).join());
        assertTrue(coordinator.commit(request.attemptId()).join());
        assertEquals(1, events.get());
    }

    @Test
    void boundedRecoveryCancelsExpiredPreparationAndQuarantinesUnsafeApply() {
        FakeJournal journal = new FakeJournal();
        CaptureAttemptCoordinator coordinator = coordinator(journal, ignored -> 0.5D, ignored -> { });
        CaptureAttemptRecord expired = prepared(request(
                ItemFeatureConfig.CaptureItemMechanics.GUARANTEED_DEFAULT), NOW - 1L);
        journal.rows.put(expired.identity().attemptId(), expired);
        CaptureAttemptRecord applying = copy(expired,
                UUID.randomUUID().toString(), CaptureAttemptRecord.State.APPLYING,
                new CaptureAttemptRecord.Resolution(
                        0, 0, 1, 1, 0, 0, 1, null,
                        "CAPTURED", "capture-guaranteed-item", 0, NOW - 100));
        journal.rows.put(applying.identity().attemptId(), applying);

        CaptureAttemptCoordinator.RecoveryReport report = coordinator.recover(8).join();

        assertTrue(report.ready());
        assertEquals(1, report.canceled());
        assertEquals(1, report.quarantined());
        assertEquals(CaptureAttemptRecord.State.CANCELED,
                journal.rows.get(expired.identity().attemptId()).state());
        assertEquals(CaptureAttemptRecord.State.QUARANTINED,
                journal.rows.get(applying.identity().attemptId()).state());
    }

    private static CaptureAttemptCoordinator coordinator(
            FakeJournal journal,
            CaptureEntropySource entropy,
            java.util.function.Consumer<com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent> events) {
        CaptureRequirementRuntime requirements = new CaptureRequirementRuntime() {
            @Override public long captureRequirementGeneration() { return 0; }
            @Override public CaptureRequirementDecision evaluateCaptureRequirement(
                    com.alechilles.alecstamework.api.CaptureRequirementSpec spec,
                    CaptureRequirementContext context, long expectedGeneration) {
                return CaptureRequirementDecision.allow();
            }
        };
        return new CaptureAttemptCoordinator(
                journal,
                new CapturePolicyRegistry(),
                new SpawnerCaptureChanceService(requirements),
                entropy,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                events);
    }

    private static CaptureAttemptCoordinator.AttemptRequest request(
            ItemFeatureConfig.CaptureItemMechanics mechanics) {
        UUID attemptId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        return new CaptureAttemptCoordinator.AttemptRequest(
                attemptId, UUID.randomUUID(), "test", "capture-1", actor, target,
                null, -1L, "Test_Stone", "Test_Role", "{}", "test-spawner",
                1L, mechanics, 5.0D, 10.0D,
                new CaptureRequirementContext(
                        attemptId, CaptureRequirementPhase.FINAL_REVALIDATION,
                        actor, target, null, "Test_Role", "test-world", "Test_Stone",
                        0.5D, CaptureRequirementContext.UNKNOWN_PROFILE_REVISION),
                0L, null, NOW + 30_000L);
    }

    private static CaptureAttemptRecord prepared(
            CaptureAttemptCoordinator.AttemptRequest request, long expiresAt) {
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        request.attemptId().toString(), request.callerNamespace(), request.idempotencyKey(),
                        request.actorUuid(), request.targetNpcUuid(), null, null,
                        request.sourceItemId(), request.roleId(), request.sourceContextJson()),
                new CaptureAttemptRecord.ConfigEvidence(
                        request.spawnerConfigId(), request.spawnerConfigRevision(),
                        null, null, true, true),
                CaptureAttemptRecord.State.PREPARED, null, null,
                request.operationId().toString(), 0, "READY", expiresAt,
                NOW - 1_000, NOW - 1_000, 0, null);
    }

    private static CaptureAttemptRecord copy(
            CaptureAttemptRecord source, String attemptId, CaptureAttemptRecord.State state,
            CaptureAttemptRecord.Resolution resolution) {
        CaptureAttemptRecord.Identity old = source.identity();
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        attemptId, old.callerNamespace(), attemptId, old.actorUuid(), old.targetNpcUuid(),
                        old.profileId(), old.expectedProfileRevision(), old.sourceItemId(),
                        old.sourceRoleId(), old.sourceContextJson()),
                source.config(), state, resolution, source.populationOperationId(),
                UUID.randomUUID().toString(), 0, "READY", source.expiresAtMs(),
                source.createdAtMs(), source.updatedAtMs(), 0, null);
    }

    private static final class FakeJournal implements CaptureAttemptJournal {
        final Map<String, CaptureAttemptRecord> rows = new LinkedHashMap<>();
        final java.util.Set<String> emitted = new java.util.HashSet<>();

        @Override
        public CompletableFuture<CaptureAttemptRepository.PrepareResult> prepare(CaptureAttemptRecord attempt) {
            CaptureAttemptRecord existing = rows.putIfAbsent(attempt.identity().attemptId(), attempt);
            return CompletableFuture.completedFuture(new CaptureAttemptRepository.PrepareResult(
                    existing == null ? CaptureAttemptRepository.PrepareStatus.PREPARED
                            : CaptureAttemptRepository.PrepareStatus.IDEMPOTENT,
                    existing == null ? attempt : existing, null));
        }

        @Override
        public CompletableFuture<CaptureAttemptRepository.MutationResult> resolve(
                CaptureAttemptRepository.ResolutionMutation mutation) {
            CaptureAttemptRecord current = rows.get(mutation.attemptId());
            CaptureAttemptRecord.State state = mutation.success()
                    ? CaptureAttemptRecord.State.RESOLVED_SUCCESS
                    : CaptureAttemptRecord.State.RESOLVED_FAILURE;
            CaptureAttemptRecord updated = withState(current, state, mutation.resolution());
            rows.put(mutation.attemptId(), updated);
            return CompletableFuture.completedFuture(new CaptureAttemptRepository.MutationResult(
                    CaptureAttemptRepository.MutationStatus.APPLIED, updated, null));
        }

        @Override
        public CompletableFuture<CaptureAttemptRepository.MutationResult> advance(
                String attemptId, CaptureAttemptRecord.State expected,
                CaptureAttemptRecord.State next, String reasonCode, String lastError, long nowMs) {
            CaptureAttemptRecord current = rows.get(attemptId);
            if (current == null || current.state() != expected) {
                return CompletableFuture.completedFuture(new CaptureAttemptRepository.MutationResult(
                        CaptureAttemptRepository.MutationStatus.INVALID_STATE, current, "state"));
            }
            CaptureAttemptRecord updated = withState(current, next, current.resolution());
            rows.put(attemptId, updated);
            return CompletableFuture.completedFuture(new CaptureAttemptRepository.MutationResult(
                    CaptureAttemptRepository.MutationStatus.APPLIED, updated, null));
        }

        @Override
        public CompletableFuture<Boolean> markEventEmitted(String attemptId, long emittedAtMs) {
            return CompletableFuture.completedFuture(emitted.add(attemptId));
        }

        @Override public CaptureAttemptRecord find(String attemptId) { return rows.get(attemptId); }
        @Override public List<CaptureAttemptRecord> loadRecoverable() { return new ArrayList<>(rows.values()); }

        private CaptureAttemptRecord withState(CaptureAttemptRecord current,
                                               CaptureAttemptRecord.State state,
                                               CaptureAttemptRecord.Resolution resolution) {
            return new CaptureAttemptRecord(
                    current.identity(), current.config(), state, resolution,
                    current.populationOperationId(), current.captureOperationId(),
                    current.eventEmittedAtMs(), current.recoveryStatus(), current.expiresAtMs(),
                    current.createdAtMs(), NOW, state.isTerminal() ? NOW : 0, current.lastError());
        }
    }
}
