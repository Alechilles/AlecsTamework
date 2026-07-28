package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Async coop reads preserve authoritative absence, diagnostics, and storage failure. */
class SqliteCompanionCoopReaderTest {
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "coop", 10, 64, 20, 0);
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void readsEmptySlotAndKeepsFailureDistinctFromAbsence()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteCompanionCoopStore(connection)
                    .registerSlot(CoopSlot.unoccupied(SLOT));
            connection.commit();
        }

        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        SqliteCompanionCoopReader reader =
                new SqliteCompanionCoopReader(reads);
        PersistenceReadResult.Found<CoopSlot> slot = found(
                reader.findSlot(SLOT).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
        );
        assertEquals(0, slot.revision());
        assertEquals(SLOT, slot.value().key());
        assertInstanceOf(
                PersistenceReadResult.Absent.class,
                reader.findResidencyByProfile(PROFILE)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
        assertEquals(
                CoopConflictDiagnostic.Reason.NONE,
                found(reader.diagnoseCapture(SLOT, PROFILE)
                        .toCompletableFuture().get(
                                10, TimeUnit.SECONDS
                        )).value().reason()
        );
        assertEquals(
                CoopConflictDiagnostic.Reason.SLOT_EMPTY,
                found(reader.diagnoseRelease(SLOT, PROFILE)
                        .toCompletableFuture().get(
                                10, TimeUnit.SECONDS
                        )).value().reason()
        );
        PersistenceReadResult.Found<List<CoopOccupancy>> all = found(
                reader.findAllOccupancies().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
        );
        assertEquals(List.of(), all.value());
        assertEquals(0, all.revision());

        reads.shutdown(Duration.ofSeconds(5));
        assertInstanceOf(
                PersistenceReadResult.Failed.class,
                reader.findSlot(SLOT).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
        );
    }

    @SuppressWarnings("unchecked")
    private <T> PersistenceReadResult.Found<T> found(
            PersistenceReadResult<T> result
    ) {
        return assertInstanceOf(
                PersistenceReadResult.Found.class, result
        );
    }
}
