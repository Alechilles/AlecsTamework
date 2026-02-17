package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameValidationTest {

    @Test
    void trimsWhitespaceWhenEnabled() {
        NamingRules rules = NamingRules.builder()
                .trimWhitespace(true)
                .build();
        NameValidation.NameValidationResult result = NameValidation.validate(
                "  Fluffy  ",
                rules,
                false,
                false
        );
        assertTrue(result.isOk());
        assertEquals("Fluffy", result.getNormalizedName());
    }

    @Test
    void rejectsInvalidCharactersByDefault() {
        NamingRules rules = NamingRules.builder().build();
        NameValidation.NameValidationResult result = NameValidation.validate(
                "Fluffy!",
                rules,
                false,
                false
        );
        assertFalse(result.isOk());
    }

    @Test
    void allowsAnyCharactersWhenConfigured() {
        NamingRules rules = NamingRules.builder()
                .allowedChars(NamingRules.ALLOWED_CHARS_ANY)
                .build();
        NameValidation.NameValidationResult result = NameValidation.validate(
                "Fluffy!",
                rules,
                false,
                false
        );
        assertTrue(result.isOk());
        assertEquals("Fluffy!", result.getNormalizedName());
    }

    @Test
    void enforcesLengthLimits() {
        NamingRules rules = NamingRules.builder()
                .minLength(2)
                .maxLength(4)
                .build();
        assertFalse(NameValidation.validate("A", rules, false, false).isOk());
        assertFalse(NameValidation.validate("ABCDE", rules, false, false).isOk());
        assertTrue(NameValidation.validate("Abcd", rules, false, false).isOk());
    }

    @Test
    void blocksRenameWhenConfigured() {
        NamingRules rules = NamingRules.builder()
                .allowRename(false)
                .build();
        NameValidation.NameValidationResult result = NameValidation.validate(
                "Buddy",
                rules,
                true,
                true
        );
        assertFalse(result.isOk());
    }

    @Test
    void blocksReplacingExistingNamesWhenConfigured() {
        NamingRules rules = NamingRules.builder()
                .replaceExisting(false)
                .build();
        NameValidation.NameValidationResult result = NameValidation.validate(
                "Buddy",
                rules,
                false,
                true
        );
        assertFalse(result.isOk());
    }
}
