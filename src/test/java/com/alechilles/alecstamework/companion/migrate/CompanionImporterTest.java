package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.CompanionStore;
import com.alechilles.alecstamework.companion.store.DiskCompanionFileIo;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionImporterTest {
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path temp;
    private Path data;
    private Path reports;
    private Path root;
    private final DiskCompanionFileIo io = new DiskCompanionFileIo();

    @BeforeEach
    void folders() throws Exception {
        data = temp.resolve("Data");
        reports = temp.resolve("reports");
        root = temp.resolve("universe").resolve("Tamework").resolve("Companions");
        Files.createDirectories(root.getParent());
        ImportFixtures.state(data);
        ImportFixtures.bonded(data);
    }

    private CompanionImporter.Outcome run(Path storeRoot) {
        return CompanionImporter.run(storeRoot, List.of(data), io, () -> NOW, "test", reports);
    }

    @Test
    void theWrittenStoreHoldsTheMappedRecordsSnapshotsAliasesAndAReceipt() throws Exception {
        ImportResult mapped = LegacyMapper.map(LegacyReader.read(List.of(data), temp.resolve("scratch")), NOW);
        assertFalse(mapped.records().isEmpty());
        Map<String, String> before = ImportFixtures.contents(data);

        CompanionImporter.Outcome outcome = run(root);

        assertTrue(outcome.imported(), String.valueOf(outcome.failure()));
        CompanionStore store = new CompanionStore(root, io, () -> NOW);
        assertEquals(new HashSet<>(mapped.records()), new HashSet<>(store.loadAll().records()));
        for (CompanionRecord record : mapped.records()) {
            assertEquals(mapped.snapshots().get(record.profileId()), store.readSnapshotNow(record.profileId()));
        }
        assertEquals(mapped.aliases().size(), LegacyAliases.load(io, root).size());
        BsonDocument receipt = io.readNow(CompanionStorage.metaFile(root)).getDocument("Import");
        assertEquals("LEGACY_3X_4X", receipt.getString("SourceKind").getValue());
        assertEquals(NOW, receipt.getNumber("AtMs").longValue());
        BsonDocument state = receipt.getArray("Sources").get(0).asDocument();
        assertEquals(data.resolve(LegacySource.STATE_FILE).toString(), state.getString("Path").getValue());
        assertEquals(Files.size(data.resolve(LegacySource.STATE_FILE)), state.getNumber("SizeBytes").longValue());
        assertEquals("V2", state.getString("Schema").getValue());
        assertEquals(2, receipt.getArray("Sources").size());
        assertEquals(mapped.records().size(), receipt.getDocument("Counts").getInt32("Records").getValue());
        assertFalse(Files.exists(CompanionStorage.importingDir(root)), "the importing folder is gone");
        assertEquals(before, ImportFixtures.contents(data), "the old files are byte for byte the same");
        assertNotNull(outcome.reportFile());
        assertEquals(outcome.reportName(), outcome.reportFile().getFileName().toString());
        assertTrue(Files.readString(outcome.reportFile()).contains(data.resolve(LegacySource.STATE_FILE).toString()));
    }

    /** Review Focus 2: a killed import leaves a half-written importing folder and no store. */
    @Test
    void aLeftoverImportingFolderIsDiscardedAndTheImportEndsWithTheSameStore() throws Exception {
        Path clean = temp.resolve("clean").resolve("Companions");
        Files.createDirectories(clean.getParent());
        assertTrue(run(clean).imported());

        Path importing = CompanionStorage.importingDir(root);
        Files.createDirectories(importing.resolve("owners"));
        Files.writeString(importing.resolve("owners").resolve("11111111-1111-1111-1111-111111111111.json"), "{half");
        Files.writeString(importing.resolve("owners").resolve("99999999-9999-9999-9999-999999999999.json"),
                "{\"Format\":1,\"Owner\":\"99999999-9999-9999-9999-999999999999\",\"WorldBound\":[]}");
        Files.createDirectories(importing.resolve("snapshots"));
        Files.writeString(importing.resolve("snapshots").resolve("stale.json"), "{}");
        Files.createDirectories(importing.resolve("legacy-scratch"));
        Files.writeString(importing.resolve("legacy-scratch").resolve(LegacySource.STATE_FILE), "copy");

        CompanionImporter.Outcome outcome = run(root);

        assertTrue(outcome.imported(), String.valueOf(outcome.failure()));
        assertEquals(ImportFixtures.contents(clean), ImportFixtures.contents(root));
        assertFalse(Files.exists(importing));
    }

    @Test
    void aSourceThatCannotBeReadLeavesNoStoreAndWritesAFailureReport() throws Exception {
        Files.write(data.resolve(LegacySource.BONDED_FILE),
                "not a database, only text that is long enough to be read".repeat(40).getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = ImportFixtures.contents(data);

        CompanionImporter.Outcome outcome = run(root);

        assertFalse(outcome.imported());
        assertTrue(outcome.failure().contains(LegacySource.BONDED_FILE), outcome.failure());
        assertFalse(Files.exists(root), "nothing is left in Companions/");
        assertFalse(Files.exists(CompanionStorage.importingDir(root)));
        assertEquals(before, ImportFixtures.contents(data));
        assertNotNull(outcome.reportFile());
        assertTrue(Files.readString(outcome.reportFile()).contains(outcome.failure()));
    }

    @Test
    void aSecondCopyOfASourceFileAndA2xFileAreReportedAsIgnored() throws Exception {
        Path older = temp.resolve("LegacyData");
        ImportFixtures.state(older);
        Files.writeString(older.resolve("tamework.sqlite"), "2.x");

        CompanionImporter.Outcome outcome =
                CompanionImporter.run(root, List.of(data, older), io, () -> NOW, "test", reports);

        assertTrue(outcome.imported(), String.valueOf(outcome.failure()));
        String report = Files.readString(outcome.reportFile());
        assertTrue(report.contains("Also found and ignored: " + older.resolve(LegacySource.STATE_FILE)), report);
        assertTrue(report.contains("An older 2.x file was also found and ignored: " + older.resolve("tamework.sqlite")),
                report);
        assertEquals(data.resolve(LegacySource.STATE_FILE).toString(), io.readNow(CompanionStorage.metaFile(root))
                .getDocument("Import").getArray("Sources").get(0).asDocument().getString("Path").getValue());
    }

    @Test
    void aStoreFolderWithUnknownFilesIsNotReplaced() throws Exception {
        Files.createDirectories(root.resolve("owners"));
        Path existing = root.resolve("owners").resolve("11111111-1111-1111-1111-111111111111.json");
        Files.writeString(existing, "{\"Format\":1}");

        CompanionImporter.Outcome outcome = run(root);

        assertFalse(outcome.imported());
        assertEquals("{\"Format\":1}", Files.readString(existing));
        assertFalse(Files.exists(CompanionStorage.metaFile(root)));
    }

    /** Where a folder rename is not atomic the files move one by one; a kill in between must not block the next start. */
    @Test
    void aFileByFileMoveGivesTheSameStoreAndAHalfMovedFolderIsDiscarded() throws Exception {
        Path clean = temp.resolve("clean").resolve("Companions");
        Files.createDirectories(clean.getParent());
        assertTrue(run(clean).imported());
        Map<String, String> expected = ImportFixtures.contents(clean);

        Path moved = temp.resolve("moved").resolve("Companions");
        Files.createDirectories(moved.getParent());
        CompanionImporter.promote(clean, moved, false);
        assertEquals(expected, ImportFixtures.contents(moved));
        assertFalse(Files.exists(clean));

        // What a kill in the middle of that move leaves: some files, the marker, no meta.json.
        Files.createDirectories(root.resolve("owners"));
        Files.writeString(root.resolve("owners").resolve("half.json"), "{}");
        Files.writeString(root.resolve(CompanionImporter.UNFINISHED_MARKER), "");
        assertTrue(run(root).imported());
        assertEquals(expected, ImportFixtures.contents(root));
    }

    /** A large world has thousands of snapshots; the check decodes a bounded sample that misses no kind. */
    @Test
    void theSnapshotSampleIsBoundedAndCoversEveryFormatAndLocationKind() {
        List<CompanionRecord> records = new ArrayList<>();
        Map<UUID, SnapshotEnvelope> snapshots = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) {
            UUID id = new UUID(7L, i);
            // The last two are the only DEAD record and the only format 1 snapshot.
            CompanionLocation location = i == 598 ? CompanionLocation.dead(null)
                    : i == 599 ? CompanionLocation.live("world", 0, 0, 0) : CompanionLocation.lost(null);
            records.add(CompanionRecord.builder(id, "Tamed_Wolf", location).build());
            snapshots.put(id, i == 599
                    ? new SnapshotEnvelope(id, 1, 0L, new BsonDocument())
                    : SnapshotEnvelope.importedState(id, 0L, "{}"));
        }

        List<UUID> sampled = CompanionImporter.snapshotSample(records, snapshots).stream()
                .map(SnapshotEnvelope::profileId).toList();

        assertTrue(sampled.size() <= CompanionImporter.SNAPSHOT_SAMPLE_MAX && sampled.size() > 100, "" + sampled.size());
        assertTrue(sampled.containsAll(List.of(new UUID(7L, 0), new UUID(7L, 598), new UUID(7L, 599))));
    }
}
