package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IndexDiagnosticsApiTest {
    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });
    private CompanionWriter.Status writer = new CompanionWriter.Status(0, 0, null, 0L);
    private final IndexDiagnosticsApi diagnostics = new IndexDiagnosticsApi(
            index, () -> writer, "universe/Tamework/Companions", () -> 4_096L, () -> 2);

    private void insert(CompanionLocation location) {
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Sheep", location).ownerUuid(UUID.randomUUID()).build());
    }

    @Test
    void aHealthyStoreReportsItsFolderItsRecordsAndWhatIsWaitingToBeWritten() {
        insert(CompanionLocation.live("default", 0, 0, 0));
        insert(CompanionLocation.live("default", 0, 0, 0));
        insert(CompanionLocation.item());
        insert(CompanionLocation.dead("fall"));
        writer = new CompanionWriter.Status(2, 3, null, 900L);

        PersistenceDiagnosticsView view = diagnostics.getPersistenceDiagnostics();

        assertEquals("universe/Tamework/Companions", view.databasePath());
        assertEquals(4_096L, view.totalBytes());
        assertEquals(0L, view.sqliteBytes() + view.walBytes() + view.shmBytes());
        assertEquals(5, view.queueMetrics().queueDepth());
        assertEquals("HEALTHY", view.health().status());
        assertNull(view.health().reason());
        assertEquals(2L, view.recordsByLocation().get("LIVE"));
        assertEquals(1L, view.recordsByLocation().get("ITEM"));
        assertEquals(1L, view.recordsByLocation().get("DEAD"));
        assertEquals(0L, view.recordsByLocation().get("COOP"));
        assertEquals(900L, view.lastFlushAtMs());
        assertNull(view.lastFailure());
        assertEquals(2, view.unreadableRecords());
    }

    @Test
    void aFailingWriterReportsDegradedHealthWithItsReason() {
        writer = new CompanionWriter.Status(1, 0, "java.io.IOException: disk full", 900L);

        PersistenceDiagnosticsView view = diagnostics.getPersistenceDiagnostics();

        assertEquals("DEGRADED", view.health().status());
        assertEquals("java.io.IOException: disk full", view.health().reason());
        assertEquals("java.io.IOException: disk full", view.queueMetrics().lastFailureReason());
        assertEquals("java.io.IOException: disk full", view.lastFailure());
    }
}
