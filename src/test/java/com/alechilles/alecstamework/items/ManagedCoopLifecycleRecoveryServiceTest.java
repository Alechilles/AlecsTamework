package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionCommand;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart regression coverage for exact source routing and release-context gating. */
class ManagedCoopLifecycleRecoveryServiceTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);
    private static final String PROFILE = "profile";
    private static final String COOP = "coop_chicken";

    @Test
    void slotCommittedCaptureAdvancesRefreshesAndUsesEntityRetirement() {
        Fixture fixture = captureFixture(OperationState.SLOT_COMMITTED, false);
        OperationRecord advanced = captureOperation(
                fixture.resident, OperationState.SOURCE_RETIRE_REQUESTED);
        AtomicInteger advances = new AtomicInteger();
        AtomicInteger entityCalls = new AtomicInteger();
        ManagedCoopLifecycleRecoveryService service = service(
                fixture,
                (operationId, generation, nowMs) -> {
                    advances.incrementAndGet();
                    assertEquals(fixture.operation.operationId(), operationId);
                    assertEquals(1L, generation);
                    assertEquals(-50L, nowMs);
                    return CompletableFuture.completedFuture(new MutationResult(
                            MutationStatus.APPLIED, advanced, null));
                },
                () -> {
                    fixture.rebuild(fixture.resident, advanced);
                    return new ManagedCoopLifecycleRecoveryService.RefreshDecision(true, null);
                },
                ready -> {
                    entityCalls.incrementAndGet();
                    assertEquals(OperationState.SOURCE_RETIRE_REQUESTED,
                            ready.durableState());
                    return CompletableFuture.completedFuture(
                            new ManagedCoopCaptureSourceRetirementService.Outcome(
                                    ManagedCoopCaptureSourceRetirementService.OutcomeStatus.COMPLETED,
                                    null, null));
                },
                (ready, resident) -> CompletableFuture.completedFuture(
                        new ManagedCoopItemCaptureRecoveryService.Outcome(
                                ManagedCoopItemCaptureRecoveryService.RecoveryStatus.FAILED,
                                "wrong_source_route"))
        );

        ManagedCoopLifecycleRecoveryService.Outcome outcome =
                service.recover("world", List.of()).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.CAPTURE_COMPLETED,
                outcome.status());
        assertEquals(1, advances.get());
        assertEquals(1, entityCalls.get());
    }

    @Test
    void capturedItemSourceNeverEntersEntityAbsenceRetirement() {
        Fixture fixture = captureFixture(OperationState.SOURCE_RETIRE_REQUESTED, true);
        AtomicInteger entityCalls = new AtomicInteger();
        AtomicInteger itemCalls = new AtomicInteger();
        ManagedCoopLifecycleRecoveryService service = service(
                fixture,
                unexpectedAdvance(),
                unexpectedRefresh(),
                ready -> {
                    entityCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                (ready, resident) -> {
                    itemCalls.incrementAndGet();
                    assertTrue(resident.snapshotJson().contains(
                            ManagedCoopCaptureSourceEvidence.SNAPSHOT_FIELD));
                    return CompletableFuture.completedFuture(
                            new ManagedCoopItemCaptureRecoveryService.Outcome(
                                    ManagedCoopItemCaptureRecoveryService.RecoveryStatus.COMPLETED,
                                    null));
                }
        );

        ManagedCoopLifecycleRecoveryService.Outcome outcome =
                service.recover("world", List.of()).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.CAPTURE_COMPLETED,
                outcome.status());
        assertEquals(0, entityCalls.get());
        assertEquals(1, itemCalls.get());
    }

    @Test
    void repeatedSweepWhileRetirementIsPendingDoesNotStartASecondRetirement() {
        Fixture fixture = captureFixture(OperationState.SOURCE_RETIRE_REQUESTED, false);
        CompletableFuture<ManagedCoopCaptureSourceRetirementService.Outcome> retirement =
                new CompletableFuture<>();
        AtomicInteger entityCalls = new AtomicInteger();
        ManagedCoopLifecycleRecoveryService service = service(
                fixture,
                unexpectedAdvance(),
                unexpectedRefresh(),
                ready -> {
                    entityCalls.incrementAndGet();
                    return retirement;
                },
                (ready, resident) -> CompletableFuture.completedFuture(null));

        CompletableFuture<ManagedCoopLifecycleRecoveryService.Outcome> first =
                service.recover("world", List.of());
        ManagedCoopLifecycleRecoveryService.Outcome duplicate =
                service.recover("world", List.of()).join();
        retirement.complete(new ManagedCoopCaptureSourceRetirementService.Outcome(
                ManagedCoopCaptureSourceRetirementService.OutcomeStatus.COMPLETED,
                null, null));

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.DEDUPLICATED,
                duplicate.status());
        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.CAPTURE_COMPLETED,
                first.join().status());
        assertEquals(1, entityCalls.get());
    }

    @Test
    void releaseWaitsForExactLoadedContextThenReusesOriginalClaim() throws Exception {
        Fixture fixture = releaseFixture();
        AtomicInteger recoveryCalls = new AtomicInteger();
        AtomicReference<ReleaseProjectionCommand> projected = new AtomicReference<>();
        ManagedCoopReleaseCoordinator.SpawnReady claim = releaseClaim(
                fixture, fixture.operation.operationId());
        ManagedCoopLifecycleRecoveryService service = new ManagedCoopLifecycleRecoveryService(
                fixture.evidence(), unexpectedAdvance(), unexpectedRefresh(),
                ready -> CompletableFuture.completedFuture(null),
                (ready, resident) -> CompletableFuture.completedFuture(null),
                operation -> {
                    recoveryCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ManagedCoopReleaseRecoveryService.RecoveryOutcome(
                                    ManagedCoopReleaseRecoveryService.Status.READY,
                                    claim, fixture.resident, null));
                },
                command -> {
                    projected.set(command);
                    return CompletableFuture.completedFuture(
                            new ManagedCoopReleaseSpawnOrchestrator.Outcome(
                                    ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED,
                                    PLANNED, false, true, null));
                },
                () -> -50L
        );

        ManagedCoopLifecycleRecoveryService.Outcome waiting =
                service.recover("world", List.of()).join();
        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.WAITING,
                waiting.status());
        assertEquals(0, recoveryCalls.get());

        ManagedCoopLifecycleRecoveryService.Outcome resumed =
                service.recover("world", List.of(context())).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.RELEASE_COMPLETED,
                resumed.status());
        assertEquals(1, recoveryCalls.get());
        assertNotNull(projected.get());
        assertEquals(AUTHORITY.worldName(), projected.get().site().worldName());
        assertEquals(PLANNED, projected.get().claim().plannedTargetUuid());
    }

    @Test
    void disabledRemovedAuthorityRecoversReleaseWithoutPhysicalContext() {
        Fixture fixture = releaseFixture(AuthorityState.DISABLED);
        AtomicInteger recoveryCalls = new AtomicInteger();
        AtomicReference<ReleaseProjectionCommand> projected = new AtomicReference<>();
        ManagedCoopReleaseCoordinator.SpawnReady claim = releaseClaim(
                fixture, fixture.operation.operationId());
        ManagedCoopLifecycleRecoveryService service = new ManagedCoopLifecycleRecoveryService(
                fixture.evidence(), unexpectedAdvance(), unexpectedRefresh(),
                ready -> CompletableFuture.completedFuture(null),
                (ready, resident) -> CompletableFuture.completedFuture(null),
                operation -> {
                    recoveryCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ManagedCoopReleaseRecoveryService.RecoveryOutcome(
                                    ManagedCoopReleaseRecoveryService.Status.READY,
                                    claim, fixture.resident, null));
                },
                command -> {
                    projected.set(command);
                    return CompletableFuture.completedFuture(
                            new ManagedCoopReleaseSpawnOrchestrator.Outcome(
                                    ManagedCoopReleaseSpawnOrchestrator.Status.FINALIZED,
                                    PLANNED, false, true, null));
                },
                () -> -50L);

        ManagedCoopLifecycleRecoveryService.Outcome outcome =
                service.recover("world", List.of()).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.RELEASE_COMPLETED,
                outcome.status());
        assertEquals(1, recoveryCalls.get());
        assertNotNull(projected.get());
        assertEquals(
                ManagedCoopRuntimeOperationDispatcher.ReleaseSitePolicy
                        .EXACT_MANAGED_OR_DISABLED_REMOVAL,
                projected.get().site().policy());
    }

    @Test
    void releaseRecoveryResultMustMatchSelectedOperationBeforeProjection() throws Exception {
        Fixture fixture = releaseFixture();
        AtomicInteger projections = new AtomicInteger();
        ManagedCoopLifecycleRecoveryService service = new ManagedCoopLifecycleRecoveryService(
                fixture.evidence(), unexpectedAdvance(), unexpectedRefresh(),
                ready -> CompletableFuture.completedFuture(null),
                (ready, resident) -> CompletableFuture.completedFuture(null),
                operation -> CompletableFuture.completedFuture(
                        new ManagedCoopReleaseRecoveryService.RecoveryOutcome(
                                ManagedCoopReleaseRecoveryService.Status.READY,
                                releaseClaim(fixture, "different-operation"),
                                fixture.resident, null)),
                command -> {
                    projections.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                () -> -50L);

        ManagedCoopLifecycleRecoveryService.Outcome outcome =
                service.recover("world", List.of(context())).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.BLOCKED,
                outcome.status());
        assertEquals("release_recovery_identity_mismatch", outcome.detail());
        assertEquals(0, projections.get());
    }

    @Test
    void activeImportIsReservedAndNeverSentThroughCaptureOrRelease() {
        Fixture fixture = importFixture();
        ManagedCoopLifecycleRecoveryService service = service(
                fixture, unexpectedAdvance(), unexpectedRefresh(),
                ready -> CompletableFuture.completedFuture(null),
                (ready, resident) -> CompletableFuture.completedFuture(null));

        ManagedCoopLifecycleRecoveryService.Outcome outcome =
                service.recover("world", List.of()).join();

        assertEquals(ManagedCoopLifecycleRecoveryService.RecoveryStatus.RESERVED_IMPORT,
                outcome.status());
    }

    private static ManagedCoopLifecycleRecoveryService service(
            Fixture fixture,
            ManagedCoopLifecycleRecoveryService.CaptureAdvanceGateway advances,
            ManagedCoopLifecycleRecoveryService.RefreshGateway refresh,
            ManagedCoopLifecycleRecoveryService.EntityRetirementGateway entity,
            ManagedCoopLifecycleRecoveryService.ItemRetirementGateway item) {
        return new ManagedCoopLifecycleRecoveryService(
                fixture.evidence(), advances, refresh, entity, item,
                operation -> CompletableFuture.completedFuture(
                        new ManagedCoopReleaseRecoveryService.RecoveryOutcome(
                                ManagedCoopReleaseRecoveryService.Status.FAILED,
                                null, null, "unexpected_release")),
                command -> CompletableFuture.completedFuture(null),
                () -> -50L);
    }

    private static ManagedCoopLifecycleRecoveryService.CaptureAdvanceGateway
    unexpectedAdvance() {
        return (operationId, generation, nowMs) -> CompletableFuture.failedFuture(
                new AssertionError("unexpected capture advance"));
    }

    private static ManagedCoopLifecycleRecoveryService.RefreshGateway unexpectedRefresh() {
        return () -> {
            throw new AssertionError("unexpected refresh");
        };
    }

    private static Fixture captureFixture(OperationState state, boolean itemSource) {
        String json = snapshotJson(itemSource);
        ResidentRecord resident = resident(
                ResidentState.HOUSED, json,
                ManagedCoopCaptureClaimValidator.snapshotSha256(json));
        return new Fixture(resident, captureOperation(resident, state));
    }

    private static OperationRecord captureOperation(
            ResidentRecord resident,
            OperationState state) {
        CaptureRequest provisional = new CaptureRequest(
                "pending", resident.residentId(), AUTHORITY, COOP, 0, PROFILE,
                resident.roleId(), SOURCE, resident.snapshotJson(), resident.snapshotHash(),
                resident.snapshotVersion(), 0L, resident.capturedAtMs());
        String operationId = ManagedCoopCaptureClaimValidator.operationId(provisional);
        long generation = state == OperationState.SLOT_COMMITTED ? 1L : 2L;
        return operation(
                operationId, OperationKind.CAPTURE, state, generation,
                SOURCE, null, resident.snapshotHash());
    }

    private static Fixture releaseFixture() {
        return releaseFixture(AuthorityState.TWORK_MANAGED);
    }

    private static Fixture releaseFixture(AuthorityState authorityState) {
        String json = snapshotJson(false);
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(json);
        ResidentRecord resident = resident(ResidentState.RELEASING, json, hash);
        OperationRecord operation = operation(
                "release-op", OperationKind.RELEASE, OperationState.SPAWN_CLAIMED,
                1L, null, PLANNED, hash);
        return new Fixture(resident, operation, authorityState);
    }

    private static ManagedCoopReleaseCoordinator.SpawnReady releaseClaim(
            Fixture fixture,
            String operationId) {
        return new ManagedCoopReleaseCoordinator.SpawnReady(
                operationId, PROFILE, fixture.resident.residentId(),
                AUTHORITY, COOP, 0, SOURCE, PLANNED, null,
                fixture.operation.snapshotHash(), 0L, 1L, 1L,
                OperationState.SPAWN_CLAIMED, 1L, true);
    }

    private static Fixture importFixture() {
        String json = snapshotJson(false);
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(json);
        ResidentRecord resident = resident(ResidentState.HOUSED, json, hash);
        OperationRecord operation = operation(
                "import-op", OperationKind.IMPORT, OperationState.SLOT_COMMITTED,
                1L, SOURCE, null, hash);
        return new Fixture(resident, operation);
    }

    private static OperationRecord operation(
            String operationId,
            OperationKind kind,
            OperationState state,
            long generation,
            UUID source,
            UUID planned,
            String hash) {
        return new OperationRecord(
                operationId, kind, PROFILE, AUTHORITY, COOP, 0,
                source, planned, null, state, hash,
                0L, generation, 0, true, -100L, -90L, 0L, null);
    }

    private static ResidentRecord resident(
            ResidentState state,
            String json,
            String hash) {
        return new ResidentRecord(
                ManagedCoopCaptureClaimValidator.residentId(PROFILE),
                AUTHORITY, COOP, 0, PROFILE, "mob_chicken",
                SOURCE, SOURCE, null, json, hash, 1, state, 1L, true,
                -100L, 0L, -100L, -90L);
    }

    private static String snapshotJson(boolean itemSource) {
        CoopResidentStateSnapshot snapshot = new CoopResidentStateSnapshot(
                SOURCE, COOP, 0, "mob_chicken",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, -100L);
        String json = new CoopResidentStateSnapshotCodec().encode(snapshot);
        return itemSource
                ? ManagedCoopCaptureSourceEvidence.markCapturedItem(
                        json,
                        new ManagedCoopCaptureSourceEvidence.CapturedItemSource(
                                uuid(9), (short) 2, "Tool_Capture_Crate", "b".repeat(64)))
                : json;
    }

    private static ManagedCoopContext context() throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "test");
        set(config, "enabled", true);
        set(config, "coopId", COOP);
        set(config.getIdentityRules(), "preserveUUID", false);
        return new ManagedCoopContext(AUTHORITY, COOP, 0, config, null);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012d", suffix));
    }

    private static final class Fixture {
        private final ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        private final ManagedCoopLifecycleOperationIndex operations =
                new ManagedCoopLifecycleOperationIndex();
        private ResidentRecord resident;
        private OperationRecord operation;
        private AuthorityState authorityState;

        private Fixture(ResidentRecord resident, OperationRecord operation) {
            this(resident, operation, AuthorityState.TWORK_MANAGED);
        }

        private Fixture(ResidentRecord resident,
                        OperationRecord operation,
                        AuthorityState authorityState) {
            this.authorityState = authorityState;
            rebuild(resident, operation);
        }

        private void rebuild(ResidentRecord nextResident, OperationRecord nextOperation) {
            resident = nextResident;
            operation = nextOperation;
            AuthorityRecord authority = new AuthorityRecord(
                    AUTHORITY.authorityId(), AUTHORITY, COOP,
                    authorityState, true, 1,
                    -100L, -90L, null);
            assertTrue(residents.rebuild(
                    ManagedCoopReadResult.loaded(List.of(authority)),
                    ManagedCoopReadResult.loaded(List.of(resident))).rebuilt());
            assertTrue(operations.rebuild(
                    ManagedCoopReadResult.loaded(List.of(operation))).rebuilt());
        }

        private ManagedCoopLifecycleRecoveryEvidence evidence() {
            return new ManagedCoopLifecycleRecoveryEvidence(
                    new ManagedCoopLifecycleRecoveryPlanner(),
                    residents, operations, () -> true);
        }
    }
}
