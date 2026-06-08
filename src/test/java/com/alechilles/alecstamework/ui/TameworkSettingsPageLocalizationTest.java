package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsPageLocalizationTest {

    @Test
    void settingsPageDropdownsAndStatusUseLocalizedText() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkSettingsPage.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolve(playerRef"));
        assertTrue(content.contains("LocalizedText.format(playerRef"));
        assertTrue(content.contains("TameworkSettingsPreset.dropdownEntries(resolveLanguage())"));
        assertFalse(content.contains("LocalizableString.fromString(\"Per World\")"));
        assertFalse(content.contains("\"Failed to apply settings.\""));
        assertFalse(content.contains("\"Applied settings.\""));
    }
}
