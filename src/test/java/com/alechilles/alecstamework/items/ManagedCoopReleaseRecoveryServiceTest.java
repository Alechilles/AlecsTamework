package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentResult;
import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentStatus;
import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.RefreshResult;
import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.RefreshStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRecoveryService.RecoveryOutcome;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Adoption;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Resolution;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for restart-safe reconstruction of release spawn claims. */
class ManagedCoopReleaseRecoveryServiceTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final String PROFILE = "profile-a";
    private static final String COOP = "coop_chicken";
    private static final String HASH = ManagedCoopCaptureClaimValidator.snapshotSha256("{}");
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final UUID TARGET = new UUID(0L, 2L);

    @Test
    void spawnClaimedAndProjectionCreatedRebuildTheSameOriginalClaim() {
        for (Fixture fixture : List.of(
                fixture(OperationState.SPAWN_CLAIMED),
                fixture(OperationState.PROJECTION_CREATED))) {
            RecoveryOutcome outcome = fixture.service().resume(fixture.operation()).join();

            assertTrue(outcome.ready());
            assertEquals(ManagedCoopReleaseRecoveryService.Status.READY, outcome.status());
            assertEquals(OperationState.SPAWN_CLAIMED, outcome.spawnClaim().durableState());
            assertEquals(1L, outcome.spawnClaim().operationGeneration());
            assertTrue(outcome.spawnClaim().spawnRequired());
            assertNull(outcome.spawnClaim().actualTargetUuid());
            assertEquals(TARGET, outcome.spawnClaim().plannedTargetUuid());
            assertEquals(SOURCE, outcome.spawnClaim().sourceNpcUuid());
            assertEquals(fixture.resident(), outcome.resident());
            assertNotNull(outcome.projectionToken());
        }
    }

    /** Regression: add+unload after restart recovery must invalidate the queued world projection. */
    @Test
    void persistedAbsenceTokenSurvivesRecoveryAndIsRecheckedAtProjectionTime() {
        Fixture fixture = fixture(OperationState.SPAWN_CLAIMED);
        boolean[] current = {true};
        ManagedCoopPersistedProjectionRecovery persisted =
                new ManagedCoopPersistedProjectionRecovery() {
                    @Override
                    public Resolution resolve(OperationRecord operation, ResidentRecord resident) {
                        return Resolution.absent(7L, 11L);
                    }

                    @Override
                    public boolean current(Resolution resolution) {
                        return current[0]
                                && resolution.evidenceRevision() == 7L
                                && resolution.loadedIdentityRevision() == 11L;
                    }

                    @Override
                    public CompletableFuture<Adoption> adopt(
                            OperationRecord operation,
                            ManagedCoopReleaseCoordinator.SpawnReady claim,
                            ResidentRecord resident,
                            Resolution projection) {
                        throw new AssertionError("absent projection must not adopt");
                    }
                };
        ManagedCoopReleaseRecoveryService service = fixture.service(
                (operationId, generation, nowMs) -> {
                    throw new AssertionError("claim was not expected");
                },
                () -> {
                    throw new AssertionError("refresh was not expected");
                },
                persisted);

        RecoveryOutcome recovered = service.resume(fixture.operation()).join();

        assertTrue(recovered.ready());
        assertNotNull(recovered.projectionToken());
        assertTrue(service.projectionCurrent(recovered.projectionToken()));
        current[0] = false;
        assertFalse(service.projectionCurrent(recovered.projectionToken()));
    }

    @Test
    void preparedOperationIsClaimedRefreshedAndThenPublished() {
        Fixture fixture = fixture(OperationState.PREPARED);
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        OperationRecord claimed = operation(OperationState.SPAWN_CLAIMED);
        ManagedCoopReleaseRecoveryService service = fixture.service(
                (operationId, generation, nowMs) -> {
                    claims.incrementAndGet();
                    assertEquals(fixture.operation().operationId(), operationId);
                    assertEquals(0L, generation);
                    assertEquals(-50L, nowMs);
                    return CompletableFuture.completedFuture(new MutationResult(
                            MutationStatus.APPLIED, claimed, null));
                },
                () -> {
                    refreshes.incrementAndGet();
                    assertTrue(fixture.operationIndex().rebuild(
                            ManagedCoopReadResult.loaded(List.of(claimed))).rebuilt());
                    return refreshed(fixture);
                }
        );

        RecoveryOutcome outcome = service.resume(fixture.operation()).join();

        assertTrue(outcome.ready());
        assertEquals(1, claims.get());
        assertEquals(1, refreshes.get());
        assertEquals(OperationState.SPAWN_CLAIMED, outcome.spawnClaim().durableState());
    }

    @Test
    void duplicatePreparedResumeSharesTheSingleClaimGate() {
        Fixture fixture = fixture(OperationState.PREPARED);
        CompletableFuture<MutationResult> pending = new CompletableFuture<>();
        OperationRecord claimed = operation(OperationState.SPAWN_CLAIMED);
        ManagedCoopReleaseRecoveryService service = fixture.service(
                (operationId, generation, nowMs) -> pending,
                () -> {
                    fixture.operationIndex().rebuild(
                            ManagedCoopReadResult.loaded(List.of(claimed)));
                    return refreshed(fixture);
                }
        );

        CompletableFuture<RecoveryOutcome> first = service.resume(fixture.operation());
        RecoveryOutcome duplicate = service.resume(fixture.operation()).join();
        pending.complete(new MutationResult(MutationStatus.APPLIED, claimed, null));

        assertEquals(ManagedCoopReleaseRecoveryService.Status.DEDUPLICATED, duplicate.status());
        assertTrue(first.join().ready());
    }

    @Test
    void untrustedStaleOrNonCanonicalEvidenceFailsClosed() {
        Fixture untrusted = fixture(OperationState.SPAWN_CLAIMED);
        untrusted.trusted()[0] = false;
        assertEquals(ManagedCoopReleaseRecoveryService.Status.FAILED,
                untrusted.service().resume(untrusted.operation()).join().status());

        Fixture stale = fixture(OperationState.SPAWN_CLAIMED);
        OperationRecord copied = copyOperation(stale.operation(), stale.operation().operationId(), 9L);
        assertEquals(ManagedCoopReleaseRecoveryService.Status.FAILED,
                stale.service().resume(copied).join().status());

        Fixture canonical = fixture(OperationState.SPAWN_CLAIMED);
        OperationRecord wrongId = copyOperation(canonical.operation(),
                "managed-coop-release:" + "f".repeat(64), canonical.operation().generation());
        canonical.operationIndex().rebuild(ManagedCoopReadResult.loaded(List.of(wrongId)));
        assertEquals(ManagedCoopReleaseRecoveryService.Status.FAILED,
                canonical.service().resume(wrongId).join().status());
    }

    @Test
    void exactPersistedProjectionIsAdoptedBeforeAnySpawnClaimIsPublished() {
        Fixture fixture = fixture(OperationState.SPAWN_CLAIMED);
        AtomicInteger adoptions = new AtomicInteger();
        ManagedCoopPersistedProjectionRecovery persisted =
                new ManagedCoopPersistedProjectionRecovery() {
                    @Override
                    public Resolution resolve(OperationRecord operation, ResidentRecord resident) {
                        return Resolution.exact("world", 0, 0, 7L);
                    }

                    @Override
                    public boolean current(Resolution resolution) {
                        return resolution.evidenceRevision() == 7L;
                    }

                    @Override
                    public CompletableFuture<Adoption> adopt(
                            OperationRecord operation,
                            ManagedCoopReleaseCoordinator.SpawnReady claim,
                            ResidentRecord resident,
                            Resolution projection) {
                        adoptions.incrementAndGet();
                        assertEquals(TARGET, claim.plannedTargetUuid());
                        return CompletableFuture.completedFuture(
                                Adoption.adopted("persisted-projection-adopted"));
                    }
                };
        ManagedCoopReleaseRecoveryService service = fixture.service(
                (operationId, generation, nowMs) -> {
                    throw new AssertionError("SPAWN_CLAIMED must not be claimed again");
                },
                () -> {
                    throw new AssertionError("refresh was not expected");
                },
                persisted);

        RecoveryOutcome outcome = service.resume(fixture.operation()).join();

        assertEquals(ManagedCoopReleaseRecoveryService.Status.DEDUPLICATED, outcome.status());
        assertEquals("persisted-projection-adopted", outcome.detail());
        assertEquals(1, adoptions.get());
        assertNull(outcome.spawnClaim());
    }

    private static Fixture fixture(OperationState state) {
        ResidentRecord resident = resident();
        OperationRecord operation = operation(state);
        ManagedCoopResidentIndex residentIndex = new ManagedCoopResidentIndex();
        assertTrue(residentIndex.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority())),
                ManagedCoopReadResult.loaded(List.of(resident))).rebuilt());
        ManagedCoopLifecycleOperationIndex operationIndex =
                new ManagedCoopLifecycleOperationIndex();
        assertTrue(operationIndex.rebuild(
                ManagedCoopReadResult.loaded(List.of(operation))).rebuilt());
        boolean[] trusted = {true};
        return new Fixture(resident, operation, residentIndex, operationIndex, trusted);
    }

    private static ResidentRecord resident() {
        return new ResidentRecord(
                ManagedCoopCaptureClaimValidator.residentId(PROFILE),
                AUTHORITY,
                COOP,
                0,
                PROFILE,
                "mob_chicken",
                SOURCE,
                SOURCE,
                null,
                "{}",
                HASH,
                1,
                ResidentState.RELEASING,
                1L,
                true,
                -100L,
                0L,
                -100L,
                -90L
        );
    }

    private static AuthorityRecord authority() {
        return new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, COOP, AuthorityState.TWORK_MANAGED,
                true, 1, -100L, -90L, null);
    }

    private static OperationRecord operation(OperationState state) {
        long generation = switch (state) {
            case PREPARED -> 0L;
            case SPAWN_CLAIMED -> 1L;
            case PROJECTION_CREATED -> 2L;
            default -> throw new IllegalArgumentException("unsupported fixture state");
        };
        UUID actual = state == OperationState.PROJECTION_CREATED ? TARGET : null;
        String operationId = operationId();
        return new OperationRecord(
                operationId, OperationKind.RELEASE, PROFILE, AUTHORITY, COOP, 0,
                null, TARGET, actual, state, HASH, 0L, generation, 0, true,
                -100L, -90L, 0L, null);
    }

    private static String operationId() {
        String residentId = ManagedCoopCaptureClaimValidator.residentId(PROFILE);
        String identity = token(residentId)
                + token(PROFILE)
                + token(AUTHORITY.authorityId())
                + token(COOP)
                + token("0")
                + token(SOURCE.toString())
                + token(TARGET.toString())
                + token(HASH)
                + token("0");
        return "managed-coop-release:"
                + ManagedCoopCaptureClaimValidator.snapshotSha256(identity);
    }

    private static String token(String value) {
        return value.length() + ":" + value;
    }

    private static OperationRecord copyOperation(OperationRecord source,
                                                 String operationId,
                                                 long generation) {
        return new OperationRecord(
                operationId, source.kind(), source.profileId(), source.authorityKey(),
                source.coopId(), source.residentSlot(), source.sourceNpcUuid(),
                source.plannedTargetUuid(), source.actualTargetUuid(), source.state(),
                source.snapshotHash(), source.expectedResidentGeneration(), generation,
                source.retryCount(), source.active(), source.createdAtMs(), source.updatedAtMs(),
                source.completedAtMs(), source.lastError());
    }

    private static RefreshResult refreshed(Fixture fixture) {
        long residentRevision = fixture.residentIndex().snapshot().revision();
        long operationRevision = fixture.operationIndex().snapshot().revision();
        return new RefreshResult(
                RefreshStatus.REFRESHED,
                residentRevision,
                operationRevision,
                new ComponentResult(ComponentStatus.REFRESHED, residentRevision, null),
                new ComponentResult(ComponentStatus.REFRESHED, operationRevision, null),
                null
        );
    }

    private record Fixture(ResidentRecord resident,
                           OperationRecord operation,
                           ManagedCoopResidentIndex residentIndex,
                           ManagedCoopLifecycleOperationIndex operationIndex,
                           boolean[] trusted) {
        private ManagedCoopReleaseRecoveryService service() {
            return service(
                    (operationId, generation, nowMs) -> {
                        throw new AssertionError("claim was not expected");
                    },
                    () -> {
                        throw new AssertionError("refresh was not expected");
                    }
            );
        }

        private ManagedCoopReleaseRecoveryService service(
                ManagedCoopReleaseRecoveryService.ClaimGateway claims,
                ManagedCoopReleaseRecoveryService.RefreshGateway refresh) {
            return service(claims, refresh, ManagedCoopPersistedProjectionRecovery.passthrough());
        }

        private ManagedCoopReleaseRecoveryService service(
                ManagedCoopReleaseRecoveryService.ClaimGateway claims,
                ManagedCoopReleaseRecoveryService.RefreshGateway refresh,
                ManagedCoopPersistedProjectionRecovery persisted) {
            return new ManagedCoopReleaseRecoveryService(
                    residentIndex,
                    operationIndex,
                    () -> trusted[0],
                    claims,
                    refresh,
                    persisted,
                    () -> -50L
            );
        }
    }
}
