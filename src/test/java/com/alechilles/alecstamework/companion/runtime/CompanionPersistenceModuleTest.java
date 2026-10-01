package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.MemoryCompanionFileIo;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPersistenceModuleTest {
    private static final Path ROOT = Path.of("universe", "Tamework", "Companions");
    private static final Path DATA = Path.of("universe", "Tamework", "Data");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static CompanionTransitions.BodyFacts body(UUID npc) {
        return new CompanionTransitions.BodyFacts(npc, OWNER, "Alec", "Tamed_Sheep", null, "default", 1, 2, 3,
                List.of(), CompanionSummary.EMPTY);
    }

    @Test
    void recordsSurviveAShutdownAndReopen() throws Exception {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        CompanionPersistenceModule first = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "test");
        UUID profile = UUID.randomUUID();
        first.index().insert(CompanionTransitions.newLive(profile, 0, body(UUID.randomUUID())));
        assertTrue(first.shutdown(System.currentTimeMillis() + 5_000L));

        CompanionPersistenceModule second = CompanionPersistenceModule.open(ROOT, List.of(DATA), io::exists, io,
                System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.READY, second.state());
        assertNotNull(second.queries().get(profile));
        assertEquals(1, second.queries().owned(OWNER).size());
        second.shutdown(System.currentTimeMillis() + 5_000L);
    }

    @Test
    void oldSavesLeaveTheStoreUntouched() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA),
                p -> p.equals(DATA.resolve("tamework-state.sqlite")), io, System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.MIGRATION_REQUIRED, module.state());
        assertTrue(io.writtenPaths().isEmpty(), "nothing may be written, not even meta.json");
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void anUnreadableStoreFailsWithoutTouchingItsFiles() throws Exception {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        Path ownerFile = ROOT.resolve("owners").resolve(OWNER + ".json");
        BsonDocument saved = new BsonDocument("Format", new BsonInt32(1)).append("Owner", new BsonString(OWNER.toString()));
        io.write(ownerFile, saved).join();
        List<Path> seeded = io.writtenPaths();
        io.failReads(true);

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.FAILED, module.state());
        assertNotNull(module.failure());
        assertEquals(seeded, io.writtenPaths(), "nothing may be written or deleted, not even meta.json");
        io.failReads(false);
        assertEquals(saved, io.readNow(ownerFile), "the unread owner file must stay as it was");
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void aSnapshotQueuedButNotYetWrittenIsReadFromTheWriter() throws Exception {
        MemoryCompanionFileIo files = new MemoryCompanionFileIo();
        CountDownLatch gate = new CountDownLatch(1);
        GatedIo io = new GatedIo(files);
        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "test");
        io.gate = gate;
        UUID profile = UUID.randomUUID();
        SnapshotEnvelope queued = new SnapshotEnvelope(profile, CompanionSnapshots.FORMAT, 3L,
                new BsonDocument("Entity", new BsonDocument()));
        try {
            module.writer().queueSnapshot(queued);

            assertEquals(queued, module.readSnapshot(profile).join(), "the file is not written yet");
        } finally {
            gate.countDown();
            module.shutdown(System.currentTimeMillis() + 5_000L);
        }
    }

    /** Holds every write at {@link #gate} once it is set, so nothing reaches the files. */
    private static final class GatedIo implements CompanionFileIo {
        private final MemoryCompanionFileIo files;
        volatile CountDownLatch gate;

        GatedIo(MemoryCompanionFileIo files) {
            this.files = files;
        }

        @Override
        public CompletableFuture<Void> write(Path file, BsonDocument document) {
            CountDownLatch g = gate;
            if (g != null) {
                try { g.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return files.write(file, document);
        }

        @Override
        public BsonDocument readNow(Path file) throws IOException {
            return files.readNow(file);
        }

        @Override
        public CompletableFuture<Void> delete(Path file) {
            return files.delete(file);
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            return files.list(directory);
        }

        @Override
        public void moveAside(Path file, String suffix) {
            files.moveAside(file, suffix);
        }
    }

    @Test
    void aFreshStoreWritesItsMetaFile() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA), p -> false, io,
                System::currentTimeMillis, "5.0.0-test");

        assertTrue(io.exists(CompanionStorage.metaFile(ROOT)));
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }
}
