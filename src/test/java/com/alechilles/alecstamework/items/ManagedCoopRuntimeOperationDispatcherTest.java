package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.ReleaseOutcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that projection callbacks cannot run until the durable release claim says SPAWN_READY. */
class ManagedCoopRuntimeOperationDispatcherTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);

    @Test
    void releaseProjectsOnlyAfterSpawnReadyAndCarriesCopiedSite() throws Exception {
        ManagedCoopContext context = context();
        ResidentRecord resident = resident(context);
        AtomicReference<ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionCommand> projected =
                new AtomicReference<>();
        AtomicReference<ManagedCoopReleaseCoordinator.ReleaseAttempt> attempted =
                new AtomicReference<>();
        ManagedCoopRuntimeOperationDispatcher dispatcher = dispatcher(
                attempt -> {
                    attempted.set(attempt);
                    return CompletableFuture.completedFuture(new ReleaseOutcome(
                            ManagedCoopReleaseCoordinator.OutcomeStatus.SPAWN_READY,
                            ready(resident), null));
                },
                command -> {
                    projected.set(command);
                    return CompletableFuture.completedFuture(
                            new ManagedCoopReleaseSpawnOrchestrator.Outcome(
                                    ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED,
                                    PLANNED, true, true, null));
                });

        var result = dispatcher.release(context, resident, 100L).join();

        assertEquals(DispatchStatus.RELEASED, result.status());
        assertEquals("release-op", result.operationId());
        assertEquals(PLANNED, attempted.get().plannedTargetUuid());
        assertNotNull(projected.get());
        assertEquals("world", projected.get().site().worldName());
        assertEquals(1, projected.get().site().blockX());
        assertEquals(resident, projected.get().resident());
    }

    @Test
    void failedOrAlreadyProjectedClaimNeverInvokesProjectionGateway() throws Exception {
        ManagedCoopContext context = context();
        ResidentRecord resident = resident(context);
        AtomicInteger projectionCalls = new AtomicInteger();
        var projection = (ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionGateway) command -> {
            projectionCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("projection must stay closed"));
        };
        ManagedCoopRuntimeOperationDispatcher failed = dispatcher(
                ignored -> CompletableFuture.completedFuture(new ReleaseOutcome(
                        ManagedCoopReleaseCoordinator.OutcomeStatus.FAILED, null, "claim_failed")),
                projection);
        ManagedCoopRuntimeOperationDispatcher replay = dispatcher(
                ignored -> CompletableFuture.completedFuture(new ReleaseOutcome(
                        ManagedCoopReleaseCoordinator.OutcomeStatus.ALREADY_PROJECTED,
                        alreadyProjected(resident), null)),
                projection);

        var failedResult = failed.release(context, resident, 100L).join();
        var replayResult = replay.release(context, resident, 100L).join();

        assertEquals(DispatchStatus.RELEASE_FAILED, failedResult.status());
        assertEquals(DispatchStatus.RELEASE_DEDUPLICATED, replayResult.status());
        assertEquals(0, projectionCalls.get());
        assertFalse(replayResult.detail() != null && replayResult.detail().contains("spawn"));
    }

    @Test
    void releaseRemainsProcessOwnedUntilProjectionFinishes() throws Exception {
        ManagedCoopContext context = context();
        ResidentRecord resident = resident(context);
        CompletableFuture<ManagedCoopReleaseSpawnOrchestrator.Outcome> projection =
                new CompletableFuture<>();
        ManagedCoopRuntimeOperationDispatcher dispatcher = dispatcher(
                ignored -> CompletableFuture.completedFuture(new ReleaseOutcome(
                        ManagedCoopReleaseCoordinator.OutcomeStatus.SPAWN_READY,
                        ready(resident), null)),
                ignored -> projection);

        CompletableFuture<ManagedCoopRuntimeOperationDispatcher.DispatchOutcome> release =
                dispatcher.release(context, resident, 100L);
        var duplicate = dispatcher.release(context, resident, 101L).join();

        assertTrue(dispatcher.releaseInFlight(resident.profileId()));
        assertEquals(DispatchStatus.RELEASE_DEDUPLICATED, duplicate.status());
        assertEquals("managed_coop_release_profile_already_in_flight", duplicate.detail());

        projection.complete(new ManagedCoopReleaseSpawnOrchestrator.Outcome(
                ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED,
                PLANNED, true, true, null));

        assertEquals(DispatchStatus.RELEASED, release.join().status());
        assertFalse(dispatcher.releaseInFlight(resident.profileId()));
    }

    @Test
    void differentCoopReleaseWaitsForCurrentLifecycleProjection() throws Exception {
        ManagedCoopContext context = context();
        ResidentRecord firstResident = resident(context);
        ResidentRecord secondResident = resident(context, "resident-2", "profile-2");
        CompletableFuture<ManagedCoopReleaseSpawnOrchestrator.Outcome> projection =
                new CompletableFuture<>();
        AtomicInteger claims = new AtomicInteger();
        ManagedCoopLifecycleMutationGate gate = new ManagedCoopLifecycleMutationGate();
        ManagedCoopRuntimeOperationDispatcher dispatcher = dispatcher(
                attempt -> {
                    claims.incrementAndGet();
                    return CompletableFuture.completedFuture(new ReleaseOutcome(
                            ManagedCoopReleaseCoordinator.OutcomeStatus.SPAWN_READY,
                            ready(attempt.resident()), null));
                },
                ignored -> projection,
                gate);

        CompletableFuture<ManagedCoopRuntimeOperationDispatcher.DispatchOutcome> first =
                dispatcher.release(context, firstResident, 100L);
        var waiting = dispatcher.release(context, secondResident, 101L).join();

        assertEquals(DispatchStatus.RELEASE_DEDUPLICATED, waiting.status());
        assertEquals("managed_coop_lifecycle_operation_in_flight", waiting.detail());
        assertEquals(1, claims.get());

        projection.complete(new ManagedCoopReleaseSpawnOrchestrator.Outcome(
                ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED,
                PLANNED, true, true, null));
        assertEquals(DispatchStatus.RELEASED, first.join().status());
    }

    @Test
    void removedCoopReleaseCannotBypassStartupAuthorityGate() throws Exception {
        AtomicInteger claims = new AtomicInteger();
        ManagedCoopContext context = context();
        ResidentRecord resident = resident(context);
        ManagedCoopRuntimeOperationDispatcher dispatcher =
                new ManagedCoopRuntimeOperationDispatcher(
                        (store, ref, ignoredContext, candidate) ->
                                CompletableFuture.failedFuture(
                                        new AssertionError("capture not used")),
                        ready -> CompletableFuture.failedFuture(
                                new AssertionError("retirement not used")),
                        attempt -> {
                            claims.incrementAndGet();
                            return CompletableFuture.failedFuture(
                                    new AssertionError("release claim must stay closed"));
                        },
                        command -> CompletableFuture.failedFuture(
                                new AssertionError("projection must stay closed")),
                        () -> PLANNED,
                        new ManagedCoopLifecycleMutationGate(() -> false));

        var outcome = dispatcher.release(
                ManagedCoopRuntimeOperationDispatcher.ReleaseSite.copyOf(context),
                resident,
                100L).join();

        assertEquals(DispatchStatus.RELEASE_DEDUPLICATED, outcome.status());
        assertEquals("managed_coop_runtime_authority_not_ready", outcome.detail());
        assertEquals(0, claims.get());
    }

    @Test
    void captureIsReportedOnlyAfterExactSourceRetirementCompletes() {
        AtomicReference<RetirementReady> retired = new AtomicReference<>();
        ManagedCoopRuntimeOperationDispatcher dispatcher = captureDispatcher(ready -> {
            retired.set(ready);
            return CompletableFuture.completedFuture(new Outcome(
                    ManagedCoopCaptureSourceRetirementService.OutcomeStatus.COMPLETED,
                    null, null));
        });
        RetirementReady ready = retirementReady();

        var result = dispatcher.afterCapture(new CaptureOutcome(
                ManagedCoopCaptureCoordinator.OutcomeStatus.RETIREMENT_READY,
                ready, null)).join();

        assertEquals(DispatchStatus.CAPTURED, result.status());
        assertEquals("capture-op", result.operationId());
        assertEquals(ready, retired.get());
    }

    @Test
    void rejectedCaptureNeverCallsSourceRetirement() {
        AtomicInteger retirementCalls = new AtomicInteger();
        ManagedCoopRuntimeOperationDispatcher dispatcher = captureDispatcher(ready -> {
            retirementCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new AssertionError("retirement must stay closed"));
        });

        var duplicate = dispatcher.afterCapture(new CaptureOutcome(
                ManagedCoopCaptureCoordinator.OutcomeStatus.DEDUPLICATED,
                null, "duplicate")).join();
        var failed = dispatcher.afterCapture(new CaptureOutcome(
                ManagedCoopCaptureCoordinator.OutcomeStatus.FAILED,
                null, "failed")).join();

        assertEquals(DispatchStatus.CAPTURE_DEDUPLICATED, duplicate.status());
        assertEquals(DispatchStatus.CAPTURE_FAILED, failed.status());
        assertEquals(0, retirementCalls.get());
    }

    private static ManagedCoopRuntimeOperationDispatcher dispatcher(
            ManagedCoopRuntimeOperationDispatcher.ReleaseClaimGateway releases,
            ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionGateway projections) {
        return dispatcher(releases, projections, new ManagedCoopLifecycleMutationGate());
    }

    private static ManagedCoopRuntimeOperationDispatcher dispatcher(
            ManagedCoopRuntimeOperationDispatcher.ReleaseClaimGateway releases,
            ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionGateway projections,
            ManagedCoopLifecycleMutationGate gate) {
        return new ManagedCoopRuntimeOperationDispatcher(
                (store, ref, context, candidate) -> CompletableFuture.failedFuture(
                        new AssertionError("capture not used")),
                ready -> CompletableFuture.failedFuture(new AssertionError("retirement not used")),
                releases,
                projections,
                () -> PLANNED,
                gate);
    }

    private static ManagedCoopRuntimeOperationDispatcher captureDispatcher(
            ManagedCoopRuntimeOperationDispatcher.RetirementGateway retirements) {
        return new ManagedCoopRuntimeOperationDispatcher(
                (store, ref, context, candidate) -> CompletableFuture.failedFuture(
                        new AssertionError("capture gateway not used directly")),
                retirements,
                attempt -> CompletableFuture.failedFuture(
                        new AssertionError("release not used")),
                command -> CompletableFuture.failedFuture(
                        new AssertionError("projection not used")),
                () -> PLANNED);
    }

    private static RetirementReady retirementReady() {
        ManagedCoopContext context;
        try {
            context = context();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return new RetirementReady(
                SOURCE, "profile", "resident", "capture-op", context.authorityKey(),
                context.coopId(), 0, "a".repeat(64), 2L,
                OperationState.SOURCE_RETIRE_REQUESTED, 2L);
    }

    private static SpawnReady ready(ResidentRecord resident) {
        return spawnReady(resident, OperationState.SPAWN_CLAIMED, true);
    }

    private static SpawnReady alreadyProjected(ResidentRecord resident) {
        return spawnReady(resident, OperationState.PROJECTION_CREATED, false);
    }

    private static SpawnReady spawnReady(ResidentRecord resident,
                                         OperationState state,
                                         boolean spawnRequired) {
        return new SpawnReady(
                "release-op", resident.profileId(), resident.residentId(),
                resident.authorityKey(), resident.coopId(), resident.residentSlot(),
                SOURCE, PLANNED, spawnRequired ? null : PLANNED,
                resident.snapshotHash(), resident.generation(), resident.generation() + 1L,
                1L, state, 2L, spawnRequired);
    }

    private static ManagedCoopContext context() throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "test");
        set(config, "enabled", true);
        set(config, "coopId", "coop_chicken");
        set(config.getIdentityRules(), "preserveUUID", false);
        var offset = config.getLifecycleRules().getResidentSpawnOffset();
        set(offset, "x", 1.0);
        set(offset, "y", 2.0);
        set(offset, "z", 3.0);
        return new ManagedCoopContext(
                new ManagedCoopAuthorityKey("world", 1, 2, 3),
                "coop_chicken", 4, config, null);
    }

    private static ResidentRecord resident(ManagedCoopContext context) {
        return resident(context, "resident", "profile");
    }

    private static ResidentRecord resident(
            ManagedCoopContext context,
            String residentId,
            String profileId) {
        return new ResidentRecord(
                residentId, context.authorityKey(), context.coopId(), 0,
                profileId, "hen", SOURCE, SOURCE, null,
                "{}", "a".repeat(64), 1, ResidentState.HOUSED,
                1L, true, 1L, 0L, 1L, 1L);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
