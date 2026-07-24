package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReplacementAttempt;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for receipt ordering, ambiguity, and the two-save durability barrier. */
class CompanionCaptureReleaseWorldExecutorTest {
    private static final long INITIAL_TARGET_CHUNK = 41L;
    private static final long MOVED_TARGET_CHUNK = 42L;
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000001");
    private final CompanionCaptureReleaseWorldExecutor executor =
            new CompanionCaptureReleaseWorldExecutor();

    @Test
    void sourceIsReplacedBeforeProjectionAndBothSaves() throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                List.of(
                        "probe", "replace", "save-actor", "resume",
                        "probe", "project", "save-target", "resume",
                        "probe", "probe-projection"
                ),
                attempts.calls
        );
    }

    @Test
    void replayForceSavesBothCurrentReceiptsAgain() throws Exception {
        FakeAttempts attempts = new FakeAttempts();

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertTrue(attempts.calls.contains("save-actor"));
        assertTrue(attempts.calls.contains("save-target"));
        assertFalse(attempts.calls.contains("replace"));
    }

    @Test
    void confirmationWaitsForBothIndependentSaveFutures()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts(false);
        CompletableFuture<LiveOperationResult> result =
                executeAsync(attempts);

        assertFalse(result.isDone());
        assertEquals(1, attempts.actorSaveCalls);
        assertEquals(0, attempts.targetSaveCalls);
        assertFalse(attempts.calls.contains("project"));

        attempts.actorSave.complete(ReceiptPersistence.saved());
        assertFalse(result.isDone());
        assertEquals(1, attempts.targetSaveCalls);
        assertTrue(attempts.calls.contains("project"));
        attempts.targetSave.complete(
                ReceiptPersistence.savedTargetChunk(INITIAL_TARGET_CHUNK)
        );

        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.get(5, TimeUnit.SECONDS).status()
        );
    }

    @Test
    void actorSaveFailureCannotConfirm() throws Exception {
        FakeAttempts attempts = new FakeAttempts(false);
        CompletableFuture<LiveOperationResult> result =
                executeAsync(attempts);

        attempts.actorSave.completeExceptionally(
                new IllegalStateException("player save failed")
        );

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_release_actor_receipt_save_failed",
                result.get(5, TimeUnit.SECONDS)
        );
        assertEquals(0, attempts.targetSaveCalls);
    }

    @Test
    void targetChunkSaveFailureCannotConfirm() throws Exception {
        FakeAttempts attempts = new FakeAttempts(false);
        CompletableFuture<LiveOperationResult> result =
                executeAsync(attempts);

        attempts.actorSave.complete(ReceiptPersistence.saved());
        assertEquals(1, attempts.targetSaveCalls);
        attempts.targetSave.completeExceptionally(
                new IllegalStateException("chunk save failed")
        );

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_release_target_receipt_save_failed",
                result.get(5, TimeUnit.SECONDS)
        );
    }

    @Test
    void replacedWorldAfterSavesCannotConfirm() throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.replacedWorldOnResumeCall = 2;

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_release_world_instance_changed",
                result
        );
        assertEquals(1, attempts.actorSaveCalls);
        assertEquals(1, attempts.targetSaveCalls);
    }

    @Test
    void nonConfirmedProjectionCannotStartTargetSave() throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.projection = LiveOperationResult.retryable(
                "capture_release_spawn_failed", null
        );

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_release_spawn_failed",
                result
        );
        assertEquals(1, attempts.actorSaveCalls);
        assertEquals(0, attempts.targetSaveCalls);
        assertEquals(
                List.of(
                        "probe", "save-actor", "resume", "probe", "project"
                ),
                attempts.calls
        );
    }

    @Test
    void changedInventoryReceiptAfterSaveIsUnknown() throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventoryAfterSave = InventoryProbe.source();

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_release_inventory_receipt_readback_conflict",
                result
        );
    }

    @Test
    void changedProjectionReceiptAfterSaveIsUnknown() throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.projectionAfterSave = ProjectionProbe.conflict(null);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_release_projection_receipt_readback_conflict",
                result
        );
    }

    @Test
    void movedProjectionIsRetryableAndNeverQuarantinedAsAmbiguous()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.projectionAfterSave =
                ProjectionProbe.moved(MOVED_TARGET_CHUNK);

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.RETRYABLE,
                "capture_release_projection_chunk_changed",
                result
        );
    }

    @Test
    void ambiguousInventoryMutationIsUnknownWithoutProjectionOrSaves()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.source();
        attempts.replacement = ReplacementAttempt.ambiguous(
                new IllegalStateException("readback failed")
        );

        LiveOperationResult result = execute(attempts);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_release_inventory_mutation_ambiguous",
                result
        );
        assertEquals(List.of("probe", "replace"), attempts.calls);
    }

    @Test
    void disconnectedActorIsRetryableWithoutProjectionOrSaves()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        attempts.inventory = InventoryProbe.unavailable(null);

        LiveOperationResult result = execute(attempts);

        assertEquals(LiveOperationResult.Status.RETRYABLE, result.status());
        assertEquals(List.of("probe"), attempts.calls);
    }

    @Test
    void missingProfileParticipantIsRejectedBeforeWorldAccess()
            throws Exception {
        FakeAttempts attempts = new FakeAttempts();
        OperationEnvelope operation = operation(
                List.of(OperationScope.operation(OPERATION))
        );

        LiveOperationResult result = executor.execute(
                request(), operation, attempts
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_release_operation_invariant_mismatch",
                result
        );
        assertTrue(attempts.calls.isEmpty());
    }

    @Test
    void rejectedWorldThreadStopsBeforeAttemptGatewayCreation()
            throws Exception {
        AtomicBoolean attemptCreated = new AtomicBoolean();
        HytaleCompanionCaptureReleaseWorldGateway gateway =
                new HytaleCompanionCaptureReleaseWorldGateway(
                        new SnapshotCodecRegistry(List.of()),
                        new HytaleCompanionProjectionSpawnExecutor(),
                        new HytaleCapturedArtifactAdapter(),
                        executor,
                        store -> {
                            throw new IllegalStateException(
                                    "not on world thread"
                            );
                        },
                        (world, store, request, operation) -> {
                            attemptCreated.set(true);
                            return new FakeAttempts();
                        }
                );

        LiveOperationResult result = gateway.applyOrResolve(
                allocate(World.class),
                allocateStore(),
                request(),
                operation()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertResult(
                LiveOperationResult.Status.UNKNOWN,
                "capture_release_world_thread_unavailable",
                result
        );
        assertFalse(attemptCreated.get());
    }

    private LiveOperationResult execute(FakeAttempts attempts)
            throws Exception {
        return executeAsync(attempts).get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<LiveOperationResult> executeAsync(
            FakeAttempts attempts
    ) {
        return executor.execute(
                request(), operation(), attempts
        ).toCompletableFuture();
    }

    private CompanionCaptureReleaseRequest request() {
        String sourcePayload = "{\"capture\":\"envelope\"}";
        String projectionPayload = "{\"state\":\"frozen\"}";
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                new CompanionSnapshot(
                        SNAPSHOT,
                        PROFILE,
                        CompanionCaptureRequest.SNAPSHOT_KIND,
                        1,
                        sourcePayload,
                        Sha256Hash.ofUtf8(sourcePayload),
                        new LifecycleRevision(1),
                        true,
                        -900
                ),
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        projectionPayload,
                        Sha256Hash.ofUtf8(projectionPayload)
                ),
                new CaptureReleaseSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        artifact(
                                "capture-device-filled",
                                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                                        + "\":\"" + SNAPSHOT + "\","
                                        + "\"" + TameworkMetadataKeys
                                        .COMPANION_PROFILE_ID + "\":\""
                                        + PROFILE + "\","
                                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                                        + "\":\"" + SOURCE_ALIAS + "\""
                        ),
                        artifact(
                                "capture-device-empty",
                                "\"" + TameworkMetadataKeys
                                        .CAPTURE_RELEASE_RECEIPT
                                        + "\":\"inventory-receipt\""
                        )
                ),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world", 1, 2, 3, 0, 0, 0
                ),
                "inventory-receipt",
                "spawn-receipt",
                -800
        );
    }

    private CapturedArtifact artifact(String itemId, String metadata) {
        return CapturedArtifact.create(
                itemId, 1, 0.0D, 0.0D, "{" + metadata + "}"
        );
    }

    private OperationEnvelope operation() {
        return operation(List.of(
                OperationScope.operation(OPERATION),
                OperationScope.profile(PROFILE)
        ));
    }

    private OperationEnvelope operation(List<OperationScope> scopes) {
        return new OperationEnvelope(
                OPERATION,
                new IdempotencyKey("capture-release-live"),
                CompanionCaptureReleaseDefinition.KIND,
                1,
                "{}",
                OperationPhase.LIVE_APPLYING,
                "capture_release",
                new LifecycleRevision(2),
                null,
                0,
                0,
                null,
                null,
                -800,
                -700,
                null,
                null,
                null,
                scopes
        );
    }

    private void assertResult(
            LiveOperationResult.Status status,
            String code,
            LiveOperationResult result
    ) {
        assertEquals(status, result.status());
        assertEquals(code, result.code());
    }

    @SuppressWarnings("unchecked")
    private <T> T allocate(Class<T> type) throws Exception {
        return (T) unsafe().allocateInstance(type);
    }

    @SuppressWarnings("unchecked")
    private Store<EntityStore> allocateStore() throws Exception {
        return (Store<EntityStore>) unsafe().allocateInstance(Store.class);
    }

    private Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class FakeAttempts implements AttemptGateway {
        private InventoryProbe inventory = InventoryProbe.receipt();
        private InventoryProbe inventoryAfterSave = InventoryProbe.receipt();
        private ReplacementAttempt replacement =
                ReplacementAttempt.receipt();
        private LiveOperationResult projection =
                LiveOperationResult.confirmed(
                        "capture_release_spawn_receipt_confirmed"
                );
        private ProjectionProbe projectionAfterSave =
                ProjectionProbe.exact(INITIAL_TARGET_CHUNK);
        private CompletableFuture<ReceiptPersistence> actorSave;
        private CompletableFuture<ReceiptPersistence> targetSave;
        private final List<String> calls = new ArrayList<>();
        private int actorSaveCalls;
        private int targetSaveCalls;
        private int resumeCalls;
        private int replacedWorldOnResumeCall;
        private boolean afterSave;

        private FakeAttempts() {
            this(true);
        }

        private FakeAttempts(boolean completeSaves) {
            actorSave = new CompletableFuture<>();
            targetSave = new CompletableFuture<>();
            if (completeSaves) {
                actorSave.complete(ReceiptPersistence.saved());
                targetSave.complete(
                        ReceiptPersistence.savedTargetChunk(
                                INITIAL_TARGET_CHUNK
                        )
                );
            }
        }

        @Override
        public InventoryProbe probeInventory() {
            calls.add("probe");
            return afterSave ? inventoryAfterSave : inventory;
        }

        @Override
        public ReplacementAttempt replaceSourceWithReceipt() {
            calls.add("replace");
            return replacement;
        }

        @Override
        public LiveOperationResult applyOrResolveProjection() {
            calls.add("project");
            return projection;
        }

        @Override
        public CompletionStage<ReceiptPersistence> persistActorReceipt() {
            calls.add("save-actor");
            actorSaveCalls++;
            return actorSave;
        }

        @Override
        public CompletionStage<ReceiptPersistence>
        persistTargetChunkReceipt() {
            calls.add("save-target");
            targetSaveCalls++;
            return targetSave;
        }

        @Override
        public CompletionStage<LiveOperationResult> resumeOnWorldThread(
                Supplier<CompletionStage<LiveOperationResult>> continuation
        ) {
            calls.add("resume");
            resumeCalls++;
            afterSave = true;
            if (resumeCalls == replacedWorldOnResumeCall) {
                return CompletableFuture.completedFuture(
                        LiveOperationResult.retryable(
                                "capture_release_world_instance_changed",
                                null
                        )
                );
            }
            return continuation.get();
        }

        @Override
        public ProjectionProbe probeProjectionReceipt() {
            calls.add("probe-projection");
            return projectionAfterSave;
        }

        @Override
        public ProjectionProbe probeProjectionReceiptInChunk(
                long expectedChunkIndex
        ) {
            calls.add("probe-projection");
            if (projectionAfterSave.status()
                    == ProjectionStatus.EXACT
                    && projectionAfterSave.chunkIndex() != null
                    && projectionAfterSave.chunkIndex()
                    != expectedChunkIndex) {
                return ProjectionProbe.moved(
                        projectionAfterSave.chunkIndex()
                );
            }
            return projectionAfterSave;
        }
    }
}
