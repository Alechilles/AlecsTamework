package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.hypixel.hytale.server.core.universe.StorageManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionStoreTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path root;

    private CompanionStore store() {
        StorageManager storage = new StorageManager(() -> false);
        return new CompanionStore(root, new HytaleCompanionFileIo(() -> storage), () -> 1234L);
    }

    private static CompanionRecord record(UUID owner, long revision) {
        return record(owner, revision, CompanionLocation.live("default", 1, 64, 1));
    }

    private static CompanionRecord record(UUID owner, long revision, CompanionLocation location) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", location)
                .ownerUuid(owner).revision(revision).build();
    }

    /** The profile ids in one section of an owner file, read back from disk. */
    private List<UUID> section(UUID owner, String name) throws IOException {
        BsonDocument doc = new HytaleCompanionFileIo(() -> new StorageManager(() -> false))
                .readNow(root.resolve("owners").resolve(owner + ".json"));
        return doc.getArray(name).stream()
                .map(entry -> UUID.fromString(entry.asDocument().getString("ProfileId").getValue()))
                .toList();
    }

    @Test
    void recordsWrittenForAnOwnerLoadBackWithTheirScopes() throws Exception {
        CompanionStore store = store();
        CompanionRecord bound = record(ALICE, 0);
        CompanionRecord portable = record(ALICE, 3, CompanionLocation.stored(StoredReason.ROSTER));

        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(bound, portable), List.of()).join();
        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(2, loaded.records().size());
        assertTrue(loaded.records().contains(bound));
        assertTrue(loaded.records().contains(portable));
        assertEquals(RecordScope.WORLD_BOUND, loaded.records().get(loaded.records().indexOf(bound)).scope());
        assertEquals(RecordScope.PORTABLE, loaded.records().get(loaded.records().indexOf(portable)).scope());
        assertEquals(1L, loaded.versions().get(ALICE.toString()));
    }

    @Test
    void storedAndOwnedItemRecordsGoToThePortableSectionAndEverythingElseStaysWorldBound() throws Exception {
        CompanionRecord stored = record(ALICE, 0, CompanionLocation.stored(StoredReason.ROSTER));
        CompanionRecord ownedItem = record(ALICE, 0, CompanionLocation.item());
        List<CompanionRecord> worldBound = List.of(
                record(ALICE, 0),
                record(ALICE, 0, CompanionLocation.coop("default", 1, 2, 3, 0)),
                record(ALICE, 0, CompanionLocation.dead("fall")),
                record(ALICE, 0, CompanionLocation.lost("gone")),
                record(ALICE, 0, CompanionLocation.released("freed")),
                record(null, 0, CompanionLocation.item()));
        List<CompanionRecord> all = new java.util.ArrayList<>(List.of(stored, ownedItem));
        all.addAll(worldBound);

        store().writeOwner(CompanionStore.ownerKey(ALICE), 1, all, List.of()).join();

        assertEquals(List.of(stored.profileId(), ownedItem.profileId()), section(ALICE, "Portable"));
        assertEquals(worldBound.stream().map(CompanionRecord::profileId).toList(), section(ALICE, "WorldBound"));
        assertEquals(all.size(), store().loadAll().records().size());
    }

    @Test
    void aRecordThatMovesFromStoredToLiveMovesSectionsOnTheNextWrite() throws Exception {
        CompanionStore store = store();
        CompanionRecord stored = record(ALICE, 0, CompanionLocation.stored(StoredReason.ROSTER));
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(stored), List.of()).join();
        assertEquals(List.of(stored.profileId()), section(ALICE, "Portable"));

        CompanionRecord live = stored.toBuilder().location(CompanionLocation.live("default", 1, 64, 1)).revision(1).build();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 2, List.of(live), List.of()).join();

        assertEquals(List.of(), section(ALICE, "Portable"));
        assertEquals(List.of(live.profileId()), section(ALICE, "WorldBound"));
        assertEquals(List.of(live), store().loadAll().records());
    }

    @Test
    void aCorruptOwnerFileLoadsFromItsBackup() throws Exception {
        CompanionStore store = store();
        CompanionRecord first = record(ALICE, 0);
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(first), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 2, List.of(first, record(ALICE, 0)), List.of()).join();
        Files.writeString(root.resolve("owners").resolve(ALICE + ".json"), "{ broken");

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(first), loaded.records());
        assertTrue(loaded.quarantinedFiles().isEmpty());
    }

    @Test
    void anOwnerFileWithNoReadableCopyIsMovedAsideAndOtherOwnersStillLoad() throws Exception {
        CompanionStore store = store();
        CompanionRecord bobs = record(BOB, 0);
        store.writeOwner(CompanionStore.ownerKey(BOB), 1, List.of(bobs), List.of()).join();
        Path alice = root.resolve("owners").resolve(ALICE + ".json");
        Files.createDirectories(alice.getParent());
        Files.writeString(alice, "{ broken");

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(bobs), loaded.records());
        assertEquals(1, loaded.quarantinedFiles().size());
        assertTrue(Files.notExists(alice));
        assertTrue(Files.exists(loaded.quarantinedFiles().get(0)));
    }

    @Test
    void anOwnerFileThatCannotBeReadFailsTheLoadAndStaysInPlace() throws Exception {
        // A directory named like an owner file: it exists, but reading its bytes fails at the I/O level.
        Path alice = root.resolve("owners").resolve(ALICE + ".json");
        Files.createDirectories(alice);

        assertThrows(CompanionFileAccessException.class, () -> store().loadAll());

        assertTrue(Files.isDirectory(alice), "a file that could not be read must not be quarantined");
        try (var siblings = Files.list(alice.getParent())) {
            assertEquals(1, siblings.count());
        }
    }

    @Test
    void anUnreadableRecordIsReportedAndKeptOnTheNextWrite() throws Exception {
        CompanionStore store = store();
        CompanionRecord good = record(ALICE, 0);
        UUID futureId = UUID.randomUUID();
        BsonDocument future = new BsonDocument("ProfileId", new BsonString(futureId.toString()))
                .append("Role", new BsonString("Sheep"))
                .append("Location", new BsonDocument("Kind", new BsonString("TELEPORTING")));
        BsonDocument file = new BsonDocument("Format", new org.bson.BsonInt32(1))
                .append("Owner", new BsonString(ALICE.toString()))
                .append("Version", new org.bson.BsonInt64(4))
                .append("WorldBound", new org.bson.BsonArray(List.of(CompanionRecordBson.encode(good), future)))
                .append("Portable", new org.bson.BsonArray());
        new HytaleCompanionFileIo(() -> new StorageManager(() -> false)).write(root.resolve("owners").resolve(ALICE + ".json"), file).join();

        CompanionStore.LoadResult first = store().loadAll();
        store.writeOwner(ALICE.toString(), 5, first.records(), first.unreadableRecords().get(ALICE.toString())).join();
        CompanionStore.LoadResult second = store().loadAll();

        assertEquals(List.of(good), second.records());
        assertTrue(second.unreadableIds().contains(futureId));
        assertEquals(future, second.unreadableRecords().get(ALICE.toString()).get(0));
    }

    @Test
    void aProfileInTwoOwnerFilesKeepsTheHigherRevision() throws Exception {
        CompanionStore store = store();
        CompanionRecord old = record(ALICE, 4, CompanionLocation.stored(StoredReason.ROSTER));
        CompanionRecord moved = old.toBuilder().ownerUuid(BOB).revision(5).build();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(old), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(BOB), 1, List.of(moved), List.of()).join();

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(moved), loaded.records());
    }

    @Test
    void manyOwnerFilesWithABrokenFileAndABrokenRecordLoadInFileOrder() throws Exception {
        CompanionStore store = store();
        HytaleCompanionFileIo io = new HytaleCompanionFileIo(() -> new StorageManager(() -> false));
        Path owners = root.resolve("owners");
        List<CompanionRecord> expected = new java.util.ArrayList<>();
        CompanionRecord shared = null;
        UUID firstOwner = null;
        UUID earlierTiedOwner = null;
        UUID brokenOwner = null;
        UUID brokenRecordOwner = null;
        UUID brokenRecordId = UUID.randomUUID();
        for (int i = 1; i <= 12; i++) {
            UUID owner = UUID.fromString(String.format("00000000-0000-0000-0000-0000000001%02d", i));
            if (i == 4) {
                brokenOwner = owner;
                Files.createDirectories(owners);
                Files.writeString(owners.resolve(owner + ".json"), "{ broken");
                continue;
            }
            List<CompanionRecord> records = new java.util.ArrayList<>(List.of(record(owner, i), record(owner, i)));
            if (i == 1) {
                firstOwner = owner;
            }
            if (i == 2) {
                earlierTiedOwner = owner;
                shared = record(owner, 7, CompanionLocation.stored(StoredReason.ROSTER));
                records.add(shared);
            }
            if (i == 9) {
                // Same profile and revision as in file 2: the copy in the earlier file is kept.
                records.add(shared.toBuilder().ownerUuid(owner).build());
            }
            if (i == 6) {
                brokenRecordOwner = owner;
                org.bson.BsonArray entries = new org.bson.BsonArray();
                records.forEach(r -> entries.add(CompanionRecordBson.encode(r)));
                entries.add(1, new BsonDocument("ProfileId", new BsonString(brokenRecordId.toString()))
                        .append("Role", new BsonString("Sheep"))
                        .append("Location", new BsonDocument("Kind", new BsonString("TELEPORTING"))));
                io.write(owners.resolve(owner + ".json"), new BsonDocument("Format", new org.bson.BsonInt32(1))
                        .append("Owner", new BsonString(owner.toString()))
                        .append("Version", new org.bson.BsonInt64(3))
                        .append("WorldBound", entries)).join();
            } else {
                store.writeOwner(CompanionStore.ownerKey(owner), i, records, List.of()).join();
            }
            expected.addAll(i == 9 ? records.subList(0, 2) : records);
        }

        // The first file and the earlier tied file finish reading last, so a loader that merged
        // files as they complete would put them last and keep the later tied copy.
        java.util.Set<Path> slow = java.util.Set.of(
                owners.resolve(firstOwner + ".json"), owners.resolve(earlierTiedOwner + ".json"));
        CompanionStore.LoadResult loaded = new CompanionStore(root, new SlowReads(io, slow, 10), () -> 1234L).loadAll();

        assertEquals(expected, loaded.records());
        assertEquals(java.util.Set.of(brokenRecordId), loaded.unreadableIds());
        assertEquals(java.util.Set.of(brokenRecordOwner.toString()), loaded.unreadableRecords().keySet());
        assertEquals(1, loaded.unreadableRecords().get(brokenRecordOwner.toString()).size());
        assertEquals(List.of(owners.resolve(brokenOwner + ".json.unreadable-1234")), loaded.quarantinedFiles());
        assertEquals(11, loaded.versions().size());
        assertEquals(3L, loaded.versions().get(brokenRecordOwner.toString()));
    }

    /**
     * Holds back the read of each {@code slow} file until {@code others} other files were read.
     * The wait is capped so a host with too few load threads to read the others meanwhile still
     * finishes.
     */
    private record SlowReads(CompanionFileIo io, java.util.Set<Path> slow,
                             java.util.concurrent.CountDownLatch othersRead) implements CompanionFileIo {
        SlowReads(CompanionFileIo io, java.util.Set<Path> slow, int others) {
            this(io, slow, new java.util.concurrent.CountDownLatch(others));
        }

        @Override
        public BsonDocument readNow(Path file) throws IOException {
            if (!slow.contains(file)) {
                try {
                    return io.readNow(file);
                } finally {
                    othersRead.countDown();
                }
            }
            BsonDocument document = io.readNow(file);
            try {
                othersRead.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return document;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> write(Path file, BsonDocument document) {
            return io.write(file, document);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> delete(Path file) {
            return io.delete(file);
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            return io.list(directory);
        }

        @Override
        public void moveAside(Path file, String suffix) throws IOException {
            io.moveAside(file, suffix);
        }
    }

    @Test
    void snapshotsRoundTripAndDelete() throws Exception {
        CompanionStore store = store();
        UUID id = UUID.randomUUID();
        SnapshotEnvelope snapshot = new SnapshotEnvelope(id, 1, 9, new BsonDocument("Components", new BsonDocument("NPCEntity", new BsonString("x"))));

        store.writeSnapshot(snapshot).join();
        assertEquals(snapshot, store.readSnapshotNow(id));

        store.deleteSnapshot(id).join();
        assertNull(store.readSnapshotNow(id));
    }

    @Test
    void anOwnerFileLeftOnlyAsABackupStillLoads() throws Exception {
        CompanionStore store = store();
        CompanionRecord first = record(ALICE, 0);
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(first), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 2, List.of(first, record(ALICE, 0)), List.of()).join();
        Files.delete(root.resolve("owners").resolve(ALICE + ".json"));

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(first), loaded.records());
        assertTrue(loaded.quarantinedFiles().isEmpty());
    }

    @Test
    void anOwnerFileFromANewerFormatIsMovedAsideAndOtherOwnersStillLoad() throws Exception {
        CompanionStore store = store();
        CompanionRecord bobs = record(BOB, 0);
        store.writeOwner(CompanionStore.ownerKey(BOB), 1, List.of(bobs), List.of()).join();
        Path alice = root.resolve("owners").resolve(ALICE + ".json");
        BsonDocument newer = new BsonDocument("Format", new org.bson.BsonInt32(CompanionStore.FORMAT + 1))
                .append("Owner", new BsonString(ALICE.toString()))
                .append("WorldBound", new org.bson.BsonArray(List.of(CompanionRecordBson.encode(record(ALICE, 0)))));
        new HytaleCompanionFileIo(() -> new StorageManager(() -> false)).write(alice, newer).join();

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(bobs), loaded.records());
        assertEquals(1, loaded.quarantinedFiles().size());
        assertTrue(Files.notExists(alice));
        assertTrue(Files.exists(loaded.quarantinedFiles().get(0)));
    }

    @Test
    void aSnapshotThatIsNotAnEnvelopeOrBelongsToAnotherProfileFailsToRead() throws Exception {
        CompanionStore store = store();
        Path snapshots = root.resolve("snapshots");
        UUID notAnEnvelope = UUID.randomUUID();
        Files.createDirectories(snapshots);
        Files.writeString(snapshots.resolve(notAnEnvelope + ".json"), "{ \"Hello\": 1 }");
        UUID someoneElse = UUID.randomUUID();
        UUID mismatched = UUID.randomUUID();
        store.writeSnapshot(new SnapshotEnvelope(someoneElse, 1, 1, new BsonDocument())).join();
        Files.move(snapshots.resolve(someoneElse + ".json"), snapshots.resolve(mismatched + ".json"));

        assertThrows(IOException.class, () -> store.readSnapshotNow(notAnEnvelope));
        assertThrows(IOException.class, () -> store.readSnapshotNow(mismatched));
    }
}
