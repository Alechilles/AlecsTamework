package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionModeCycleLocalizationTest {

    @Test
    void modeCycleMessagesResolveConfiguredLanguageKeysBeforePresentation() throws Exception {
        String content = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/npc/actions/InteractionModeCycleEffects.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(content.contains("LocalizedText.resolveConfigValue(language, configuredMessage, configuredMessage)"));
        assertFalse(content.contains("showFloatingTextMessage(next.message"));
        assertFalse(content.contains("applyUiMessage(next.message"));
    }
}
