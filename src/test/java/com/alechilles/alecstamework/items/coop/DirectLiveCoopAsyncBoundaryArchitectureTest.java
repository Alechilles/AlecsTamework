package com.alechilles.alecstamework.items.coop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the value-only seam between world scanning and facade completions. */
class DirectLiveCoopAsyncBoundaryArchitectureTest {
    private static final Path SYSTEM = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/"
                    + "CommandDirectLiveCoopSystem.java"
    );
    private static final Path TRACKER = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/coop/"
                    + "DirectLiveCoopCompletionTracker.java"
    );
    private static final Path AUTHOR = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/coop/"
                    + "DirectLiveCoopAuthor.java"
    );
    private static final Path EVIDENCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/"
                    + "HytaleDirectLiveCoopEvidenceFactory.java"
    );

    @Test
    void worldFacingSystemDoesNotOwnFacadeCompletionCallbacks()
            throws Exception {
        String source = Files.readString(SYSTEM);

        assertFalse(source.contains(".whenComplete("));
        assertTrue(source.contains("completions.trackCapture("));
        assertTrue(source.contains("completions.trackRelease("));
        assertTrue(source.contains("completions.trackRegistration("));
    }

    @Test
    void completionTrackerHasNoHytaleRuntimeTypeBoundary()
            throws Exception {
        String source = Files.readString(TRACKER);

        assertTrue(source.contains("stage.whenComplete("));
        for (String forbidden : List.of(
                "import com.hypixel.hytale",
                "HytaleDirectLiveCoop",
                "TwCoopConfig",
                "Vector3d",
                "ItemContainer",
                "NPCEntity"
        )) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test
    void queuedPresentationEvidenceIsStringsAndPrimitiveCoordinates()
            throws Exception {
        String compact = Files.readString(TRACKER)
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains(
                "public record PublishedEffect( @Nonnull String worldKey, "
                        + "@Nonnull String physicalCoopKey, double x, "
                        + "double y, double z )"
        ));
    }

    @Test
    void retainedCaptureEvidenceIsEncodedBeforeTheAsyncAuthor()
            throws Exception {
        String author = Files.readString(AUTHOR);
        String evidence = Files.readString(EVIDENCE);
        String sourceRecord = author.substring(
                author.indexOf("public record LiveNpcSource("),
                author.indexOf("/** Stable author outcomes")
        );

        assertTrue(sourceRecord.contains(
                "SnapshotCodecRegistry.EncodedSnapshot encodedSnapshot"
        ));
        assertTrue(sourceRecord.contains("CoopSlotKey observedSlot"));
        assertFalse(sourceRecord.contains("CoopResidentStateSnapshot"));
        assertFalse(sourceRecord.contains("Component"));
        assertFalse(sourceRecord.contains("Ref<"));
        assertFalse(sourceRecord.contains("Store<"));
        assertFalse(author.contains("snapshotCodecs.encode("));
        assertTrue(evidence.indexOf("snapshotCodecs.encode(")
                < evidence.indexOf(
                "new DirectLiveCoopAuthor.LiveNpcSource("
        ));
        for (java.lang.reflect.RecordComponent component
                : DirectLiveCoopAuthor.LiveNpcSource.class
                .getRecordComponents()) {
            String type = component.getType().getName();
            assertFalse(type.startsWith("com.hypixel.hytale"), type);
            assertFalse(type.contains("CoopResidentStateSnapshot"), type);
            assertFalse(type.contains(".npc.components."), type);
        }
    }
}
