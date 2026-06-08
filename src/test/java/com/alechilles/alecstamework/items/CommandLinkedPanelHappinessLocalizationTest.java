package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelHappinessLocalizationTest {

    @Test
    void happinessPresentationResolvesConfiguredLabelsAsLanguageKeys() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(language, stripped, stripped)"));
        assertTrue(content.contains("LocalizedText.resolveConfigValue(language, label, label)"));
    }
}
