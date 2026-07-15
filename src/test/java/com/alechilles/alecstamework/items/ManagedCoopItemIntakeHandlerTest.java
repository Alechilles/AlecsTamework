package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureAttempt;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureFinalizer.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeOutcome;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeRequest;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeStart;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementReceipt;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.StartStatus;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacement;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacementStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admission ordering, fail-closed decoding, and interaction-fingerprint deduplication tests. */
class ManagedCoopItemIntakeHandlerTest {
    private static final UUID SOURCE = new UUID(0L, 301L);
    private static final UUID PLAYER = new UUID(0L, 302L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void slotCommitPrecedesItemRetirementAndFinalization() throws Exception {
        List<String> order = new ArrayList<>();
        CompletableFuture<CaptureOutcome> durableClaim = new CompletableFuture<>();
        AtomicReference<CaptureAttempt> submitted = new AtomicReference<>();
        ManagedCoopItemIntakeHandler handler = handler(
                attempt -> {
                    order.add("capture");
                    submitted.set(attempt);
                    return durableClaim;
                },
                ready -> {
                    order.add("finalize");
                    return CompletableFuture.completedFuture(new Outcome(true, null));
                }
        );
        IntakeRequest request = request(order);

        IntakeStart start = handler.handle(request);
        assertEquals(StartStatus.ACCEPTED, start.status());
        assertEquals(List.of("capture"), order);
        assertFalse(start.completion().toCompletableFuture().isDone());

        CaptureAttempt attempt = submitted.get();
        assertNotNull(attempt);
        assertEquals(ManagedCoopCaptureSourceEvidence.Status.CAPTURED_ITEM,
                ManagedCoopCaptureSourceEvidence.read(attempt.snapshotJson()).status());
        durableClaim.complete(new CaptureOutcome(
                ManagedCoopCaptureCoordinator.OutcomeStatus.RETIREMENT_READY,
                ready(attempt),
                null));

        IntakeOutcome outcome = start.completion().toCompletableFuture().join();
        assertTrue(outcome.completed());
        assertEquals(List.of("capture", "retire", "finalize", "cleanup"), order);
    }

    @Test
    void incompleteEnvelopeBlocksVanillaPathWithoutPersistenceOrConsumption() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger retirements = new AtomicInteger();
        List<String> feedback = new ArrayList<>();
        ManagedCoopItemIntakeHandler handler = handler(
                attempt -> {
                    captures.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError("must not capture"));
                },
                ready -> CompletableFuture.completedFuture(new Outcome(true, null))
        );
        IntakeRequest request = new IntakeRequest(
                context(), PLAYER, (short) 1, "Tool_Capture_Crate", null,
                (ready, envelope) -> {
                    retirements.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError("must not retire"));
                },
                feedback::add
        );

        IntakeStart result = handler.handle(request);

        assertEquals(StartStatus.REJECTED, result.status());
        assertEquals(0, captures.get());
        assertEquals(0, retirements.get());
        assertTrue(feedback.getFirst().contains("blocked"));
    }

    @Test
    void repeatedFingerprintSharesOneInFlightCapture() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        CompletableFuture<CaptureOutcome> pending = new CompletableFuture<>();
        ManagedCoopItemIntakeHandler handler = handler(
                attempt -> {
                    captures.incrementAndGet();
                    return pending;
                },
                ready -> CompletableFuture.completedFuture(new Outcome(true, null))
        );
        IntakeRequest request = request(new ArrayList<>());

        IntakeStart first = handler.handle(request);
        IntakeStart second = handler.handle(request);

        assertEquals(StartStatus.ACCEPTED, first.status());
        assertEquals(StartStatus.DEDUPLICATED, second.status());
        assertEquals(1, captures.get());
        assertEquals(first.completion(), second.completion());
    }

    @Test
    void profileMismatchFailsBeforeCapacityOrCapture() throws Exception {
        AtomicInteger placements = new AtomicInteger();
        ManagedCoopCapturedItemEnvelopeCodec codec = new ManagedCoopCapturedItemEnvelopeCodec();
        ManagedCoopItemIntakeHandler handler = new ManagedCoopItemIntakeHandler(
                codec,
                (context, uuid, profile) -> {
                    placements.incrementAndGet();
                    return placement();
                },
                uuid -> "different-profile",
                attempt -> CompletableFuture.failedFuture(new AssertionError("must not capture")),
                ready -> CompletableFuture.completedFuture(new Outcome(true, null))
        );

        IntakeStart result = handler.handle(request(new ArrayList<>()));

        assertEquals(StartStatus.REJECTED, result.status());
        assertEquals(0, placements.get());
        assertEquals("managed_coop_item_profile_identity_mismatch", result.detail());
    }

    private ManagedCoopItemIntakeHandler handler(
            ManagedCoopItemIntakeHandler.CaptureGateway capture,
            ManagedCoopItemIntakeHandler.FinalizationGateway finalization) {
        return new ManagedCoopItemIntakeHandler(
                new ManagedCoopCapturedItemEnvelopeCodec(),
                (context, uuid, profile) -> placement(),
                uuid -> "profile-a",
                capture,
                finalization
        );
    }

    private IntakeRequest request(List<String> order) throws Exception {
        ManagedCoopCapturedItemEnvelopeCodec codec = new ManagedCoopCapturedItemEnvelopeCodec();
        String raw = codec.encode("profile-a", portableSnapshot());
        return new IntakeRequest(
                context(),
                PLAYER,
                (short) 1,
                "Tool_Capture_Crate",
                raw,
                (ready, envelope) -> {
                    order.add("retire");
                    return CompletableFuture.completedFuture(new ItemRetirementReceipt(
                            envelope.fingerprint(),
                            ready.operationId(),
                            () -> {
                                order.add("cleanup");
                                return CompletableFuture.completedFuture(true);
                            }
                    ));
                },
                ignored -> {
                }
        );
    }

    private RetirementReady ready(CaptureAttempt attempt) {
        return new RetirementReady(
                SOURCE,
                "profile-a",
                "resident-a",
                "operation-a",
                AUTHORITY,
                "coop_chicken",
                0,
                attempt.snapshotHash(),
                2L,
                OperationState.SOURCE_RETIRE_REQUESTED,
                1L
        );
    }

    private CapturePlacement placement() {
        return new CapturePlacement(CapturePlacementStatus.NEW_SLOT, 0, 0L, null, null);
    }

    private CoopResidentStateSnapshot portableSnapshot() {
        return new CoopResidentStateSnapshot(
                SOURCE, null, -1, "mob_chicken",
                null, null, null, null, null, null, null, null, null, null, null,
                null, 1.0, -100L
        );
    }

    private ManagedCoopContext context() throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "Coop_Config");
        set(config, "enabled", true);
        set(config, "coopId", "coop_chicken");
        return new ManagedCoopContext(AUTHORITY, "coop_chicken", 0, config, null);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
