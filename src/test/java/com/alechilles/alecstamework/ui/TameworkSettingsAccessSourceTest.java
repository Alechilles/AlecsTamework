package com.alechilles.alecstamework.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsAccessSourceTest {

    @Test
    void settingsPageChecksPlayerRefPermissionsInsteadOfPlayerComponent() throws IOException {
        String content = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "ui",
                "TameworkSettingsPageService.java"
        ));

        assertFalse(content.contains("hasAccess(playerRef, player)"));
        assertFalse(content.contains("hasAccess(uiPlayerRef, player)"));
        assertTrue(content.contains("hasAccess(playerRef, playerRef)"));
        assertTrue(content.contains("hasAccess(uiPlayerRef, uiPlayerRef)"));
    }

    @Test
    void settingsAnnouncementChecksPlayerRefPermissionsInsteadOfPlayerComponent() throws IOException {
        String content = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "ui",
                "TameworkSettingsAnnouncementService.java"
        ));

        assertFalse(content.contains("hasAccess(uiPlayerRef, player)"));
        assertTrue(content.contains("hasAccess(uiPlayerRef, uiPlayerRef)"));
    }
}
