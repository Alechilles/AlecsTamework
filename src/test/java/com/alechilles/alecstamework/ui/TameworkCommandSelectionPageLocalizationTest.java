package com.alechilles.alecstamework.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCommandSelectionPageLocalizationTest {

    @Test
    void commandOptionLabelsResolveConfigLanguageKeys() throws Exception {
        String page = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java"),
                StandardCharsets.UTF_8
        );
        String options = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/ui/CommandSelectionOptionSource.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(page.contains("CommandSelectionOptionSource.build("));
        assertTrue(options.contains("LocalizedText.resolveConfigValue("));
        assertFalse(options.contains("return entry.getDisplayName();"));
    }
}
