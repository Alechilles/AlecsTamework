package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptJournal;
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

/** In-memory capture fixtures for the shipped HyDragon integration release gate. */
final class HyDragonCaptureSelfTestFixture {
    private static final long NOW_MS = 50_000L;
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private HyDragonCaptureSelfTestFixture() {
    }

    static List<ApiSelfTestAssertion> run() {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        try {
            runGuaranteed(assertions);
            runFailedProbabilityAndDuplicate(assertions);
        } catch (RuntimeException failure) {
            assertions.add(new ApiSelfTestAssertion(
                    "isolated capture fixtures execute", false,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())));
        }
        return List.copyOf(assertions);
    }

    private static void runGuaranteed(List<ApiSelfTestAssertion> assertions) {
        InMemoryCaptureJournal journal = new InMemoryCaptureJournal();
        AtomicInteger entropyCalls = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        CaptureAttemptCoordinator coordinator = coordinator(
                journal, entropyCalls, events, 0.75D);
        CaptureAttemptCoordinator.AttemptRequest request = request(
                "guaranteed", ItemFeatureConfig.CaptureItemMechanics.GUARANTEED_DEFAULT);

        CaptureAttemptCoordinator.ResolutionResult resolved = coordinator.resolve(request).join();
        boolean began = coordinator.beginApply(request.attemptId()).join();
        boolean committed = coordinator.commit(request.attemptId()).join();

        boolean passed = resolved.status() == CaptureAttemptCoordinator.ResultStatus.SUCCESS
                && resolved.attempt() != null
                && resolved.attempt().config().guaranteed()
                && resolved.attempt().resolution().entropySample() == null
                && entropyCalls.get() == 0
                && began
                && committed
                && journal.findUnchecked(request.attemptId()).state() == CaptureAttemptRecord.State.COMMITTED
                && events.get() == 1;
        assertions.add(new ApiSelfTestAssertion(
                "isolated guaranteed capture commits without entropy",
                passed,
                "status=" + resolved.status() + " entropyCalls=" + entropyCalls.get()
                        + " state=" + journal.findUnchecked(request.attemptId()).state()
                        + " events=" + events.get()));
    }

    private static void runFailedProbabilityAndDuplicate(List<ApiSelfTestAssertion> assertions) {
        InMemoryCaptureJournal journal = new InMemoryCaptureJournal();
        AtomicInteger entropyCalls = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        CaptureAttemptCoordinator coordinator = coordinator(
                journal, entropyCalls, events, 0.90D);
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.PROBABILITY, 1, 0.25D, 0.0D,
                        0.0D, 1.0D, 2_000, null, null);
        CaptureAttemptCoordinator.AttemptRequest request = request("failed-roll", mechanics);
        CaptureAttemptCoordinator.AttemptRequest lateCallback = request(
                "failed-roll", mechanics, "late-callback");

        CaptureAttemptCoordinator.ResolutionResult first = coordinator.resolve(request).join();
        CaptureAttemptCoordinator.ResolutionResult duplicate = coordinator.resolve(lateCallback).join();

        CaptureAttemptRecord durable = journal.findUnchecked(request.attemptId());
        boolean passed = first.status() == CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL
                && duplicate.status() == CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL
                && !lateCallback.attemptId().equals(request.attemptId())
                && duplicate.attemptId().equals(request.attemptId())
                && first.attempt() == duplicate.attempt()
                && durable.state() == CaptureAttemptRecord.State.RESOLVED_FAILURE
                && durable.resolution().failureCooldownUntilMs() > NOW_MS
                && entropyCalls.get() == 1
                && events.get() == 1
                && journal.applyTransitions == 0;
        assertions.add(new ApiSelfTestAssertion(
                "isolated failed capture is immutable and duplicate-safe",
                passed,
                "first=" + first.status() + " duplicate=" + duplicate.status()
                        + " entropyCalls=" + entropyCalls.get() + " events=" + events.get()
                        + " applyTransitions=" + journal.applyTransitions));
    }

    private static CaptureAttemptCoordinator coordinator(
            InMemoryCaptureJournal journal,
            AtomicInteger entropyCalls,
            AtomicInteger events,
            double entropy) {
        CaptureRequirementRuntime requirements = new CaptureRequirementRuntime() {
            @Override
            public long captureRequirementGeneration() {
                return 0L;
            }

            @Override
            public CaptureRequirementDecision evaluateCaptureRequirement(
                    com.alechilles.alecstamework.api.CaptureRequirementSpec spec,
                    CaptureRequirementContext context,
                    long expectedGeneration) {
                return CaptureRequirementDecision.allow();
            }
        };
        return new CaptureAttemptCoordinator(
                journal,
                new CapturePolicyRegistry(),
                new SpawnerCaptureChanceService(requirements),
                ignored -> {
                    entropyCalls.incrementAndGet();
                    return entropy;
                },
                Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC),
                ignored -> events.incrementAndGet());
    }

    private static CaptureAttemptCoordinator.AttemptRequest request(
            String key,
            ItemFeatureConfig.CaptureItemMechanics mechanics) {
        return request(key, mechanics, "initial");
    }

    private static CaptureAttemptCoordinator.AttemptRequest request(
            String key,
            ItemFeatureConfig.CaptureItemMechanics mechanics,
            String callbackIdentity) {
        UUID attemptId = UUID.nameUUIDFromBytes(
                ("capture-attempt:" + key + ':' + callbackIdentity)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CaptureAttemptCoordinator.AttemptRequest(
                attemptId,
                UUID.nameUUIDFromBytes(("capture-operation:" + key + ':' + callbackIdentity)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "tamework-self-test",
                key,
                ACTOR_ID,
                TARGET_ID,
                null,
                -1L,
                "HyDragon_SelfTest_Stone",
                "HyDragon_SelfTest_Dragon",
                "{\"fixture\":true}",
                "HyDragon_SelfTest_Spawner",
                1L,
                mechanics,
                5.0D,
                10.0D,
                new CaptureRequirementContext(
                        attemptId,
                        CaptureRequirementPhase.FINAL_REVALIDATION,
                        ACTOR_ID,
                        TARGET_ID,
                        null,
                        "HyDragon_SelfTest_Dragon",
                        "self-test-world",
                        "HyDragon_SelfTest_Stone",
                        0.5D,
                        CaptureRequirementContext.UNKNOWN_PROFILE_REVISION),
                0L,
                null,
                NOW_MS + 30_000L);
    }

    private static final class InMemoryCaptureJournal implements CaptureAttemptJournal {
        private final Map<String, CaptureAttemptRecord> attempts = new LinkedHashMap<>();
        private final Map<String, String> originToAttempt = new LinkedHashMap<>();
        private final java.util.Set<String> emitted = new java.util.HashSet<>();
        private int applyTransitions;

        @Override
        public CompletableFuture<CaptureAttemptRepository.PrepareResult> prepare(CaptureAttemptRecord attempt) {
            String origin = attempt.identity().callerNamespace() + ":" + attempt.identity().idempotencyKey();
            String existingId = originToAttempt.putIfAbsent(origin, attempt.identity().attemptId());
            CaptureAttemptRecord existing = existingId == null ? null : attempts.get(existingId);
            if (existing == null) {
                attempts.put(attempt.identity().attemptId(), attempt);
            }
            return CompletableFuture.completedFuture(new CaptureAttemptRepository.PrepareResult(
                    existing == null ? CaptureAttemptRepository.PrepareStatus.PREPARED
                            : CaptureAttemptRepository.PrepareStatus.IDEMPOTENT,
                    existing == null ? attempt : existing,
                    null));
        }

        @Override
        public CompletableFuture<CaptureAttemptRepository.MutationResult> resolve(
                CaptureAttemptRepository.ResolutionMutation mutation) {
            CaptureAttemptRecord current = attempts.get(mutation.attemptId());
            CaptureAttemptRecord updated = copy(
                    current,
                    mutation.success() ? CaptureAttemptRecord.State.RESOLVED_SUCCESS
                            : CaptureAttemptRecord.State.RESOLVED_FAILURE,
                    mutation.resolution(),
                    mutation.populationOperationId(),
                    mutation.captureOperationId());
            attempts.put(updated.identity().attemptId(), updated);
            return mutation(CaptureAttemptRepository.MutationStatus.APPLIED, updated);
        }

        @Override
        public CompletableFuture<CaptureAttemptRepository.MutationResult> advance(
                String attemptId,
                CaptureAttemptRecord.State expected,
                CaptureAttemptRecord.State next,
                String reasonCode,
                String lastError,
                long nowMs) {
            CaptureAttemptRecord current = attempts.get(attemptId);
            if (current == null || current.state() != expected) {
                return mutation(CaptureAttemptRepository.MutationStatus.INVALID_STATE, current);
            }
            if (next == CaptureAttemptRecord.State.APPLYING) {
                applyTransitions++;
            }
            CaptureAttemptRecord updated = copy(
                    current, next, current.resolution(),
                    current.populationOperationId(), current.captureOperationId());
            attempts.put(attemptId, updated);
            return mutation(CaptureAttemptRepository.MutationStatus.APPLIED, updated);
        }

        @Override
        public CompletableFuture<Boolean> markEventEmitted(String attemptId, long emittedAtMs) {
            return CompletableFuture.completedFuture(emitted.add(attemptId));
        }

        @Override
        public CompletableFuture<CaptureAttemptRepository.FailureCooldown> findFailureCooldown(
                UUID actorUuid,
                String spawnerConfigId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CaptureAttemptRecord find(String attemptId) {
            return attempts.get(attemptId);
        }

        @Override
        public List<CaptureAttemptRecord> loadRecoverable() {
            return List.copyOf(attempts.values());
        }

        private CaptureAttemptRecord findUnchecked(UUID attemptId) {
            return attempts.get(attemptId.toString());
        }

        private CompletableFuture<CaptureAttemptRepository.MutationResult> mutation(
                CaptureAttemptRepository.MutationStatus status,
                CaptureAttemptRecord attempt) {
            return CompletableFuture.completedFuture(
                    new CaptureAttemptRepository.MutationResult(status, attempt, null));
        }

        private CaptureAttemptRecord copy(
                CaptureAttemptRecord current,
                CaptureAttemptRecord.State state,
                CaptureAttemptRecord.Resolution resolution,
                String populationOperationId,
                String captureOperationId) {
            return new CaptureAttemptRecord(
                    current.identity(), current.config(), state, resolution,
                    populationOperationId, captureOperationId,
                    current.eventEmittedAtMs(), current.recoveryStatus(), current.expiresAtMs(),
                    current.createdAtMs(), NOW_MS,
                    state.isTerminal() ? NOW_MS : 0L,
                    current.lastError());
        }
    }
}
