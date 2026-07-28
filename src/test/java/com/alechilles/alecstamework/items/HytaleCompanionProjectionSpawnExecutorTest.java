package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.AttemptGateway;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.ProjectionCommand;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.ReceiptResult;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.SpawnAttempt;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.SpawnStatus;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for receipt-first replay and exception-ambiguous projection insertion. */
class HytaleCompanionProjectionSpawnExecutorTest {
    private final HytaleCompanionProjectionSpawnExecutor executor =
            new HytaleCompanionProjectionSpawnExecutor();

    @Test
    void exactReceiptConfirmsBeforeUnsupportedSnapshotResolution() {
        RecordingAttempts attempts = attempts(ReceiptResult.match());
        AtomicInteger resolutions = new AtomicInteger();

        LiveOperationResult result = executor.execute(
                command(),
                () -> {
                    resolutions.incrementAndGet();
                    return failed("snapshot_codec_unsupported");
                },
                attempts
        );

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals("restoration_spawn_receipt_confirmed", result.code());
        assertEquals(0, resolutions.get());
        assertEquals(0, attempts.spawnCalls);
    }

    @Test
    void conflictingReceiptFailsUnknownWithoutDecodeOrSpawn() {
        RecordingAttempts attempts = attempts(ReceiptResult.conflict(null));
        AtomicInteger resolutions = new AtomicInteger();

        LiveOperationResult result = executor.execute(
                command(),
                () -> {
                    resolutions.incrementAndGet();
                    return decoded(snapshot());
                },
                attempts
        );

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals("restoration_spawn_receipt_conflict", result.code());
        assertEquals(0, resolutions.get());
        assertEquals(0, attempts.spawnCalls);
    }

    @Test
    void absentReceiptAndDecodeFailureRemainUnknownWithoutSpawn() {
        RecordingAttempts attempts = attempts(ReceiptResult.absent());

        LiveOperationResult result = executor.execute(
                command(),
                () -> failed("snapshot_decode_failed"),
                attempts
        );

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals("restoration_snapshot_decode_failed", result.code());
        assertEquals(0, attempts.spawnCalls);
    }

    @Test
    void failedSpawnWithMatchingPostProbeConfirmsAmbiguousInsertion() {
        RecordingAttempts attempts = attempts(
                ReceiptResult.absent(),
                ReceiptResult.match()
        );
        attempts.spawnResult =
                SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, null);

