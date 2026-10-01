package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureItemFlowsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final List<UUID> deleted = new ArrayList<>();
    private final CaptureItemFlows flows = new CaptureItemFlows(index, deleted::add, new RestoreFlow<String>(
            index, new LoadedBodies<>(), id -> CompletableFuture.completedFuture(snapshot(index.get(id))),
            owner -> CompletableFuture.completedFuture(null),
            (committed, snapshot, destination, reason) -> CompletableFuture.completedFuture(true),
            (id, body) -> { }, System::currentTimeMillis, (before, after) -> null));

    private static CompanionTransitions.BodyFacts body() {
        return new CompanionTransitions.BodyFacts(UUID.randomUUID(), OWNER, "Alec", "Tamed_Sheep", null, "default",
                0, 0, 0, List.of(), CompanionSummary.EMPTY);
    }

    private CompanionRecord insertItem() {
        CompanionRecord record = CompanionTransitions.newItem(UUID.randomUUID(), body(), OWNER, "Alec");
        index.insert(record);
        return index.get(record.profileId());
    }

    private static SnapshotEnvelope snapshot(CompanionRecord record) {
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(record.profileId(), CompanionSnapshots.FORMAT, record.generation(), data);
    }

    @Test
    void forgetTombstonesTheItemRecordAndFreesTheOwnedSlot() {
        CompanionRecord item = insertItem();
        assertEquals(1, index.ownedCount(OWNER));

        assertEquals(CaptureItemFlows.Result.FORGOTTEN, flows.forget(item.profileId(), OWNER));

        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.RELEASED, after.location().kind());
        assertEquals(CompanionTransitions.CAUSE_FORGOTTEN, after.location().cause());
        assertEquals(0, index.ownedCount(OWNER));
        assertEquals(List.of(item.profileId()), deleted);
    }

    @Test
    void forgetByAnotherPlayerIsRefused() {
        CompanionRecord item = insertItem();

        assertEquals(CaptureItemFlows.Result.NOT_OWNER, flows.forget(item.profileId(), UUID.randomUUID()));

        assertEquals(LocationKind.ITEM, index.get(item.profileId()).location().kind());
        assertTrue(deleted.isEmpty());
    }

    @Test
    void forgetOfALiveCompanionIsRefused() {
        CompanionRecord live = CompanionTransitions.newLive(UUID.randomUUID(), 0, body());
        index.insert(live);

        assertEquals(CaptureItemFlows.Result.NOT_IN_ITEM, flows.forget(live.profileId(), null));

        assertEquals(LocationKind.LIVE, index.get(live.profileId()).location().kind());
        assertTrue(deleted.isEmpty());
    }

    @Test
    void recallRaisesTheGenerationSoTheOldItemCanNoLongerRelease() {
        CompanionRecord item = insertItem();
        long oldGeneration = item.generation();

        RestoreFlow.Result result = flows.recall(item.profileId(),
                new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f)).join();

        assertEquals(RestoreFlow.Result.RESTORED, result);
        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertTrue(after.generation() > oldGeneration);
        assertNotEquals(RestoreRules.Verdict.ALLOWED,
                RestoreRules.forRecord(after, RestoreRules.Reason.RELEASE, System.currentTimeMillis(), oldGeneration));
    }

    @Test
    void aSuccessfulForgetOrRecallAsksToEmptyTheOwnersHeldCopiesOfTheItem() {
        List<String> swept = new ArrayList<>();
        flows.useHeldItemSweep((owner, profileId) -> swept.add(owner + ":" + profileId));
        CompanionRecord forgotten = insertItem();
        CompanionRecord recalled = insertItem();

        // An admin forget (no acting owner) still sweeps the record owner.
        flows.forget(forgotten.profileId(), null);
        flows.recall(recalled.profileId(), new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f)).join();

        assertEquals(List.of(OWNER + ":" + forgotten.profileId(), OWNER + ":" + recalled.profileId()), swept);
    }

    @Test
    void aRefusedForgetAndARestoreOfACompanionOutsideAnItemSweepNothing() {
        List<UUID> swept = new ArrayList<>();
        flows.useHeldItemSweep((owner, profileId) -> swept.add(profileId));
        CompanionRecord item = insertItem();
        CompanionRecord live = CompanionTransitions.newLive(UUID.randomUUID(), 0, body());
        index.insert(live);

        flows.forget(item.profileId(), UUID.randomUUID());
        flows.recall(live.profileId(), new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f)).join();

        assertTrue(swept.isEmpty());
    }

    @Test
    void aDestroyedItemAtTheRecordsGenerationLeavesTheCompanionLostAndRecoverable() {
        CompanionRecord item = insertItem();

        assertEquals(false, flows.itemDestroyed(new CaptureItemKeys.Ref(item.profileId(), item.generation() + 5)));
        assertEquals(LocationKind.ITEM, index.get(item.profileId()).location().kind());

        assertEquals(true, flows.itemDestroyed(new CaptureItemKeys.Ref(item.profileId(), item.generation())));
        CompanionRecord after = index.get(item.profileId());
        assertEquals(LocationKind.LOST, after.location().kind());
        assertEquals(CompanionTransitions.CAUSE_ITEM_DESTROYED, after.location().cause());
        // The owner keeps the companion and its snapshot, and can recover it.
        assertEquals(1, index.ownedCount(OWNER));
        assertTrue(deleted.isEmpty());
        assertEquals(RestoreRules.Verdict.ALLOWED,
                RestoreRules.forRecord(after, RestoreRules.Reason.RECOVER, System.currentTimeMillis()));
        // A copy of the vanished item can no longer release it.
        assertNotEquals(RestoreRules.Verdict.ALLOWED, RestoreRules.forRecord(after, RestoreRules.Reason.RELEASE,
                System.currentTimeMillis(), item.generation()));
    }
}
