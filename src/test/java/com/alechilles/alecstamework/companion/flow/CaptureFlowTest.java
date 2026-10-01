package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureFlowTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final List<SnapshotEnvelope> snapshots = new ArrayList<>();
    private final UUID owner = UUID.randomUUID();

    private CompanionTransitions.BodyFacts facts(UUID npcUuid) {
        return new CompanionTransitions.BodyFacts(npcUuid, owner, "Alec", "Tamed_Sheep", "Wooly", "default", 0, 0, 0,
                List.of(), CompanionSummary.EMPTY);
    }

    private CompanionRecord insertLive(long generation) {
        UUID npc = UUID.randomUUID();
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), generation, facts(npc));
        index.insert(r);
        loaded.put(r.profileId(), "body");
        return index.get(r.profileId());
    }

    private CaptureFlow<String> flow(Function<UUID, CompletableFuture<Void>> flush) {
        return new CaptureFlow<>(index, loaded, (id, envelope) -> { events.add("snapshot"); snapshots.add(envelope); },
                owner -> { events.add("flush"); return flush.apply(owner); });
    }

    private CaptureFlow<String> flow(CompletableFuture<Void> flush) {
        return flow(owner -> flush);
    }

    private static final BsonDocument DATA = new BsonDocument("Entity", new BsonString("x"));

    private CaptureFlow.Capture<String> stamped(CompanionRecord live, UUID newOwner) {
        return new CaptureFlow.Capture<>(live.profileId(), live.generation(), "body", facts(live.currentNpcUuid()),
                newOwner, newOwner == null ? null : "Bo", DATA);
    }

    @Test
    void aStampedCaptureCommitsItemUnregistersTheBodyAndFlushesBeforeReturning() {
        CompanionRecord live = insertLive(2);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(live.profileId(), outcome.itemRef().profileId());
        assertEquals(3, outcome.itemRef().generation());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.ITEM, after.location().kind());
        assertEquals(3, after.generation());
        assertNull(after.currentNpcUuid());
        assertNull(loaded.get(live.profileId()));
        assertEquals(List.of("snapshot", "flush"), events);
        assertEquals(3, snapshots.get(0).generation());
        assertEquals(CompanionSnapshots.FORMAT, snapshots.get(0).format());
    }

    @Test
    void aStaleStampIsRefusedAndNothingChanges() {
        CompanionRecord live = insertLive(2);
        CaptureFlow.Capture<String> stale = new CaptureFlow.Capture<>(live.profileId(), 1, "body",
                facts(live.currentNpcUuid()), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null)).capture(stale).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, outcome.result());
        assertNull(outcome.itemRef());
        assertTrue(events.isEmpty());
        assertEquals(live, index.get(live.profileId()));
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aDifferentRegisteredBodyForTheProfileRefusesTheCapture() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "other-body");

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, outcome.result());
        assertTrue(events.isEmpty());
        assertEquals(live, index.get(live.profileId()));
        assertEquals("other-body", loaded.get(live.profileId()));
    }

    @Test
    void anEditThatKeepsTheItemHolderDuringTheFlushStillCaptures() {
        CompanionRecord live = insertLive(2);
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = flow(flush).capture(stamped(live, owner));
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(), b -> b.displayName("Renamed"));

        flush.complete(null);

        CaptureFlow.Outcome outcome = pending.join();
        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(3, outcome.itemRef().generation());
        assertEquals("Renamed", index.get(live.profileId()).displayName());
    }

    @Test
    void aSnapshotQueueFailureUndoesTheCaptureWithoutFailingTheFuture() {
        CompanionRecord live = insertLive(2);
        CaptureFlow<String> flow = new CaptureFlow<>(index, loaded,
                (id, envelope) -> { throw new IllegalStateException("queue full"); },
                owner -> CompletableFuture.completedFuture(null));

        CaptureFlow.Outcome outcome = flow.capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aFailedFlushLeavesTheRecordLiveAndTheBodyRegistered() {
        CompanionRecord live = insertLive(2);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(stamped(live, owner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertNull(outcome.itemRef());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals(live.currentNpcUuid(), after.currentNpcUuid());
        assertEquals("body", loaded.get(live.profileId()));
        SnapshotEnvelope last = snapshots.get(snapshots.size() - 1);
        assertEquals(2, last.generation(), "the snapshot is not newer than the restored record");
    }

    @Test
    void aFailedFlushOfTheOldOwnerRevertsAnOwnerChange() {
        CompanionRecord live = insertLive(0);
        UUID newOwner = UUID.randomUUID();

        CaptureFlow.Outcome outcome = flow(who -> who != null && who.equals(newOwner)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(stamped(live, newOwner)).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        assertEquals(List.of("snapshot", "flush", "flush", "snapshot"), events, "new owner first, then the old one");
        assertEquals(owner, index.get(live.profileId()).ownerUuid());
        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
        assertEquals("body", loaded.get(live.profileId()));
    }

    @Test
    void aChangeDuringTheFlushWinsAndTheCaptureIsAConflict() {
        CompanionRecord live = insertLive(0);
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<CaptureFlow.Outcome> pending = flow(flush).capture(stamped(live, owner));
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.complete(null);

        CaptureFlow.Outcome outcome = pending.join();
        assertEquals(CaptureFlow.Result.CONFLICT, outcome.result());
        assertNull(outcome.itemRef());
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
    }

    @Test
    void anUnstampedBodyGetsANewItemRecordAtGenerationZero() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null)).capture(capture).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertEquals(0, outcome.itemRef().generation());
        CompanionRecord record = index.get(outcome.itemRef().profileId());
        assertNotNull(record);
        assertEquals(LocationKind.ITEM, record.location().kind());
        assertEquals("Tamed_Sheep", record.roleId());
        assertEquals("Wooly", record.displayName());
        assertEquals(owner, record.ownerUuid());
        assertEquals(0, snapshots.get(0).generation());
    }

    @Test
    void aSecondUnstampedCaptureOfTheSameNpcIsRefused() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);
        CaptureFlow<String> flow = flow(CompletableFuture.completedFuture(null));
        flow.capture(capture).join();

        CaptureFlow.Outcome second = flow.capture(capture).join();

        assertEquals(CaptureFlow.Result.NOT_CAPTURABLE, second.result());
        assertEquals(1, index.fileRecords(owner).size());
    }

    @Test
    void aFailedUnstampedCaptureLeavesATombstoneInsteadOfAnItem() {
        UUID npc = UUID.randomUUID();
        CaptureFlow.Capture<String> capture = new CaptureFlow.Capture<>(null, 0, "wild", facts(npc), owner, "Alec", DATA);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.failedFuture(new RuntimeException("disk")))
                .capture(capture).join();

        assertEquals(CaptureFlow.Result.COMMIT_FAILED, outcome.result());
        List<CompanionRecord> filed = index.fileRecords(owner);
        assertEquals(1, filed.size());
        assertEquals(LocationKind.RELEASED, filed.get(0).location().kind());
    }

    @Test
    void aCaptureThatClearsTheOwnerFilesTheRecordUnowned() {
        CompanionRecord live = insertLive(0);

        CaptureFlow.Outcome outcome = flow(CompletableFuture.completedFuture(null))
                .capture(stamped(live, null)).join();

        assertEquals(CaptureFlow.Result.CAPTURED, outcome.result());
        assertTrue(index.fileRecords(null).stream().anyMatch(r -> r.profileId().equals(live.profileId())));
        assertTrue(index.fileRecords(owner).isEmpty());
        assertEquals(List.of("snapshot", "flush", "flush"), events, "the unowned file and the old owner's file");
    }
}
