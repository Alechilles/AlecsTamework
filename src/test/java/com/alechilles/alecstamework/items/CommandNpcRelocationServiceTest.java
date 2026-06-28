package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards linked companion recall/lost timing contracts.
 */
class CommandNpcRelocationServiceTest {

    @Test
    void defaultLostDetectionWindowIsTenSeconds() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandNpcRelocationService.java"
        ), StandardCharsets.UTF_8);
        String defaultConfig = Files.readString(Path.of(
                "src", "main", "resources", "Server", "Tamework", "Global", "TwGlobalDefault.json"
        ), StandardCharsets.UTF_8);
        String config = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "config", "assets", "TwGlobalConfig.java"
        ), StandardCharsets.UTF_8);

        assertTrue(
                service.contains("MAX_RELOCATION_WAIT_MS = 10000L"),
                "Relocation fallback wait should mark companions lost after 10 seconds."
        );
        assertTrue(
                defaultConfig.contains("\"RelocationMaxWaitMs\": 10000"),
                "Default global config should mark companions lost after 10 seconds."
        );
        assertTrue(
                config.contains("commandRelocationMaxWaitMs = 10000"),
                "TwGlobalConfig Java fallback should match the 10 second default."
        );
    }

    @Test
    void pendingRecallSnapshotExposesCountdownForLinkedPanel() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandNpcRelocationService.java"
        ), StandardCharsets.UTF_8);
        String entryService = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandLinkedPanelEntryService.java"
        ), StandardCharsets.UTF_8);
        String featureHandler = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandItemFeatureHandler.java"
        ), StandardCharsets.UTF_8);

        assertTrue(
                service.contains("public PendingRecallSnapshot getPendingRecallSnapshot"),
                "Relocation service should expose read-only pending recall state."
        );
        assertTrue(
                service.contains("record PendingRecallSnapshot"),
                "Pending recall snapshot should be an explicit immutable record."
        );
        assertTrue(
                service.contains("remainingUntilLostMs"),
                "Pending recall snapshot should expose the remaining time until the companion becomes lost."
        );
        assertTrue(
                entryService.contains("CommandNpcRelocationService relocationService"),
                "Linked panel entry service should receive relocation state."
        );
        assertTrue(
                entryService.contains("getPendingRecallSnapshot(record.npcUuid)"),
                "Linked panel entries should read pending recall countdown state."
        );
        assertTrue(
                featureHandler.contains("lostService,\r\n                relocationService,")
                        || featureHandler.contains("lostService,\n                relocationService,"),
                "CommandItemFeatureHandler should wire relocation state into linked panel entries."
        );
    }
}
