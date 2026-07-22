package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.items.capturepolicy.runtime.SqliteCaptureAttemptJournal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavioral probability/idempotency coverage for every shipped capture entry-point shape. */
class CaptureEntryPointProbabilityContractTest {
    private static final long NOW = 50_000L;

    @TempDir
    Path tempDir;

    @Test
    void channelOwnerAndWildCallbacksResolveOneEntropySamplePerPropagatedAttempt() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("capture-entry-points.sqlite"))) {
            AtomicInteger entropyCalls = new AtomicInteger();
            CaptureAttemptCoordinator coordinator = coordinator(harness, entropyCalls);

            for (EntryPoint entryPoint : EntryPoint.values()) {
                CaptureAttemptCoordinator.AttemptRequest prepared = request(entryPoint);

                CaptureAttemptCoordinator.ResolutionResult first = coordinator.resolve(prepared).get();
                CaptureAttemptCoordinator.ResolutionResult duplicate = coordinator.resolve(prepared).get();

                assertEquals(CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL, first.status(),
                        entryPoint.name());
                assertEquals(CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL, duplicate.status(),
                        entryPoint.name());
                assertEquals(prepared.attemptId(), duplicate.attemptId(), entryPoint.name());
                assertEquals(first.attempt().identity(), duplicate.attempt().identity(),
                        entryPoint.name());
                assertEquals(first.attempt().state(), duplicate.attempt().state(),
                        entryPoint.name());
                assertEquals(first.attempt().resolution(), duplicate.attempt().resolution(),
                        entryPoint.name());
            }

            assertEquals(EntryPoint.values().length, entropyCalls.get(),
                    "each entry point may sample once, while its duplicate callback must replay");
        }
    }

    @Test
    void probabilityRequestCannotReachEntropyWithoutPrepareTimeAttemptIdentity() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("capture-missing-id.sqlite"))) {
            AtomicInteger entropyCalls = new AtomicInteger();
            CaptureAttemptCoordinator coordinator = coordinator(harness, entropyCalls);
            CaptureAttemptCoordinator.AttemptRequest valid = request(EntryPoint.CHANNELED);

            assertThrows(NullPointerException.class, () -> new CaptureAttemptCoordinator.AttemptRequest(
                    null, valid.operationId(), valid.callerNamespace(), valid.idempotencyKey(),
                    valid.actorUuid(), valid.targetNpcUuid(), valid.profileId(),
                    valid.expectedProfileRevision(), valid.sourceItemId(), valid.roleId(),
                    valid.sourceContextJson(), valid.spawnerConfigId(), valid.spawnerConfigRevision(),
                    valid.itemMechanics(), valid.currentHealth(), valid.maximumHealth(),
                    valid.requirementContext(), valid.expectedRequirementGeneration(),
                    valid.populationOperationId(), valid.expiresAtMs()));
            assertEquals(0, entropyCalls.get());
        }
    }

    private static CaptureAttemptCoordinator coordinator(
            HydragonPersistenceTestHarness harness, AtomicInteger entropyCalls) {
        CaptureRequirementRuntime requirements = new CaptureRequirementRuntime() {
            @Override
            public long captureRequirementGeneration() {
                return 0L;
            }

            @Override
            public CaptureRequirementDecision evaluateCaptureRequirement(
                    com.alechilles.alecstamework.api.CaptureRequirementSpec requirement,
                    CaptureRequirementContext context,
                    long expectedGeneration) {
                return CaptureRequirementDecision.allow();
            }
        };
        return new CaptureAttemptCoordinator(
                new SqliteCaptureAttemptJournal(
                        new CaptureAttemptRepository(harness.connections, harness.queue)),
                new CapturePolicyRegistry(),
                new SpawnerCaptureChanceService(requirements),
                ignored -> {
                    entropyCalls.incrementAndGet();
                    return 0.9D;
                },
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                ignored -> { });
    }

    private static CaptureAttemptCoordinator.AttemptRequest request(EntryPoint entryPoint) {
        UUID attemptId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String route = entryPoint.name().toLowerCase(java.util.Locale.ROOT);
        return new CaptureAttemptCoordinator.AttemptRequest(
                attemptId,
                UUID.randomUUID(),
                null,
                null,
                actor,
                target,
                null,
                -1L,
                "Test_Stone_" + route,
                "Test_Role",
                "{\"version\":1,\"world\":\"test-world\",\"inventory\":\"hotbar\","
                        + "\"slot\":2,\"fingerprint\":\"" + route + "-fingerprint\"}",
                "test-spawner-" + route,
                1L,
                new ItemFeatureConfig.CaptureItemMechanics(
                        CaptureChanceMode.PROBABILITY, 1, 0.5D, 0.0D,
                        0.0D, 1.0D, 0, null, null),
                5.0D,
                10.0D,
                new CaptureRequirementContext(
                        attemptId, CaptureRequirementPhase.FINAL_REVALIDATION,
                        actor, target, null, "Test_Role", "test-world",
                        "Test_Stone_" + route, 0.5D,
                        CaptureRequirementContext.UNKNOWN_PROFILE_REVISION),
                0L,
                null,
                NOW + 30_000L);
    }

    private enum EntryPoint {
        CHANNELED,
        TAMEWORK_CAPTURE_OWNER,
        TAMEWORK_CAPTURE_WILD
    }
}
