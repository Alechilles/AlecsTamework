package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.RecordScope;
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

    private static CompanionRecord record(UUID owner, long revision, RecordScope scope) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("default", 1, 64, 1))
                .ownerUuid(owner).revision(revision).scope(scope).build();
    }

    @Test
    void recordsWrittenForAnOwnerLoadBackWithTheirScopes() throws Exception {
        CompanionStore store = store();
        CompanionRecord bound = record(ALICE, 0, RecordScope.WORLD_BOUND);
        CompanionRecord portable = record(ALICE, 3, RecordScope.PORTABLE);

        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(bound, portable), List.of()).join();
        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(2, loaded.records().size());
        assertTrue(loaded.records().contains(bound));
        assertTrue(loaded.records().contains(portable));
        assertEquals(1L, loaded.versions().get(ALICE.toString()));
    }

    @Test
    void aCorruptOwnerFileLoadsFromItsBackup() throws Exception {
        CompanionStore store = store();
        CompanionRecord first = record(ALICE, 0, RecordScope.WORLD_BOUND);
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(first), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 2, List.of(first, record(ALICE, 0, RecordScope.WORLD_BOUND)), List.of()).join();
        Files.writeString(root.resolve("owners").resolve(ALICE + ".json"), "{ broken");

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(first), loaded.records());
        assertTrue(loaded.quarantinedFiles().isEmpty());
    }

    @Test
    void anOwnerFileWithNoReadableCopyIsMovedAsideAndOtherOwnersStillLoad() throws Exception {
        CompanionStore store = store();
        CompanionRecord bobs = record(BOB, 0, RecordScope.WORLD_BOUND);
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
        CompanionRecord good = record(ALICE, 0, RecordScope.WORLD_BOUND);
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
        CompanionRecord old = record(ALICE, 4, RecordScope.PORTABLE);
        CompanionRecord moved = old.toBuilder().ownerUuid(BOB).revision(5).build();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(old), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(BOB), 1, List.of(moved), List.of()).join();

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(moved), loaded.records());
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
        CompanionRecord first = record(ALICE, 0, RecordScope.WORLD_BOUND);
        store.writeOwner(CompanionStore.ownerKey(ALICE), 1, List.of(first), List.of()).join();
        store.writeOwner(CompanionStore.ownerKey(ALICE), 2, List.of(first, record(ALICE, 0, RecordScope.WORLD_BOUND)), List.of()).join();
        Files.delete(root.resolve("owners").resolve(ALICE + ".json"));

        CompanionStore.LoadResult loaded = store().loadAll();

        assertEquals(List.of(first), loaded.records());
        assertTrue(loaded.quarantinedFiles().isEmpty());
    }

    @Test
    void anOwnerFileFromANewerFormatIsMovedAsideAndOtherOwnersStillLoad() throws Exception {
        CompanionStore store = store();
        CompanionRecord bobs = record(BOB, 0, RecordScope.WORLD_BOUND);
        store.writeOwner(CompanionStore.ownerKey(BOB), 1, List.of(bobs), List.of()).join();
        Path alice = root.resolve("owners").resolve(ALICE + ".json");
        BsonDocument newer = new BsonDocument("Format", new org.bson.BsonInt32(CompanionStore.FORMAT + 1))
                .append("Owner", new BsonString(ALICE.toString()))
                .append("WorldBound", new org.bson.BsonArray(List.of(CompanionRecordBson.encode(record(ALICE, 0, RecordScope.WORLD_BOUND)))));
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
