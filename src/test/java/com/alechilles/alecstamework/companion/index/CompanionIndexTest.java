package com.alechilles.alecstamework.companion.index;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionIndexTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final List<String> changes = new ArrayList<>();
    private final CompanionIndex index = new CompanionIndex(() -> 1000L,
            (before, after) -> changes.add((before == null ? "null" : String.valueOf(before.ownerUuid())) + "->" + after.ownerUuid()));

    private static CompanionRecord live(UUID owner) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("default", 1, 64, 1))
                .ownerUuid(owner).build();
    }

    @Test
    void staleRevisionUpdateIsRejectedAndLeavesTheRecordUnchanged() {
        CompanionRecord r = live(ALICE);
        index.insert(r);
        assertTrue(index.update(r.profileId(), 0, b -> b.displayName("Wooly")).applied());

        CompanionIndex.Mutation stale = index.update(r.profileId(), 0, b -> b.displayName("Other"));

        assertEquals(CompanionIndex.Status.CONFLICT, stale.status());
        assertEquals("Wooly", index.get(r.profileId()).displayName());
        assertEquals(1, index.get(r.profileId()).revision());
    }

    @Test
    void ownerChangeMovesTheRecordBetweenOwnersAndReportsBothSides() {
        CompanionRecord r = live(ALICE);
        index.insert(r);

        index.update(r.profileId(), 0, b -> b.ownerUuid(BOB));

        assertTrue(index.fileRecords(ALICE).isEmpty());
        assertEquals(r.profileId(), index.fileRecords(BOB).get(0).profileId());
        assertEquals(List.of("null->" + ALICE, ALICE + "->" + BOB), changes);
    }

    @Test
    void countsExcludeTombstonesAndOnlyLiveRecordsAreDeployed() {
        index.insert(live(ALICE));
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.stored(StoredReason.ROSTER)).ownerUuid(ALICE).build());
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.item()).ownerUuid(ALICE).build());
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.released("owner_release")).ownerUuid(ALICE).build());

        assertEquals(3, index.ownedCount(ALICE));
        assertEquals(1, index.deployedCount(ALICE));
        assertEquals(4, index.fileRecords(ALICE).size());
    }

    @Test
    void npcLookupFollowsTheCurrentBodyOnly() {
        CompanionRecord r = live(ALICE);
        UUID firstBody = UUID.randomUUID();
        UUID secondBody = UUID.randomUUID();
        index.insert(r.toBuilder().currentNpcUuid(firstBody).build());

        index.update(r.profileId(), 0, b -> b.currentNpcUuid(secondBody));

        assertNull(index.byNpcUuid(firstBody));
        assertEquals(r.profileId(), index.byNpcUuid(secondBody).profileId());
    }

    @Test
    void provisioningOriginIsUnique() {
        index.insert(live(ALICE).toBuilder().origin("hydragon", "soul-1").build());

        CompanionIndex.Mutation second = index.insert(live(BOB).toBuilder().origin("hydragon", "soul-1").build());

        assertEquals(CompanionIndex.Status.DUPLICATE, second.status());
        assertEquals(ALICE, index.byOrigin("hydragon", "soul-1").ownerUuid());
    }

    @Test
    void updateCannotTakeAnotherRecordsOrigin() {
        CompanionRecord a = live(ALICE).toBuilder().origin("hydragon", "soul-1").build();
        CompanionRecord b = live(BOB);
        index.insert(a);
        index.insert(b);

        CompanionIndex.Mutation taken = index.update(b.profileId(), 0, x -> x.origin("hydragon", "soul-1"));

        assertEquals(CompanionIndex.Status.DUPLICATE, taken.status());
        assertEquals(a.profileId(), taken.before().profileId());
        assertEquals(a.profileId(), index.byOrigin("hydragon", "soul-1").profileId());
    }

    @Test
    void readersNeverMissARecordDuringConcurrentUpdates() throws Exception {
        UUID body = UUID.randomUUID();
        CompanionRecord r = live(ALICE).toBuilder().currentNpcUuid(body).build();
        index.insert(r);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                long revision = 0;
                for (int i = 0; i < 20_000; i++) {
                    String name = "Name" + i;
                    CompanionIndex.Mutation m = index.update(r.profileId(), revision, b -> b.displayName(name));
                    revision = m.after().revision();
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 200_000; i++) {
                assertEquals(1, index.fileRecords(ALICE).size());
                assertEquals(1, index.ownedCount(ALICE));
                assertNotNull(index.byNpcUuid(body));
            }
        } finally {
            writer.join();
        }
        assertNull(writerFailure.get());
    }

    @Test
    void generationCannotGoBackwards() {
        CompanionRecord r = live(ALICE).toBuilder().generation(3).build();
        index.insert(r);

        assertThrows(IllegalArgumentException.class, () -> index.update(r.profileId(), 0, b -> b.generation(2)));
    }

    @Test
    void revertRestoresTheUncommittedRecordIncludingItsGeneration() {
        UUID body = UUID.randomUUID();
        index.insert(live(ALICE).toBuilder().currentNpcUuid(body).build());
        CompanionRecord previous = index.fileRecords(ALICE).get(0);
        CompanionIndex.Mutation capture = index.update(previous.profileId(), 0,
                b -> b.generation(1).location(CompanionLocation.item()).currentNpcUuid(null));

        CompanionIndex.Mutation reverted = index.revert(previous.profileId(), capture.after().revision(), previous);

        assertTrue(reverted.applied());
        CompanionRecord now = index.get(previous.profileId());
        assertEquals(0, now.generation());
        assertEquals(previous.location(), now.location());
        assertEquals(2, now.revision());
        assertEquals(previous.profileId(), index.byNpcUuid(body).profileId());
        assertEquals(CompanionIndex.Status.CONFLICT, index.revert(previous.profileId(), 1, previous).status());
    }

    @Test
    void checkAndApplyUnderTheLockIsAtomicAcrossThreads() throws Exception {
        int cap = 5;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            pool.execute(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                boolean ok = index.atomically(() -> {
                    if (index.ownedCount(ALICE) >= cap) return false;
                    return index.insert(live(ALICE)).applied();
                });
                if (ok) admitted.incrementAndGet();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(cap, admitted.get());
        assertEquals(cap, index.ownedCount(ALICE));
    }
}
