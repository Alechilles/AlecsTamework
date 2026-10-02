package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.CompanionStore;
import com.alechilles.alecstamework.companion.store.DiskCompanionFileIo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        // A kill after the receipt was written but before the rename: still not a store.
        Files.writeString(CompanionStorage.metaFile(importing), "{\"Format\":1,\"CreatedBy\":\"killed\"}");
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

        // Every failed start writes the same file, so reports do not pile up.
        CompanionImporter.run(root, List.of(data), io, () -> NOW + 60_000L, "test", reports);
        assertEquals(List.of(outcome.reportName()), List.copyOf(ImportFixtures.contents(reports).keySet()));
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
}
