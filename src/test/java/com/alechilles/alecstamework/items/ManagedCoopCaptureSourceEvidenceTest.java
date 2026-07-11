package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.Status;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Recovery source-kind evidence tests for already-itemized capture sources. */
class ManagedCoopCaptureSourceEvidenceTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void marksAndReadsExactCapturedItemRecoveryIdentity() {
        CapturedItemSource source = new CapturedItemSource(
                new UUID(0L, 21L), (short) 4, "Tool_Capture_Crate", HASH);

        String marked = ManagedCoopCaptureSourceEvidence.markCapturedItem(
                "{\"version\":\"1\",\"npcUuid\":\"00000000-0000-0000-0000-000000000001\"}",
                source);
        ManagedCoopCaptureSourceEvidence.ReadResult read =
                ManagedCoopCaptureSourceEvidence.read(marked);

        assertEquals(Status.CAPTURED_ITEM, read.status());
        assertNotNull(read.capturedItem());
        assertEquals(source, read.capturedItem());
    }

    @Test
    void unmarkedEntitySourceAndMalformedMarkerAreDistinct() {
        assertEquals(Status.ENTITY_SOURCE,
                ManagedCoopCaptureSourceEvidence.read("{}").status());

        JsonObject malformed = JsonParser.parseString("{}").getAsJsonObject();
        malformed.addProperty(ManagedCoopCaptureSourceEvidence.SNAPSHOT_FIELD, "wrong");
        assertEquals(Status.INVALID,
                ManagedCoopCaptureSourceEvidence.read(malformed.toString()).status());
    }

    @Test
    void markerCannotBeOverwrittenOrUseWeakFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new CapturedItemSource(
                new UUID(0L, 1L), (short) 0, "Tool_Capture_Crate", "weak"));

        CapturedItemSource source = new CapturedItemSource(
                new UUID(0L, 1L), (short) 0, "Tool_Capture_Crate", HASH);
        String marked = ManagedCoopCaptureSourceEvidence.markCapturedItem("{}", source);
        assertThrows(IllegalArgumentException.class,
                () -> ManagedCoopCaptureSourceEvidence.markCapturedItem(marked, source));
    }
}
