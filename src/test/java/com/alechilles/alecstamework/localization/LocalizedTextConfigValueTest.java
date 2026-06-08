package com.alechilles.alecstamework.localization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalizedTextConfigValueTest {

    @Test
    void resolvesBundledLanguageKeyFromConfigValue() {
        assertEquals(
                "Companion Talents",
                LocalizedText.resolveConfigValue(null, "tamework.ui.talents.title", "Fallback")
        );
    }

    @Test
    void keepsRawConfigTextWhenNoTranslationExists() {
        assertEquals(
                "Custom Raw Label",
                LocalizedText.resolveConfigValue(null, "Custom Raw Label", "Fallback")
        );
    }

    @Test
    void usesFallbackForBlankConfigValue() {
        assertEquals("Fallback", LocalizedText.resolveConfigValue(null, " ", "Fallback"));
    }

    @Test
    void formatsResolvedConfigValue() {
        assertEquals(
                "Level 7",
                LocalizedText.formatConfigValue(null, "tamework.ui.talents.requirement.level", "Level {0}", 7)
        );
    }
}
