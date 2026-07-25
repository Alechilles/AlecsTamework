package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.CompositeProbe;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.SaveResult;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash-seam, replay, conflict, and cleanup coverage for captured-item coop intake. */
class CompanionCoopCapturedItemWorldExecutorTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final LifecycleRevision EXPECTED =
            new LifecycleRevision(7);
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop-chicken", 10, 64, 20, 2);

    @Test
    void sourceIsMarkedOnlyAfterGenericReceiptSaveAndWorldResume()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.ABSENT, ArtifactState.SOURCE
        );
        CompletableFuture<SaveResult> receiptSave = new CompletableFuture<>();
        attempt.saves.add(receiptSave);

        CompletableFuture<LiveOperationResult> result = executor().execute(
                request(), operation(OperationPhase.LIVE_APPLYING), attempt
        ).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(
                List.of("probe", "install-receipt", "save"),
                attempt.events
        );

        receiptSave.complete(SaveResult.saved());
        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                result.get(5, TimeUnit.SECONDS).status()
        );
        assertTrue(
                attempt.events.indexOf("resume")
                        < attempt.events.indexOf("mark-source")
        );
        assertEquals(
                List.of(
                        "probe", "install-receipt", "save", "resume",
                        "probe", "mark-source", "save", "resume", "probe"
                ),
                attempt.events
        );
    }

    @Test
    void exactReceiptAndUnchangedSourceResumeIdempotently()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.SOURCE
        );

        LiveOperationResult result = executor().execute(
                request(), operation(OperationPhase.RETRYABLE), attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(1, attempt.markCalls);
        assertEquals(2, attempt.saveCalls);
    }

    @Test
    void exactReceiptAndMarkedArtifactAreForceSavedBeforeConfirmation()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.MARKED
        );

        LiveOperationResult result = executor().execute(
                request(), operation(OperationPhase.LIVE_APPLYING), attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(0, attempt.markCalls);
        assertEquals(2, attempt.saveCalls);
        assertEquals(3, attempt.probeCalls);
    }

    @Test
    void absenceOrMarkedArtifactWithoutReceiptIsUnknown()
            throws Exception {
        for (ArtifactState state : List.of(
                ArtifactState.ABSENT, ArtifactState.MARKED
        )) {
            FakeAttempt attempt = new FakeAttempt(
                    ReceiptState.ABSENT, state
            );

            LiveOperationResult result = executor().execute(
                    request(),
                    operation(OperationPhase.LIVE_APPLYING),
                    attempt
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
            assertEquals(0, attempt.saveCalls);
            assertEquals(0, attempt.markCalls);
        }
    }

    @Test
    void exactReceiptWithUnexpectedAbsenceRemainsUnknown()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.ABSENT
        );

        LiveOperationResult result = executor().execute(
                request(), operation(OperationPhase.LIVE_APPLYING), attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, result.status());
        assertEquals(0, attempt.saveCalls);
    }

    @Test
    void actorSaveAndWorldResumeFailuresAreRetryable()
            throws Exception {
        FakeAttempt saveFailure = new FakeAttempt(
                ReceiptState.ABSENT, ArtifactState.SOURCE
        );
        saveFailure.saves.add(CompletableFuture.completedFuture(
                SaveResult.retryable(new IllegalStateException("disk"))
        ));
        LiveOperationResult failedSave = executor().execute(
                request(),
                operation(OperationPhase.LIVE_APPLYING),
                saveFailure
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FakeAttempt resumeFailure = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.SOURCE
        );
        resumeFailure.failResume = true;
        LiveOperationResult failedResume = executor().execute(
                request(),
                operation(OperationPhase.LIVE_APPLYING),
                resumeFailure
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                failedSave.status()
        );
        assertEquals(0, saveFailure.markCalls);
        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                failedResume.status()
        );
        assertEquals(0, resumeFailure.markCalls);
    }

    @Test
    void nullAndConflictBoundaryValuesFailClosed() throws Exception {
        FakeAttempt nullProbe = new FakeAttempt(
                ReceiptState.ABSENT, ArtifactState.SOURCE
        );
        nullProbe.returnNullProbe = true;
        LiveOperationResult nullResult = executor().execute(
                request(),
                operation(OperationPhase.LIVE_APPLYING),
                nullProbe
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FakeAttempt conflict = new FakeAttempt(
                ReceiptState.CONFLICT, ArtifactState.SOURCE
        );
        LiveOperationResult conflictResult = executor().execute(
                request(),
                operation(OperationPhase.LIVE_APPLYING),
                conflict
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.UNKNOWN, nullResult.status());
        assertEquals(
                LiveOperationResult.Status.UNKNOWN, conflictResult.status()
        );
        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                executor().execute(null, null, null)
                        .toCompletableFuture().get().status()
        );
    }

    @Test
    void durableCleanupRetiresOnlyMarkedArtifactThenGenericReceipt()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.MARKED
        );

        LiveOperationResult result = executor().cleanupAfterDurableCommit(
                request(), operation(OperationPhase.DURABLE), attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(
                List.of(
                        "probe", "retire-marked", "save", "resume",
                        "probe", "remove-receipt", "save", "resume", "probe"
                ),
                attempt.events
        );
        assertEquals(ArtifactState.ABSENT, attempt.artifactState);
        assertEquals(ReceiptState.ABSENT, attempt.receiptState);
    }

    @Test
    void durableCleanupReplaysExactAbsenceThroughOneLastSave()
            throws Exception {
        FakeAttempt attempt = new FakeAttempt(
                ReceiptState.ABSENT, ArtifactState.ABSENT
        );

        LiveOperationResult result = executor().cleanupAfterDurableCommit(
                request(), operation(OperationPhase.DURABLE), attempt
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(LiveOperationResult.Status.CONFIRMED, result.status());
        assertEquals(2, attempt.saveCalls);
        assertEquals(0, attempt.retireCalls);
        assertEquals(0, attempt.removeReceiptCalls);
    }

    @Test
    void cleanupBeforeDurableCommitIsRejected() throws Exception {
        for (OperationPhase phase : List.of(
                OperationPhase.LIVE_APPLYING,
                OperationPhase.PUBLISHED
        )) {
            FakeAttempt attempt = new FakeAttempt(
                    ReceiptState.EXACT, ArtifactState.MARKED
            );

            LiveOperationResult result =
                    executor().cleanupAfterDurableCommit(
                            request(), operation(phase), attempt
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(
                    LiveOperationResult.Status.UNKNOWN, result.status()
            );
            assertTrue(attempt.events.isEmpty());
        }
    }

    @Test
    void sourceBoundaryPreservesLiveEntityDelegateAndRoutesItems()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        AtomicInteger itemCalls = new AtomicInteger();
        FakeAttempt itemAttempt = new FakeAttempt(
                ReceiptState.EXACT, ArtifactState.MARKED
        );
        CompanionCoopCaptureSourceBoundary boundary =
                new CompanionCoopCaptureSourceBoundary(
                        (request, operation) -> {
                            liveCalls.incrementAndGet();
                            return LiveOperationResult.confirmed(
                                    "live-delegate"
                            ).completed();
                        },
                        (request, operation) -> {
                            itemCalls.incrementAndGet();
                            return itemAttempt;
                        }
                );
        CompanionCoopCaptureRequest live = liveRequest();

        LiveOperationResult liveResult = boundary.applyOrResolve(
                live, operation(live, OperationPhase.LIVE_APPLYING)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        LiveOperationResult itemResult = boundary.applyOrResolve(
                request(), operation(OperationPhase.LIVE_APPLYING)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("live-delegate", liveResult.code());
        assertEquals(LiveOperationResult.Status.CONFIRMED, itemResult.status());
        assertEquals(1, liveCalls.get());
        assertEquals(1, itemCalls.get());
    }

    private CompanionCoopCapturedItemWorldExecutor executor() {
        return new CompanionCoopCapturedItemWorldExecutor();
    }

    private CompanionCoopCaptureRequest request() {
        String portable = "{\"version\":\"1\",\"npcUuid\":\"" + SOURCE
                + "\",\"coopId\":null,\"residentSlot\":-1,"
                + "\"roleId\":\"tamed_chicken\",\"capturedAtMs\":-200}";
        String housed = portable
                .replace(
                        "\"coopId\":null",
                        "\"coopId\":\"coop-chicken\""
                )
                .replace("\"residentSlot\":-1", "\"residentSlot\":2");
        CompanionSnapshot capture = snapshot(
                "40000000-0000-0000-0000-000000000001",
                CompanionCaptureRequest.SNAPSHOT_KIND,
                new LifecycleRevision(6),
                portable,
                -200
        );
        JsonObject metadata = new JsonObject();
        metadata.addProperty(
                TameworkMetadataKeys.TARGET_UUID, SOURCE.toString()
        );
        metadata.addProperty(
                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                PROFILE.toString()
        );
        metadata.addProperty(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                capture.snapshotId().toString()
        );
        CapturedArtifact source = CapturedArtifact.create(
                "captured-chicken", 1, 0.0D, 0.0D, metadata.toString()
        );
        BsonDocument markedMetadata = BsonDocument.parse(
                source.metadataExtendedJson()
        );
        markedMetadata.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString("coop-item-receipt")
        );
        CapturedArtifact marked = CapturedArtifact.create(
                source.itemId(),
                source.quantity(),
                source.durability(),
                source.maxDurability(),
                markedMetadata.toJson()
        );
        return new CompanionCoopCaptureRequest(
                PROFILE,
                EXPECTED,
                SLOT,
                snapshot(
                        "40000000-0000-0000-0000-000000000002",
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        EXPECTED.next(),
                        housed,
                        -100
                ),
                new CoopCapturedItemSourceEvidence(
                        SOURCE,
                        PROFILE,
                        capture,
                        UUID.fromString(
                                "30000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        new CoopCapturedItemInventoryPosition(
                                CoopCapturedItemInventoryPosition.Section.STORAGE,
                                4
                        ),
                        source,
                        marked,
                        "coop-item-receipt"
                ),
                -100
        );
    }

    private CompanionCoopCaptureRequest liveRequest() {
        CompanionCoopCaptureRequest captured = request();
        return new CompanionCoopCaptureRequest(
                captured.profileId(),
                captured.expectedLifecycleRevision(),
                captured.targetSlot(),
                captured.snapshot(),
                new CoopCaptureSourceEvidence(
                        SOURCE, "world", "live-receipt"
                ),
                captured.requestedAtMs()
        );
    }

    private CompanionSnapshot snapshot(
            String id,
            com.alechilles.alecstamework.companion.snapshot.SnapshotKind kind,
            LifecycleRevision revision,
            String payload,
            long createdAtMs
    ) {
        return new CompanionSnapshot(
                SnapshotId.parse(id),
                PROFILE,
                kind,
                kind.equals(CompanionCaptureRequest.SNAPSHOT_KIND)
                        ? CompanionCaptureRequest.SNAPSHOT_VERSION
                        : CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                revision,
                true,
                createdAtMs
        );
    }

    private OperationEnvelope operation(OperationPhase phase) {
        return operation(request(), phase);
    }

    private OperationEnvelope operation(
            CompanionCoopCaptureRequest request,
            OperationPhase phase
    ) {
        OperationId id = OperationId.parse(
                "60000000-0000-0000-0000-000000000001"
        );
        return new OperationEnvelope(
                id,
                new IdempotencyKey("coop-captured-item"),
                CompanionCoopCaptureDefinition.KIND,
                1,
                CompanionCoopCaptureDefinition.INSTANCE.encode(request),
                phase,
                "companion_coop_capture",
                EXPECTED,
                null,
                0,
                0,
                phase == OperationPhase.RETRYABLE ? "TRANSIENT" : null,
                phase == OperationPhase.RETRYABLE ? "retry" : null,
                -100,
                -90,
                phase == OperationPhase.DURABLE
                        || phase == OperationPhase.PUBLISHED ? -80L : null,
                phase == OperationPhase.PUBLISHED ? -70L : null,
                phase == OperationPhase.PUBLISHED ? -70L : null,
                List.of(
                        OperationScope.operation(id),
                        OperationScope.profile(PROFILE),
                        OperationScope.coop(SLOT.toString())
                )
        );
    }

    private static final class FakeAttempt
            implements CompanionCoopCapturedItemAttempt {
        private ReceiptState receiptState;
        private ArtifactState artifactState;
        private final ArrayDeque<CompletableFuture<SaveResult>> saves =
                new ArrayDeque<>();
        private final List<String> events = new ArrayList<>();
        private int probeCalls;
        private int saveCalls;
        private int markCalls;
        private int retireCalls;
        private int removeReceiptCalls;
        private boolean failResume;
        private boolean returnNullProbe;

        private FakeAttempt(
                ReceiptState receiptState,
                ArtifactState artifactState
        ) {
            this.receiptState = receiptState;
            this.artifactState = artifactState;
        }

        @Override
        public CompositeProbe probe(
                InventoryOperationReceipt receipt,
                CoopCapturedItemSourceEvidence source
        ) {
            events.add("probe");
            probeCalls++;
            return returnNullProbe
                    ? null
                    : CompositeProbe.of(receiptState, artifactState);
        }

        @Override
        public ReceiptMutation installReceipt(
                InventoryOperationReceipt receipt
        ) {
            events.add("install-receipt");
            receiptState = ReceiptState.EXACT;
            return ReceiptMutation.exact();
        }

        @Override
        public ArtifactMutation markSource(
                CoopCapturedItemSourceEvidence source
        ) {
            events.add("mark-source");
            markCalls++;
            artifactState = ArtifactState.MARKED;
            return ArtifactMutation.marked();
        }

        @Override
        public ArtifactMutation retireMarkedArtifact(
                CoopCapturedItemSourceEvidence source
        ) {
            events.add("retire-marked");
            retireCalls++;
            artifactState = ArtifactState.ABSENT;
            return ArtifactMutation.absent();
        }

        @Override
        public ReceiptMutation removeReceipt(
                InventoryOperationReceipt receipt
        ) {
            events.add("remove-receipt");
            removeReceiptCalls++;
            receiptState = ReceiptState.ABSENT;
            return ReceiptMutation.exact();
        }

        @Override
        public CompletionStage<SaveResult> persistActor() {
            events.add("save");
            saveCalls++;
            return saves.isEmpty()
                    ? CompletableFuture.completedFuture(SaveResult.saved())
                    : saves.remove();
        }

        @Override
        public CompletionStage<LiveOperationResult> resumeOnActorWorldThread(
                Supplier<CompletionStage<LiveOperationResult>> continuation
        ) {
            events.add("resume");
            return failResume ? null : continuation.get();
        }
    }
}
