package com.alechilles.alecstamework.companion.network;

import com.alechilles.alecstamework.api.PortableCompanionEntry;
import com.alechilles.alecstamework.api.PortableCompanionStore;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two servers, each with its own index and snapshot files, sharing one in-memory network store. */
class PortableSyncTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    /** The network's store: keeps per profile the entry that supersedes the others, as the contract asks. */
    private static final class MemoryStore {
        final Map<UUID, PortableCompanionEntry> rows = new ConcurrentHashMap<>();
        volatile boolean down;

        PortableCompanionStore as(String holder) {
            return new PortableCompanionStore() {
                @Override
                public String holderId() {
                    return holder;
                }

                @Override
                public List<PortableCompanionEntry> load(UUID owner, Collection<UUID> ids) {
                    if (down) {
                        throw new IllegalStateException("store down");
                    }
                    List<PortableCompanionEntry> out = new ArrayList<>();
                    for (PortableCompanionEntry row : rows.values()) {
                        if (owner != null && owner.equals(row.ownerUuid()) || ids.contains(row.profileId())) {
                            out.add(row);
                        }
                    }
                    return out;
                }

                @Override
                public void save(List<PortableCompanionEntry> entries) {
                    if (down) {
                        throw new IllegalStateException("store down");
                    }
                    for (PortableCompanionEntry entry : entries) {
                        rows.merge(entry.profileId(), entry, (old, next) -> next.supersedes(old) ? next : old);
                    }
                }
            };
        }
    }

    private static final AtomicLong CLOCK = new AtomicLong(1_000L);
    private final MemoryStore network = new MemoryStore();
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    /** One server: its index, its snapshot files, its sync. Files count as written only when {@link #written} runs. */
    private final class Server {
        final CompanionIndex index = new CompanionIndex(CLOCK::incrementAndGet, (b, a) -> { });
        final Map<UUID, SnapshotEnvelope> snapshots = new ConcurrentHashMap<>();
        volatile boolean snapshotReadsFail;
        final PortableSync sync;

        Server(String id) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executors.add(executor);
            sync = new PortableSync(index,
                    profileId -> snapshotReadsFail ? CompletableFuture.failedFuture(new IllegalStateException("disk"))
                            : CompletableFuture.completedFuture(snapshots.get(profileId)),
                    snapshot -> snapshots.put(snapshot.profileId(), snapshot), snapshots::remove,
                    executor, CLOCK::get);
            index.addAfterUnlockListener(sync::onRecordChanged);
            sync.register(network.as(id));
            sync.push(Integer.MAX_VALUE);
        }

        /** The writer wrote every owner file. */
        void written() {
            List<CompanionRecord> all = new ArrayList<>();
            index.forEach(all::add);
            sync.onWritten(all);
        }

        CompanionRecord capture(UUID profileId, long generation) {
            snapshots.put(profileId, snapshot(profileId, generation));
            CompanionRecord current = index.get(profileId);
            if (current == null) {
                index.insert(CompanionRecord.builder(profileId, "Cow", CompanionLocation.item())
                        .generation(generation).ownerUuid(ALICE).homeWorld("default").build());
            } else {
                index.update(profileId, current.revision(), b -> b.generation(generation).location(CompanionLocation.item()));
            }
            return index.get(profileId);
        }

        void release(UUID profileId) {
            CompanionRecord current = index.get(profileId);
            index.update(profileId, current.revision(), b -> b.generation(current.generation() + 1)
                    .location(CompanionLocation.live("default", 1, 64, 1)));
        }
    }

    private static SnapshotEnvelope snapshot(UUID profileId, long generation) {
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(profileId, 1, generation, data);
    }

    @AfterEach
    void stop() {
        executors.forEach(ScheduledExecutorService::shutdownNow);
    }

    @Test
    void aLanternFilledOnOneServerReleasesOnAnotherAndTheFirstStopsHoldingIt() throws Exception {
        Server a = new Server("A");
        Server b = new Server("B");
        UUID cow = UUID.randomUUID();

        a.capture(cow, 1);
        a.written();
        a.sync.push(100);
        b.sync.loadOwner(ALICE);

        CompanionRecord arrived = b.index.get(cow);
        assertNotNull(arrived, "the companion followed its owner");
        assertEquals(LocationKind.ITEM, arrived.location().kind());
        assertEquals(1L, arrived.generation());
        assertEquals(1L, b.snapshots.get(cow).generation(), "its snapshot came with it");

        b.release(cow);
        b.written();
        b.sync.push(100);
        a.sync.loadOwner(ALICE);

        CompanionRecord left = a.index.get(cow);
        assertTrue(PortableSync.isElsewhere(left), "A keeps a tombstone that no longer counts");
        assertEquals(2L, left.generation(), "the tombstone fences A's older holders");
        assertNull(a.snapshots.get(cow), "A drops its stale snapshot");
        List<CompanionRecord> counted = a.sync.ownerRecords(ALICE);
        CompanionRecord standIn = counted.stream().filter(r -> r.profileId().equals(cow) && r.isDeployed())
                .findFirst().orElseThrow();
        assertEquals("B/default", standIn.location().world(), "B's animal counts on A, in B's world");
    }

    @Test
    void aChangeLeavesTheServerOnlyOnceItsFileIsWritten() {
        Server a = new Server("A");
        UUID cow = UUID.randomUUID();
        a.capture(cow, 1);

        a.sync.push(100);
        assertTrue(network.rows.isEmpty(), "a change whose commit could still be reverted is not sent");

        a.written();
        a.sync.push(100);
        assertEquals(1L, network.rows.get(cow).generation());
    }

    @Test
    void aJoinKeepsALocalCopyNewerThanTheNetworkAndTakesOneThatIsOlder() throws Exception {
        Server a = new Server("A");
        Server b = new Server("B");
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        // The network has generation 1 of both; A moved "mine" on while the store was down.
        a.capture(mine, 1);
        b.capture(theirs, 1);
        a.written();
        b.written();
        a.sync.push(100);
        b.sync.push(100);
        a.sync.loadOwner(ALICE);
        network.down = true;
        a.capture(mine, 3);
        a.written();
        a.sync.push(100);
        network.down = false;
        b.capture(theirs, 4);
        b.written();
        b.sync.push(100);

        a.sync.loadOwner(ALICE);

        assertEquals(3L, a.index.get(mine).generation(), "the network's older copy never replaces A's");
        assertEquals(4L, a.index.get(theirs).generation(), "A took the network's newer copy");
        CLOCK.addAndGet(60_000L);   // past the retry backoff
        a.sync.push(100);
        assertEquals(3L, network.rows.get(mine).generation(), "A's newer copy reached the network");
    }

    @Test
    void aPortableCopyIsNeverSentWithoutItsSnapshot() {
        Server a = new Server("A");
        UUID cow = UUID.randomUUID();
        a.capture(cow, 1);
        a.written();
        a.snapshotReadsFail = true;

        a.sync.push(100);
        assertTrue(network.rows.isEmpty());
        assertEquals(1, a.sync.outboxForTests().size(), "it waits for the next push");

        a.snapshotReadsFail = false;
        a.sync.push(100);
        assertNotNull(network.rows.get(cow).snapshotJson());
    }

    @Test
    void aRefreshBringsInACompanionThisServerHasNotSeen() throws Exception {
        Server a = new Server("A");
        Server b = new Server("B");
        UUID cow = UUID.randomUUID();
        a.capture(cow, 2);
        a.written();
        a.sync.push(100);

        assertTrue(b.sync.mayBeBehind(b.index.get(cow), 2));
        b.sync.refresh(cow).get(5, TimeUnit.SECONDS);

        assertEquals(2L, b.index.get(cow).generation());
        assertFalse(b.sync.mayBeBehind(b.index.get(cow), 2));
    }
}
