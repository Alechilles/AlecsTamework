package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIncidentJournalTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void writesDeterministicJsonlWithoutRawScopeKeysAndRotatesBySize() throws Exception {
        PersistenceIncidentEvent event = event("safe-profile-hash");
        try (PersistenceIncidentJournal journal = new PersistenceIncidentJournal(
                tempDir, "boot-test", null, CLOCK, 128L, 5, 7, 8)) {
            journal.record(event);
            journal.record(event);
        }

        List<Path> files = files();
        assertEquals(2, files.size());
        String json = Files.readString(files.getFirst());
        assertTrue(JsonParser.parseString(json.strip()).isJsonObject());
        assertTrue(json.contains("safe-profile-hash"));
        assertFalse(json.contains("raw-profile-uuid"));
        assertFalse(json.contains(System.getProperty("user.name")));
    }

    @Test
    void retentionRemovesExpiredFilesAndKeepsBoundedNewestSet() throws Exception {
        Path expired = tempDir.resolve("incidents-old-2026-07-01.jsonl");
        Files.writeString(expired, "{}\n");
        Files.setLastModifiedTime(expired, FileTime.from(Instant.parse("2026-07-01T00:00:00Z")));
        for (int index = 0; index < 3; index++) {
            Path recent = tempDir.resolve("incidents-existing-" + index + ".jsonl");
            Files.writeString(recent, "{}\n");
            Files.setLastModifiedTime(recent, FileTime.from(
                    Instant.parse("2026-07-18T00:00:0" + index + "Z")));
        }
        try (PersistenceIncidentJournal journal = new PersistenceIncidentJournal(
                tempDir, "boot-test", null, CLOCK, 10_000L, 2, 7, 8)) {
            journal.record(event("hash"));
        }

        assertFalse(Files.exists(expired));
        assertTrue(files().size() <= 2);
    }

    @Test
    void unwritableTargetDropsDiagnosticsWithoutThrowingIntoCaller() throws Exception {
        Path notDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");
        try (PersistenceIncidentJournal journal = new PersistenceIncidentJournal(
                notDirectory, "boot-test", null, CLOCK, 10_000L, 5, 7, 1)) {
            for (int index = 0; index < 100; index++) journal.record(event("hash"));
            Thread.sleep(150L);
            assertTrue(journal.droppedRecords() > 0L);
        }
    }

    private PersistenceIncidentEvent event(String scopeHash) {
        return new PersistenceIncidentEvent(
                1, CLOCK.millis(), PersistenceIncidentEventKind.INCIDENT_OPENED,
                "boot-test", "incident-test", "trace-test", "operation-test",
                PersistenceDomain.OWNER_MUTATION, PersistenceOperationPhase.PUBLICATION,
                "publication_failed", PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE,
                PersistenceDisposition.SCOPED_QUARANTINE,
                List.of(new PersistenceIncidentEvent.SafeScope(
                        "PROFILE", scopeHash, "canonical_profile_catalog")),
                1L, 0L, "opened");
    }

    private List<Path> files() throws Exception {
        try (var stream = Files.list(tempDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