        LiveOperationResult result = executor.execute(
                command(),
                () -> decoded(snapshot()),
                attempts
        );

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals("restoration_spawn_receipt_confirmed", result.code());
        assertEquals(1, attempts.spawnCalls);
        assertEquals(2, attempts.probeCalls);
    }

    @Test
    void failedSpawnWithProvenAbsenceIsRetryable() {
        RecordingAttempts attempts = attempts(
                ReceiptResult.absent(),
                ReceiptResult.absent()
        );
        attempts.spawnResult =
                SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, null);

        LiveOperationResult result = executor.execute(
                command(),
                () -> decoded(snapshot()),
                attempts
        );

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals("restoration_spawn_failed", result.code());
    }

    @Test
    void failedSpawnWithConflictingPostProbeIsUnknown() {
        RecordingAttempts attempts = attempts(
                ReceiptResult.absent(),
                ReceiptResult.conflict(null)
        );
        attempts.spawnResult =
                SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, null);

        LiveOperationResult result = executor.execute(
                command(),
                () -> decoded(snapshot()),
                attempts
        );

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals("restoration_spawn_receipt_conflict", result.code());
    }

    @Test
    void identityMismatchStaysUnknownEvenWhenTargetAliasIsAbsent() {
        RecordingAttempts attempts = attempts(
                ReceiptResult.absent(),
                ReceiptResult.absent()
        );
        attempts.spawnResult =
                SpawnAttempt.failed(SpawnStatus.IDENTITY_MISMATCH, null);

        LiveOperationResult result = executor.execute(
                command(),
                () -> decoded(snapshot()),
                attempts
        );

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals("restoration_identity_mismatch", result.code());
    }

    @Test
    void successfulSpawnUsesTheCompleteExactReceiptMarker() {
        RecordingAttempts attempts = attempts(ReceiptResult.absent());

        LiveOperationResult result = executor.execute(
                command(),
                () -> decoded(snapshot()),
                attempts
        );

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals("restoration_spawned", result.code());
        assertEquals(1, attempts.spawnCalls);
        TameworkProjectionIdentityComponent marker = attempts.spawnMarker;
        assertEquals(PROFILE.toString(), marker.getProfileId());
        assertEquals(OPERATION.toString(), marker.getOperationId());
        assertEquals(
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                marker.getProjectionKind()
        );
        assertEquals("receipt-a", marker.getSlotKey());
        assertEquals(SOURCE.value(), marker.getSourceNpcUuid());
        assertEquals(7, marker.getGeneration());
    }

    @Test
    void sourceIdentityAndRequiredRoleAreDecodedStateInvariants() {
        RecordingAttempts wrongSource = attempts(ReceiptResult.absent());
        CoopResidentStateSnapshot mismatched = snapshot(uuid(91), "tamed_test");

        LiveOperationResult sourceResult = executor.execute(
                command(),
                () -> decoded(mismatched),
                wrongSource
        );
        LiveOperationResult roleResult = executor.execute(
                command(),
                () -> decoded(snapshot(SOURCE.value(), " ")),
                attempts(ReceiptResult.absent())
        );

        assertEquals(LiveOperationResult.Status.UNKNOWN, sourceResult.status());
        assertEquals(
                "restoration_projection_source_identity_mismatch",
                sourceResult.code()
        );
        assertEquals(LiveOperationResult.Status.UNKNOWN, roleResult.status());
        assertEquals(
                "restoration_projection_state_incomplete",
                roleResult.code()
        );
        assertEquals(0, wrongSource.spawnCalls);
    }

    @Test
    void everyMarkerFieldAndBothUuidSurfacesMustMatch() {
        TameworkProjectionIdentityComponent expected =
                command().projectionMarker();
        TameworkProjectionIdentityComponent actual = expected.clone();
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(TARGET.value());

        assertTrue(HytaleCompanionProjectionSpawnExecutor.receiptMatches(
                TARGET.value(),
                TARGET.value(),
                npc,
                expected,
                actual
        ));
        assertFalse(HytaleCompanionProjectionSpawnExecutor.receiptMatches(
                TARGET.value(), uuid(80), npc, expected, actual
        ));
        npc.setLegacyUUID(uuid(81));
        assertFalse(HytaleCompanionProjectionSpawnExecutor.receiptMatches(
                TARGET.value(), TARGET.value(), npc, expected, actual
        ));
        npc.setLegacyUUID(TARGET.value());
        assertFalse(HytaleCompanionProjectionSpawnExecutor.receiptMatches(
                TARGET.value(), TARGET.value(), null, expected, actual
        ));

        List<Consumer<TameworkProjectionIdentityComponent>> mutations = List.of(
                marker -> marker.setProfileId("other-profile"),
                marker -> marker.setOperationId("other-operation"),
                marker -> marker.setProjectionKind("OTHER"),
                marker -> marker.setSlotKey("other-receipt"),
                marker -> marker.setSourceNpcUuid(uuid(82)),
                marker -> marker.setGeneration(8)
        );
        for (Consumer<TameworkProjectionIdentityComponent> mutation : mutations) {
            TameworkProjectionIdentityComponent changed = expected.clone();
            mutation.accept(changed);
            assertFalse(
                    HytaleCompanionProjectionSpawnExecutor.markersEqual(
                            expected, changed
                    )
            );
        }
    }

    @Test
    void productionAttemptPreservesSignedFrozenPlacement() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        Store<EntityStore> store = registry.addStore(null, null);
        CapturingSpawnGateway capture = new CapturingSpawnGateway();
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                (request, target) -> {
                    throw new AssertionError("Failed gateway must not install");
                },
                capture
        );
        HytaleCompanionProjectionAttemptGateway attempts =
                new HytaleCompanionProjectionAttemptGateway(
                        null,
                        store,
                        spawner,
                        new PlannedNpcProjectionPostAddService()
                );
        try {
            SpawnAttempt result = attempts.spawn(
                    command(),
                    snapshot(),
                    command().projectionMarker()
            );

            assertEquals(SpawnStatus.ROLE_NOT_FOUND, result.status());
            assertSame(store, capture.request.store());
            assertEquals(-11.25, capture.request.position().x());
            assertEquals(-63.5, capture.request.position().y());
            assertEquals(-4.75, capture.request.position().z());
            assertEquals(-0.25f, capture.request.rotation().pitch());
            assertEquals(-1.5f, capture.request.rotation().yaw());
            assertEquals(-0.5f, capture.request.rotation().roll());
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    @Test
    void productionAttemptAcceptsCaptureReleaseProjectionMarker() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        Store<EntityStore> store = registry.addStore(null, null);
        CapturingSpawnGateway capture = new CapturingSpawnGateway();
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                (request, target) -> {
                    throw new AssertionError("Failed gateway must not install");
                },
                capture
        );
        HytaleCompanionProjectionAttemptGateway attempts =
                new HytaleCompanionProjectionAttemptGateway(
                        null,
                        store,
                        spawner,
                        new PlannedNpcProjectionPostAddService()
                );
        ProjectionCommand release = command(
                "capture_release",
                TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE
        );
        try {
            SpawnAttempt result = attempts.spawn(
                    release,
                    snapshot(),
                    release.projectionMarker()
            );

            assertEquals(SpawnStatus.ROLE_NOT_FOUND, result.status());
            assertSame(store, capture.request.store());
            assertEquals(
                    TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE,
                    capture.request.projectionMarker().getProjectionKind()
            );
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    private RecordingAttempts attempts(ReceiptResult... receipts) {
        return new RecordingAttempts(List.of(receipts));
    }

    private ProjectionCommand command() {
        return command(
                "restoration",
                TameworkProjectionIdentityComponent.KIND_RECOVERY
        );
    }

    private ProjectionCommand command(String operationCode, String kind) {
        return new ProjectionCommand(
                operationCode,
                PROFILE,
                OPERATION,
                kind,
                TARGET,
                SOURCE.value(),
                "receipt-a",
                7,
                new CompanionSpawnPlacement(
                        "world",
                        -11.25,
                        -63.5,
                        -4.75,
                        -0.25f,
                        -1.5f,
                        -0.5f
                )
        );
    }

    private SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded(
            CoopResidentStateSnapshot snapshot
    ) {
        return new SnapshotDecodeResult.Decoded<>(snapshot);
    }

    private SnapshotDecodeResult.Failed<CoopResidentStateSnapshot> failed(
            String code
    ) {
        return new SnapshotDecodeResult.Failed<>(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                code,
                null
        );
    }

    private CoopResidentStateSnapshot snapshot() {
        return snapshot(SOURCE.value(), "tamed_test");
    }

    private CoopResidentStateSnapshot snapshot(UUID npcUuid, String roleId) {
        return new CoopResidentStateSnapshot(
                npcUuid,
                null,
                -1,
                roleId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                -500
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static final ProfileId PROFILE =
            new ProfileId(uuid(1));
    private static final OperationId OPERATION =
            new OperationId(uuid(2));
    private static final NpcAlias SOURCE =
            new NpcAlias(uuid(3));
    private static final NpcAlias TARGET =
            new NpcAlias(uuid(4));

    private static final class RecordingAttempts implements AttemptGateway {
        private final ArrayDeque<ReceiptResult> receipts;
        private int probeCalls;
        private int spawnCalls;
        private SpawnAttempt spawnResult = SpawnAttempt.spawned();
        private TameworkProjectionIdentityComponent spawnMarker;

        private RecordingAttempts(List<ReceiptResult> receipts) {
            this.receipts = new ArrayDeque<>(receipts);
        }

        @Override
        public ReceiptResult probe(
                ProjectionCommand command,
                TameworkProjectionIdentityComponent expectedMarker
        ) {
            probeCalls++;
            return receipts.removeFirst();
        }

        @Override
        public SpawnAttempt spawn(
                ProjectionCommand command,
                CoopResidentStateSnapshot snapshot,
                TameworkProjectionIdentityComponent marker
        ) {
            spawnCalls++;
            spawnMarker = marker.clone();
            return spawnResult;
        }
    }

    private static final class CapturingSpawnGateway
            implements PlannedNpcProjectionSpawner.SpawnGateway {
        private PlannedNpcProjectionSpawner.SpawnRequest request;

        @Override
        public PlannedNpcProjectionSpawner.GatewayResult spawn(
                PlannedNpcProjectionSpawner.SpawnRequest request,
                PlannedNpcProjectionSpawner.PreAddInstaller installer
        ) {
            this.request = request;
            return PlannedNpcProjectionSpawner.GatewayResult.failed(
                    PlannedNpcProjectionSpawner.Status.ROLE_NOT_FOUND
            );
        }

        @Override
        public void quarantine(
                PlannedNpcProjectionSpawner.SpawnedProjection spawned
        ) {
            throw new AssertionError("Failed spawn cannot be quarantined");
        }
    }
}
