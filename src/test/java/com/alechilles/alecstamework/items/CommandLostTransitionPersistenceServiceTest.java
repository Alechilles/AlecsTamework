package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.persistence.sqlite.LostRecoveryWriteResult;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.RecoveredProjectionSnapshotLoadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.ACCEPTED_PENDING;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.ALREADY_PENDING;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.MISSING_FULL_SNAPSHOT;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.DURABLE_SNAPSHOT_CONFLICT;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.DURABLE_SNAPSHOT_READ_FAILED;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.PersistStatus.REJECTED;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.CancelStatus.COMPENSATION_PENDING;
import static com.alechilles.alecstamework.items.CommandLostTransitionPersistenceService.CancelStatus.COMPENSATION_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLostTransitionPersistenceServiceTest {

    @Test
    void preparePreservesSignedQueuedAndDroppedTimestamps() {
        UUID sourceUuid = uuid(1);
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot current =
                new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                        sourceUuid, null, null, -300L, -250L, 1, null, 0L);

        CommandLinkedNpcLostService.LostLinkedNpcSnapshot prepared =
                CommandLostTransitionPersistenceService.prepare(
                        current,
                        sourceUuid,
                        null,
                        null,
                        null,
                        -200L,
                        -100L,
                        2,
                        999L
                );
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot zeroSentinels =
                CommandLostTransitionPersistenceService.prepare(
                        null, sourceUuid, null, null, null, 0L, 0L, 0, 999L);

        assertEquals(-300L, prepared.lastRelocationQueuedAtMs());
        assertEquals(-100L, prepared.lostAtMs());
        assertEquals(999L, zeroSentinels.lastRelocationQueuedAtMs());
        assertEquals(999L, zeroSentinels.lostAtMs());
    }

    @Test
    void handsCompleteSignedSnapshotToTrackedWriteAndPublishesOnlyAfterCommit() {
        UUID sourceUuid = uuid(1);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot full = fullSnapshot(sourceUuid);
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<LostRecoveryWriteResult>> completion =
                new CompletableFuture<>();
        AtomicReference<CoopResidentStateSnapshotService.CoopResidentStateSnapshot> handedOff =
                new AtomicReference<>();
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> published = new ArrayList<>();
        CommandLostTransitionPersistenceService service = new CommandLostTransitionPersistenceService(
                ignored -> full,
                (lost, snapshot) -> {
                    handedOff.set(snapshot);
                    return new PersistenceWriteQueue.WriteSubmission<>(true, completion);
                },
                ignored -> committedVoidSubmission()
        );
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost = lost(sourceUuid);

        CommandLostTransitionPersistenceService.PersistStatus status = service.persist(lost, published::add);

        assertEquals(ACCEPTED_PENDING, status);
        assertTrue(service.isPending(sourceUuid));
        assertTrue(published.isEmpty(), "lost state must remain invisible before commit");
        assertSame(full, handedOff.get());
        assertNotNull(handedOff.get().commandLinks());
        assertNotNull(handedOff.get().owner());
        assertNotNull(handedOff.get().tamed());
        assertNotNull(handedOff.get().npcName());
        assertNotNull(handedOff.get().happiness());
        assertNotNull(handedOff.get().needs());
        assertNotNull(handedOff.get().breeding());
        assertNotNull(handedOff.get().leveling());
        assertNotNull(handedOff.get().traits());
        assertNotNull(handedOff.get().talents());
        assertNotNull(handedOff.get().lifeStage());
        assertNotNull(handedOff.get().attachments());
        assertEquals(-101L, handedOff.get().happiness().getLastUpdateMs());
        assertEquals(-102L, handedOff.get().needs().getLastUpdateMs());
        assertEquals(-104L, handedOff.get().breeding().getCooldownUntilMs());
        assertEquals(-106L, handedOff.get().lifeStage().getBornAtMs());

        completion.complete(new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.COMMITTED,
                new LostRecoveryWriteResult("profile-a", sourceUuid, 2, true, "sha"),
                null,
                null
        ));

        assertEquals(List.of(lost), published);
        assertFalse(service.isPending(sourceUuid));
    }

    @Test
    void failedAndRejectedWritesNeverPublishRuntimeLostState() {
        UUID failedUuid = uuid(1);
        UUID rejectedUuid = uuid(2);
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<LostRecoveryWriteResult>> failedCompletion =
                new CompletableFuture<>();
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> published = new ArrayList<>();
        AtomicInteger writes = new AtomicInteger();
        CommandLostTransitionPersistenceService failed = new CommandLostTransitionPersistenceService(
                ignored -> fullSnapshot(failedUuid),
                (lost, full) -> {
                    writes.incrementAndGet();
                    return new PersistenceWriteQueue.WriteSubmission<>(true, failedCompletion);
                },
                ignored -> committedVoidSubmission()
        );

        assertEquals(ACCEPTED_PENDING, failed.persist(lost(failedUuid), published::add));
        failedCompletion.complete(new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.FAILED,
                null,
                "forced_failure",
                new IllegalStateException("forced")
        ));
        assertTrue(published.isEmpty());

        CommandLostTransitionPersistenceService rejected = new CommandLostTransitionPersistenceService(
                ignored -> fullSnapshot(rejectedUuid),
                (lost, full) -> {
                    writes.incrementAndGet();
                    return rejectedSubmission();
                },
                ignored -> committedVoidSubmission()
        );
        assertEquals(REJECTED, rejected.persist(lost(rejectedUuid), published::add));
        assertTrue(published.isEmpty());
        assertEquals(2, writes.get());
    }

    @Test
    void missingOrCancelledFullStateFailsClosed() {
        UUID sourceUuid = uuid(1);
        AtomicInteger writes = new AtomicInteger();
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> published = new ArrayList<>();
        CommandLostTransitionPersistenceService missing = new CommandLostTransitionPersistenceService(
                ignored -> null,
                (lost, full) -> {
                    writes.incrementAndGet();
                    return rejectedSubmission();
                },
                ignored -> committedVoidSubmission()
        );
        assertEquals(MISSING_FULL_SNAPSHOT, missing.persist(lost(sourceUuid), published::add));
        assertEquals(0, writes.get());

        CompletableFuture<PersistenceWriteQueue.WriteOutcome<LostRecoveryWriteResult>> completion =
                new CompletableFuture<>();
        CommandLostTransitionPersistenceService cancelled = new CommandLostTransitionPersistenceService(
                ignored -> fullSnapshot(sourceUuid),
                (lost, full) -> new PersistenceWriteQueue.WriteSubmission<>(true, completion),
                ignored -> committedVoidSubmission()
        );
        assertEquals(ACCEPTED_PENDING, cancelled.persist(lost(sourceUuid), published::add));
        assertEquals(COMPENSATION_PENDING, cancelled.cancel(sourceUuid));
        completion.complete(new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.COMMITTED,
                new LostRecoveryWriteResult("profile-a", sourceUuid, 2, true, "sha"),
                null,
                null
        ));
        assertTrue(published.isEmpty());
    }

    /** Regression for the restart path that stranded recovered companions as UNLOADED. */
    @Test
    void verifiedRecoveredProjectionBridgesMissingLiveSnapshotAfterRestart() {
        UUID historicalSourceUuid = uuid(1);
        UUID currentNpcUuid = uuid(2);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot durable =
                fullSnapshot(historicalSourceUuid);
        AtomicReference<CoopResidentStateSnapshotService.CoopResidentStateSnapshot> handedOff =
                new AtomicReference<>();
        CommandLostTransitionPersistenceService service = new CommandLostTransitionPersistenceService(
                ignored -> null,
                ignored -> RecoveredProjectionSnapshotLoadResult.found(
                        "profile-a", historicalSourceUuid, durable),
                (lost, full) -> {
                    handedOff.set(full);
                    return committedSubmission(currentNpcUuid);
                },
                ignored -> committedVoidSubmission()
        );

        assertEquals(ACCEPTED_PENDING, service.persist(lost(currentNpcUuid), ignored -> { }));
        assertNotNull(handedOff.get());
        assertEquals(currentNpcUuid, handedOff.get().npcUuid());
        assertEquals(durable.roleId(), handedOff.get().roleId());
        assertEquals(durable.npcName().getName(), handedOff.get().npcName().getName());
        assertEquals(durable.capturedAtMs(), handedOff.get().capturedAtMs());
    }

    @Test
    void conflictedOrUnreadableDurableProjectionNeverWrites() {
        UUID currentNpcUuid = uuid(2);
        AtomicInteger writes = new AtomicInteger();
        CommandLostTransitionPersistenceService conflicted = new CommandLostTransitionPersistenceService(
                ignored -> null,
                ignored -> RecoveredProjectionSnapshotLoadResult.conflict(
                        "profile-a", uuid(1), "active_recovery_conflict"),
                (lost, full) -> {
                    writes.incrementAndGet();
                    return rejectedSubmission();
                },
                ignored -> committedVoidSubmission()
        );
        CommandLostTransitionPersistenceService failed = new CommandLostTransitionPersistenceService(
                ignored -> null,
                ignored -> RecoveredProjectionSnapshotLoadResult.failed(
                        "profile-a", uuid(1), "lost_envelope_snapshot_hash_invalid", null),
                (lost, full) -> {
                    writes.incrementAndGet();
                    return rejectedSubmission();
                },
                ignored -> committedVoidSubmission()
        );

        assertEquals(DURABLE_SNAPSHOT_CONFLICT,
                conflicted.persist(lost(currentNpcUuid), ignored -> { }));
        assertEquals(DURABLE_SNAPSHOT_READ_FAILED,
                failed.persist(lost(currentNpcUuid), ignored -> { }));
        assertEquals(0, writes.get());
    }

    @Test
    void rejectedCancellationStillSuppressesPublicationAfterAcceptedWriteCommits() {
        UUID sourceUuid = uuid(1);
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<LostRecoveryWriteResult>> completion =
                new CompletableFuture<>();
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> published = new ArrayList<>();
        CommandLostTransitionPersistenceService service = new CommandLostTransitionPersistenceService(
                ignored -> fullSnapshot(sourceUuid),
                (lost, full) -> new PersistenceWriteQueue.WriteSubmission<>(true, completion),
                ignored -> rejectedVoidSubmission()
        );

        assertEquals(ACCEPTED_PENDING, service.persist(lost(sourceUuid), published::add));
        assertEquals(COMPENSATION_REJECTED, service.cancel(sourceUuid));
        completion.complete(new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.COMMITTED,
                new LostRecoveryWriteResult("profile-a", sourceUuid, 2, true, "sha"),
                null,
                null
        ));

        assertTrue(published.isEmpty(), "cancelled state must stay invisible even if compensation is rejected");
    }

    @Test
    void sameNpcSecondSubmissionIsIdempotentAndCannotSuppressFirstCommit() {
        UUID sourceUuid = uuid(1);
        CompletableFuture<PersistenceWriteQueue.WriteOutcome<LostRecoveryWriteResult>> completion =
                new CompletableFuture<>();
        AtomicInteger writes = new AtomicInteger();
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> published = new ArrayList<>();
        CommandLostTransitionPersistenceService service = new CommandLostTransitionPersistenceService(
                ignored -> fullSnapshot(sourceUuid),
                (lost, full) -> {
                    writes.incrementAndGet();
                    return new PersistenceWriteQueue.WriteSubmission<>(true, completion);
                },
                ignored -> committedVoidSubmission()
        );
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot first = lost(sourceUuid);

        assertEquals(ACCEPTED_PENDING, service.persist(first, published::add));
        assertEquals(ALREADY_PENDING, service.persist(lost(sourceUuid), published::add));
        assertEquals(1, writes.get());

        completion.complete(new PersistenceWriteQueue.WriteOutcome<>(
                PersistenceWriteQueue.WriteStatus.COMMITTED,
                new LostRecoveryWriteResult("profile-a", sourceUuid, 2, true, "sha"),
                null,
                null
        ));
        assertEquals(List.of(first), published);
    }

    private PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> rejectedSubmission() {
        return new PersistenceWriteQueue.WriteSubmission<>(
                false,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.REJECTED,
                        null,
                        "forced_rejection",
                        null
                ))
        );
    }

    private PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> committedSubmission(
            UUID npcUuid) {
        return new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.COMMITTED,
                        new LostRecoveryWriteResult("profile-a", npcUuid, 1, true, "sha"),
                        null,
                        null
                ))
        );
    }

    private PersistenceWriteQueue.WriteSubmission<Void> committedVoidSubmission() {
        return new PersistenceWriteQueue.WriteSubmission<>(
                true,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.COMMITTED,
                        null,
                        null,
                        null
                ))
        );
    }

    private PersistenceWriteQueue.WriteSubmission<Void> rejectedVoidSubmission() {
        return new PersistenceWriteQueue.WriteSubmission<>(
                false,
                CompletableFuture.completedFuture(new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.REJECTED,
                        null,
                        "forced_rejection",
                        null
                ))
        );
    }

    private CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost(UUID sourceUuid) {
        return new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                sourceUuid, null, null, 10L, 20L, 3, null, 0L);
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot(UUID sourceUuid) {
        UUID ownerUuid = uuid(99);
        TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
        lifeStage.setBornAtMs(-106L);
        lifeStage.setAdultAtMs(-107L);
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                sourceUuid,
                null,
                -1,
                "tamed_test",
                new TameworkCommandLinksComponent(ownerUuid, new String[] {"tool-a"}),
                new TameworkOwnerComponent(ownerUuid, "Owner"),
                new TameworkTamedComponent(true),
                new TameworkNpcNameComponent("Companion", ownerUuid, -100L, TameworkNpcNameComponent.NameSource.Player),
                new TameworkHappinessComponent("happy", 0.8, -101L),
                new TameworkNeedsComponent("needs", 0.1, 0.2, 0.0, 0.0, -102L, -103L),
                new TameworkBreedingComponent("breed", 0.8, -103L, true, true, -104L, null, -105L, 500L),
                new TameworkLevelingComponent("level", 2, 4.0, 8.0),
                new TameworkTraitsComponent("traits", 55L, new TameworkTraitsComponent.TraitValue[] {
                    new TameworkTraitsComponent.TraitValue("friendly", 1.0)
                }),
                new TameworkTalentsComponent("talents", 1, new String[] {"swift"}),
                lifeStage,
                new TameworkAttachmentsComponent("attachments", Map.of("head", "crest")),
                88.0,
                -999L
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
