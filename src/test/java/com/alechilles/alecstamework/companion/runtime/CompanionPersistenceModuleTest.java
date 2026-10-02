package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.migrate.ImportFixtures;
import com.alechilles.alecstamework.companion.migrate.ImportResult;
import com.alechilles.alecstamework.companion.migrate.LegacyMapper;
import com.alechilles.alecstamework.companion.migrate.LegacyReader;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.DiskCompanionFileIo;
import com.alechilles.alecstamework.companion.store.MemoryCompanionFileIo;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void oldSavesThatCannotBeConvertedLeaveTheStoreUntouched() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        CompanionPersistenceModule module = CompanionPersistenceModule.open(ROOT, List.of(DATA),
                p -> p.equals(DATA.resolve("tamework.sqlite")), io, System::currentTimeMillis, "test");

        assertEquals(CompanionPersistenceModule.State.MIGRATION_REQUIRED, module.state());
        assertEquals(CompanionStorage.LegacyKind.LEGACY_2X, module.legacyKind());
        assertTrue(io.writtenPaths().isEmpty(), "nothing may be written, not even meta.json");
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void aWorldWithOldDatabasesIsImportedOnceAndThenLoadsNormally(@TempDir Path temp) throws Exception {
        Path data = temp.resolve("Data");
        Path root = temp.resolve("universe").resolve("Tamework").resolve("Companions");
        Files.createDirectories(root.getParent());
        ImportFixtures.state(data);
        ImportFixtures.bonded(data);
        Map<String, String> oldFiles = ImportFixtures.contents(data);
        ImportResult mapped = LegacyMapper.map(LegacyReader.read(List.of(data), temp.resolve("scratch")), 5_000L);
        DiskCompanionFileIo io = new DiskCompanionFileIo();
        Path reports = temp.resolve("reports");

        CompanionPersistenceModule first = CompanionPersistenceModule.open(root, List.of(data), Files::exists, io,
                () -> 5_000L, "test", reports);
        try {
            assertEquals(CompanionPersistenceModule.State.READY, first.state(), String.valueOf(first.failure()));
            assertNull(first.legacyKind());
            for (CompanionRecord record : mapped.records()) {
                assertEquals(record, first.queries().get(record.profileId()));
            }
            assertEquals(mapped.aliases().size(), first.legacyAliases().size());
            assertTrue(first.legacyAliases().size() > 0);
            assertEquals(mapped.snapshots().get(ImportFixtures.BONDED_DEAD),
                    first.readSnapshot(ImportFixtures.BONDED_DEAD).join());
        } finally {
            assertTrue(first.shutdown(System.currentTimeMillis() + 5_000L));
        }
        Map<String, String> store = ImportFixtures.contents(root);
        assertEquals(1, ImportFixtures.contents(reports).size());

        // Review Focus 5: the old files are still there, but the store now exists.
        CompanionPersistenceModule second = CompanionPersistenceModule.open(root, List.of(data), Files::exists, io,
                () -> 9_000_000L, "test", reports);
        try {
            assertEquals(CompanionPersistenceModule.State.READY, second.state());
            for (CompanionRecord record : mapped.records()) {
                assertEquals(record, second.queries().get(record.profileId()));
            }
            assertEquals(mapped.aliases().size(), second.legacyAliases().size());
        } finally {
            assertTrue(second.shutdown(System.currentTimeMillis() + 5_000L));
        }
        assertEquals(store, ImportFixtures.contents(root), "a second start does not import or rewrite anything");
        assertEquals(1, ImportFixtures.contents(reports).size(), "no second import report");
        assertEquals(oldFiles, ImportFixtures.contents(data), "the old files are byte for byte the same");
    }

    @Test
    void aFailedImportWritesNothingAndRequiresMigrationWithTheReason(@TempDir Path temp) throws Exception {
        Path data = Files.createDirectories(temp.resolve("Data"));
        Path root = temp.resolve("universe").resolve("Tamework").resolve("Companions");
        Files.createDirectories(root.getParent());
        Files.write(data.resolve("tamework-state.sqlite"), "this is not a database, only text".repeat(60).getBytes());
        Map<String, String> oldFiles = ImportFixtures.contents(data);
        DiskCompanionFileIo io = new DiskCompanionFileIo();
        Path reports = temp.resolve("reports");

        CompanionPersistenceModule module = CompanionPersistenceModule.open(root, List.of(data), Files::exists, io,
                System::currentTimeMillis, "test", reports);

        assertEquals(CompanionPersistenceModule.State.MIGRATION_REQUIRED, module.state());
        assertEquals(CompanionStorage.LegacyKind.LEGACY_3X_4X, module.legacyKind());
        assertTrue(module.failure().contains("tamework-state.sqlite"), module.failure());
        assertEquals(Map.of(), ImportFixtures.contents(root.getParent()), "no store and no importing folder is left");
        assertEquals(oldFiles, ImportFixtures.contents(data));
        assertEquals(List.of(module.importReportName()), List.copyOf(ImportFixtures.contents(reports).keySet()));
        assertEquals(CompanionPersistenceModule.FreshStart.CREATED, CompanionPersistenceModule.startFresh(root,
                List.of(data), Files::exists, io, () -> 1L, "test"), "the operator can still start fresh");
        module.shutdown(System.currentTimeMillis() + 1_000L);
    }

    @Test
    void startingFreshWritesAnEmptyStoreWithAReceiptAndLeavesTheOldFilesAlone(@TempDir Path data) throws Exception {
        byte[] oldDatabase = {1, 2, 3, 4, 5};
        byte[] oldBundle = {9, 8, 7};
        Files.write(data.resolve("tamework.sqlite"), oldDatabase);
        Files.write(data.resolve("CommandLinkedNpcDeaths.dat"), oldBundle);
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        Predicate<Path> exists = p -> io.exists(p) || Files.exists(p);
        CompanionPersistenceModule blocked = CompanionPersistenceModule.open(ROOT, List.of(data), exists, io,
                System::currentTimeMillis, "test");
        assertEquals(CompanionPersistenceModule.State.MIGRATION_REQUIRED, blocked.state());

        assertEquals(CompanionPersistenceModule.FreshStart.CREATED,
                CompanionPersistenceModule.startFresh(ROOT, List.of(data), exists, io, () -> -1234L, "test"));

        Path meta = CompanionStorage.metaFile(ROOT);
        assertEquals(List.of(meta), io.writtenPaths(), "only meta.json is written");
        BsonDocument receipt = io.readNow(meta).getDocument("FreshStart");
        assertEquals(CompanionStorage.LegacyKind.LEGACY_2X.name(), receipt.getString("FoundData").getValue());
        assertEquals(-1234L, receipt.getInt64("AtMs").getValue());
        assertArrayEquals(oldDatabase, Files.readAllBytes(data.resolve("tamework.sqlite")));
        assertArrayEquals(oldBundle, Files.readAllBytes(data.resolve("CommandLinkedNpcDeaths.dat")));
        try (Stream<Path> files = Files.list(data)) {
            assertEquals(2L, files.count(), "nothing is added to or removed from the old data folder");
        }

        CompanionPersistenceModule reopened = CompanionPersistenceModule.open(ROOT, List.of(data), exists, io,
                System::currentTimeMillis, "test");
        try {
            assertEquals(CompanionPersistenceModule.State.READY, reopened.state());
            assertNull(reopened.legacyKind());
            assertTrue(reopened.queries().owned(OWNER).isEmpty());
            assertEquals(CompanionPersistenceModule.FreshStart.NOT_NEEDED,
                    CompanionPersistenceModule.startFresh(ROOT, List.of(data), exists, io, () -> 5L, "test"));
            assertEquals(-1234L, io.readNow(meta).getDocument("FreshStart").getInt64("AtMs").getValue(),
                    "an existing store keeps its receipt");
        } finally {
            reopened.shutdown(System.currentTimeMillis() + 5_000L);
        }
    }

    @Test
    void aWorldWithNoOldSavesHasNothingToStartFresh() {
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();

        assertEquals(CompanionPersistenceModule.FreshStart.NOT_NEEDED,
                CompanionPersistenceModule.startFresh(ROOT, List.of(DATA), p -> false, io, () -> 1L, "test"));

        assertTrue(io.writtenPaths().isEmpty());
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

    @Test
    void theFolderSizeIsMeasuredInTheBackgroundAndThenServedFromMemory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("owners"));
        Files.write(root.resolve("owners").resolve(OWNER + ".json"), new byte[40]);
        Files.write(root.resolve("meta.json"), new byte[2]);
        CompanionPersistenceModule module = CompanionPersistenceModule.open(root, List.of(DATA), p -> false,
                new MemoryCompanionFileIo(), System::currentTimeMillis, "test");
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (module.folderBytes() != 42L && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(42L, module.folderBytes());

            Files.write(root.resolve("owners").resolve("later.json"), new byte[8]);
            assertEquals(42L, module.folderBytes(), "a fresh measurement is reused, not read again");
        } finally {
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
