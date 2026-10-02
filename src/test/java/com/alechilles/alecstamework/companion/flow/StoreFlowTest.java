package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFlowTest {
    private static final long NOW = 5_000L;
    private static final BsonDocument ENTITY = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()));

    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final List<SnapshotEnvelope> queued = new ArrayList<>();
    private final UUID owner = UUID.randomUUID();
    private CompletableFuture<Void> flush = CompletableFuture.completedFuture(null);
    private CompletableFuture<StoreFlow.CapturedBody> captured = CompletableFuture.completedFuture(
            new StoreFlow.CapturedBody(ENTITY, CompanionSummary.EMPTY));
    private SnapshotEnvelope stored;
    private CompletableFuture<SnapshotEnvelope> storedRead;

    private CompanionRecord insertLive(long generation) {
        CompanionTransitions.BodyFacts facts = new CompanionTransitions.BodyFacts(UUID.randomUUID(), owner, "Alec",
                "Tamed_Sheep", "Wooly", "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY);
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), generation, facts);
        index.insert(r);
        return index.get(r.profileId());
    }

    private StoreFlow<String> flow() {
        return new StoreFlow<>(index, loaded,
                body -> { events.add("capture"); return captured; },
                id -> storedRead != null ? storedRead : CompletableFuture.completedFuture(stored),
                (id, envelope) -> { events.add("snapshot"); queued.add(envelope); },
                who -> { events.add("flush"); return flush; },
                (id, body) -> events.add("remove"), () -> NOW);
    }

    @Test
    void aLoadedBodyIsSnapshottedCommittedStoredFlushedAndOnlyThenRemoved() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "body");

        StoreFlow.Result result = flow().store(live.profileId(), StoredReason.ROSTER, 0L).join();

        assertEquals(StoreFlow.Result.STORED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.STORED, after.location().kind());
        assertEquals(StoredReason.ROSTER, after.location().reason());
        assertEquals(3, after.generation());
        assertNull(after.currentNpcUuid());
        assertEquals(NOW, after.lastSnapshotAtMs());
        assertNull(loaded.get(live.profileId()));
        assertEquals(List.of("capture", "snapshot", "flush", "remove"), events);
        assertEquals(3, queued.get(0).generation());
        assertEquals(CompanionSnapshots.FORMAT, queued.get(0).format());
    }

    @Test
    void anExtensionWriteWhileTheStoreIsBeingWrittenDoesNotMakeTheStoreAConflict() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "body");
        flush = new CompletableFuture<>();
        CompletableFuture<StoreFlow.Result> store = flow().store(live.profileId(), StoredReason.ROSTER, 0L);
        CompanionRecord committed = index.get(live.profileId());

        index.update(live.profileId(), committed.revision(), b -> b.extension("hydragon/bonded",
                new com.alechilles.alecstamework.companion.index.ExtensionEntry(1L, "{}")));
        flush.complete(null);

        assertEquals(StoreFlow.Result.STORED, store.join());
        assertEquals(LocationKind.STORED, index.get(live.profileId()).location().kind());
        assertEquals(1, index.get(live.profileId()).extensions().size());
    }

    @Test
    void anUnloadedBodyUsesTheStoredSnapshotAndRemovesNothing() {
        CompanionRecord live = insertLive(2);
        stored = new SnapshotEnvelope(live.profileId(), CompanionSnapshots.FORMAT, 2, ENTITY);

        StoreFlow.Result result = flow().store(live.profileId(), StoredReason.TIMED, 0L).join();

        assertEquals(StoreFlow.Result.STORED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals(StoredReason.TIMED, after.location().reason());
        assertEquals(3, after.generation());
        assertEquals(live.lastSnapshotAtMs(), after.lastSnapshotAtMs());
        assertEquals(List.of("flush"), events);
        assertTrue(queued.isEmpty());
    }

    @Test
    void withoutAUsableSnapshotNothingIsStoredAndTheCompanionStaysLive() {
        CompanionRecord live = insertLive(2);
        stored = null;

        StoreFlow.Result result = flow().store(live.profileId(), StoredReason.ROSTER, 0L).join();

        assertEquals(StoreFlow.Result.NO_SNAPSHOT, result);
        assertEquals(live, index.get(live.profileId()));
        assertTrue(events.isEmpty());
    }

    @Test
    void aFailedFlushLeavesTheRecordLiveTheBodyRegisteredAndTheSnapshotNotNewerThanTheRecord() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "body");
        flush = CompletableFuture.failedFuture(new RuntimeException("disk"));

        StoreFlow.Result result = flow().store(live.profileId(), StoredReason.ROSTER, 0L).join();

        assertEquals(StoreFlow.Result.COMMIT_FAILED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals(2, after.generation());
        assertEquals(live.currentNpcUuid(), after.currentNpcUuid());
        assertEquals("body", loaded.get(live.profileId()));
        assertEquals(List.of("capture", "snapshot", "flush", "snapshot"), events, "the body is never removed");
        assertEquals(2, queued.get(queued.size() - 1).generation());
    }

    @Test
    void theCooldownIsWrittenAndTheSummonTimerCleared() {
        CompanionRecord live = insertLive(0);
        index.update(live.profileId(), live.revision(), b -> b.summonedUntilMs(9_000L));
        loaded.put(live.profileId(), "body");

        flow().store(live.profileId(), StoredReason.TIMED, 12_000L).join();

        CompanionRecord after = index.get(live.profileId());
        assertEquals(12_000L, after.summonCooldownUntilMs());
        assertEquals(0L, after.summonedUntilMs());
    }

    @Test
    void aChangeDuringTheFlushWinsAndTheStaleBodyIsRemoved() {
        CompanionRecord live = insertLive(0);
        loaded.put(live.profileId(), "body");
        flush = new CompletableFuture<>();
        CompletableFuture<StoreFlow.Result> pending = flow().store(live.profileId(), StoredReason.ROSTER, 0L);
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.complete(null);

        assertEquals(StoreFlow.Result.CONFLICT, pending.join());
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
        assertTrue(events.contains("remove"));
    }

    @Test
    void aCompanionThatIsNotLiveIsRefused() {
        CompanionRecord live = insertLive(0);
        index.update(live.profileId(), live.revision(), CompanionTransitions.released(live));

        assertEquals(StoreFlow.Result.NOT_LIVE, flow().store(live.profileId(), StoredReason.ROSTER, 0L).join());
        assertEquals(StoreFlow.Result.NOT_FOUND, flow().store(UUID.randomUUID(), StoredReason.ROSTER, 0L).join());
    }

    @Test
    void aBodyThatRegistersWhileTheStoredSnapshotIsReadBlocksTheStore() {
        CompanionRecord live = insertLive(2);
        storedRead = new CompletableFuture<>();
        CompletableFuture<StoreFlow.Result> pending = flow().store(live.profileId(), StoredReason.ROSTER, 0L);
        loaded.put(live.profileId(), "late-body");

        storedRead.complete(new SnapshotEnvelope(live.profileId(), CompanionSnapshots.FORMAT, 2, ENTITY));

        assertEquals(StoreFlow.Result.CONFLICT, pending.join());
        assertEquals(live, index.get(live.profileId()));
        assertEquals("late-body", loaded.get(live.profileId()));
        assertTrue(events.isEmpty(), "nothing was flushed or removed");
    }

    @Test
    void aCaptureThatIsNotARestorableSnapshotChangesNothing() {
        CompanionRecord live = insertLive(2);
        loaded.put(live.profileId(), "body");
        captured = CompletableFuture.completedFuture(new StoreFlow.CapturedBody(new BsonDocument(), CompanionSummary.EMPTY));

        StoreFlow.Result result = flow().store(live.profileId(), StoredReason.ROSTER, 0L).join();

        assertEquals(StoreFlow.Result.COMMIT_FAILED, result);
        assertEquals(live, index.get(live.profileId()));
        assertEquals("body", loaded.get(live.profileId()));
        assertEquals(List.of("capture"), events);
    }

    @Test
    void aFailedFlushAfterANewerChangeLeavesThatChangeAndRemovesTheStaleBody() {
        CompanionRecord live = insertLive(0);
        loaded.put(live.profileId(), "body");
        flush = new CompletableFuture<>();
        CompletableFuture<StoreFlow.Result> pending = flow().store(live.profileId(), StoredReason.ROSTER, 0L);
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.completeExceptionally(new RuntimeException("disk"));

        assertEquals(StoreFlow.Result.COMMIT_FAILED, pending.join());
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
        assertNull(loaded.get(live.profileId()));
        assertTrue(events.contains("remove"));
    }
}
