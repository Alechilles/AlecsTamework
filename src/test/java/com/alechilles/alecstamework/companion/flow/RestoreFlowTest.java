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
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreFlowTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<String> loaded = new LoadedBodies<>();
    private final List<String> events = new ArrayList<>();
    private final RestoreFlow.Destination there = new RestoreFlow.Destination("other", 1, 2, 3, 0f, 0f);

    private CompanionRecord insertLive() {
        CompanionRecord r = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                UUID.randomUUID(), UUID.randomUUID(), "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(r);
        return index.get(r.profileId());
    }

    private static SnapshotEnvelope snapshot(CompanionRecord r) {
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(r.profileId(), CompanionSnapshots.FORMAT, r.generation(), data);
    }

    private RestoreFlow<String> flow(CompletableFuture<Void> flush, boolean spawnOk) {
        return new RestoreFlow<>(index, loaded,
                id -> CompletableFuture.completedFuture(snapshot(index.get(id))),
                owner -> { events.add("flush"); return flush; },
                (committed, snap, dest, reason) -> { events.add("spawn gen" + committed.generation());
                    return CompletableFuture.completedFuture(spawnOk); },
                (id, body) -> events.add("remove " + body),
                System::currentTimeMillis);
    }

    @Test
    void aRecallCommitsBeforeItRemovesTheOldBodyAndSpawnsTheNewOne() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.RESTORED, result);
        assertEquals(List.of("flush", "remove old-body", "spawn gen1"), events);
        CompanionRecord after = index.get(live.profileId());
        assertEquals("other", after.location().world());
        assertEquals(1, after.generation());
        assertEquals(null, loaded.get(live.profileId()), "the old body is no longer registered");
    }

    @Test
    void aFailedFlushChangesNothingLive() {
        CompanionRecord live = insertLive();
        loaded.put(live.profileId(), "old-body");

        RestoreFlow.Result result = flow(CompletableFuture.failedFuture(new RuntimeException("disk")), true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.COMMIT_FAILED, result);
        assertEquals(List.of("flush"), events, "no removal and no spawn");
        assertEquals("default", index.get(live.profileId()).location().world());
        assertEquals("old-body", loaded.get(live.profileId()), "the old body is registered again");
    }

    @Test
    void aChangeDuringTheRestoreWinsAndNothingSpawns() {
        CompanionRecord live = insertLive();
        CompletableFuture<Void> flush = new CompletableFuture<>();
        CompletableFuture<RestoreFlow.Result> pending = flow(flush, true)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there);
        CompanionRecord committed = index.get(live.profileId());
        index.update(live.profileId(), committed.revision(),
                CompanionTransitions.died(committed, CompanionSummary.EMPTY, 5L, 6L, "PLAYER", null));

        flush.complete(null);

        assertEquals(RestoreFlow.Result.CONFLICT, pending.join());
        assertTrue(events.stream().noneMatch(e -> e.startsWith("spawn")));
        assertEquals(LocationKind.DEAD, index.get(live.profileId()).location().kind());
    }

    @Test
    void aFailedSpawnPutsTheRecordBack() {
        CompanionRecord live = insertLive();

        RestoreFlow.Result result = flow(CompletableFuture.completedFuture(null), false)
                .restore(live.profileId(), RestoreRules.Reason.RECALL, there).join();

        assertEquals(RestoreFlow.Result.SPAWN_FAILED, result);
        CompanionRecord after = index.get(live.profileId());
        assertEquals("default", after.location().world());
    }
}
