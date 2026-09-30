package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionWriterTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Path ROOT = Path.of("root");

    private final MemoryCompanionFileIo io = new MemoryCompanionFileIo();
    private final CompanionStore store = new CompanionStore(ROOT, io, () -> 1L);
    private final AtomicReference<CompanionWriter> writerRef = new AtomicReference<>();
    private final CompanionIndex index = new CompanionIndex(() -> 1L, (b, a) -> writerRef.get().onRecordChanged(b, a));
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final CompanionWriter writer = new CompanionWriter(index, store,
            new CompanionStore.LoadResult(List.of(), Map.of(), Set.of(), Map.of(), List.of()),
            executor, 60_000L, 2_000L, System::currentTimeMillis);

    {
        writerRef.set(writer);
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    private static Path ownerFile(UUID owner) {
        return ROOT.resolve("owners").resolve(owner + ".json");
    }

    private CompanionRecord insertLive(UUID owner) {
        CompanionRecord r = CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("default", 1, 64, 1))
                .ownerUuid(owner).build();
        index.insert(r);
        return r;
    }

    @Test
    void manyChangesToOneOwnerBecomeOneWriteWithTheLatestState() throws Exception {
        CompanionRecord r = insertLive(ALICE);
        index.update(r.profileId(), 0, b -> b.displayName("One"));
        index.update(r.profileId(), 1, b -> b.displayName("Two"));

        writer.flushNow(ALICE).get(2, TimeUnit.SECONDS);

        assertEquals(1, io.writesTo(ownerFile(ALICE)));
        BsonDocument file = io.files.get(ownerFile(ALICE));
        assertEquals("Two", file.getArray("WorldBound").get(0).asDocument().getString("Name").getValue());
    }

    @Test
    void flushNowIsNotCompletedByAFlushThatStartedBeforeTheChange() throws Exception {
        CompanionRecord r = insertLive(ALICE);
        CountDownLatch gate = new CountDownLatch(1);
        io.blockWrites = gate;
        CompletableFuture<Void> first = writer.flushNow(ALICE);   // this flush blocks inside write
        Thread.sleep(100);
        index.update(r.profileId(), 0, b -> b.displayName("After"));
        CompletableFuture<Void> second = writer.flushNow(ALICE);

        io.blockWrites = null;
        gate.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);

        BsonDocument file = io.files.get(ownerFile(ALICE));
        assertEquals("After", file.getArray("WorldBound").get(0).asDocument().getString("Name").getValue());
        assertEquals(2, io.writesTo(ownerFile(ALICE)));
    }

    @Test
    void aFailedWriteFailsTheWaiterAndARetryLaterWritesTheData() throws Exception {
        insertLive(ALICE);
        io.failNextWrites.set(1);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> writer.flushNow(ALICE).get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("injected"));
        assertTrue(writer.status().lastFailure().contains("injected"));

        writer.flushNow(ALICE).get(2, TimeUnit.SECONDS);
        assertEquals(1, io.writesTo(ownerFile(ALICE)));
    }

    @Test
    void anOwnerTransferWritesTheNewOwnerBeforeTheOldOne() throws Exception {
        CompanionRecord r = insertLive(ALICE);
        writer.flushNow(ALICE).get(2, TimeUnit.SECONDS);
        io.writeOrder.clear();

        index.update(r.profileId(), 0, b -> b.ownerUuid(BOB));
        writer.flushNow(BOB).get(2, TimeUnit.SECONDS);

        assertEquals(List.of(ownerFile(BOB)), io.writeOrder.subList(0, 1));
        assertFalse(io.files.containsKey(ownerFile(ALICE)), "an owner with nothing left has no file");
    }

    @Test
    void snapshotsAreWrittenBeforeTheOwnerFileInTheSameFlush() throws Exception {
        CompanionRecord r = insertLive(ALICE);
        writer.queueSnapshot(new SnapshotEnvelope(r.profileId(), 1, 1, new BsonDocument()));

        writer.flushNow(ALICE).get(2, TimeUnit.SECONDS);

        assertEquals(ROOT.resolve("snapshots").resolve(r.profileId() + ".json"), io.writeOrder.get(0));
        assertEquals(ownerFile(ALICE), io.writeOrder.get(1));
    }

    @Test
    void shutdownWritesEverythingStillPending() {
        insertLive(ALICE);

        assertTrue(writer.shutdown(2_000L));

        assertTrue(io.files.containsKey(ownerFile(ALICE)));
    }
}
