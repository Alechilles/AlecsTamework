package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression coverage for item labels shown by bonded-companion revival UI. */
class ReviveCostItemTextTest {
    @Test
    void fallsBackToHumanReadableItemIdWhenAssetsAreUnavailable() {
        assertEquals("Revitalizing Essence", ReviveCostItemText.resolve(
                "Revitalizing_Essence", "Revitalizing_Essence", "en-US"));
    }

    @Test
    void preservesAnAlreadyResolvedAuthoritativeName() {
        assertEquals("Draconic Essence", ReviveCostItemText.resolve(
                "Draconic_Essence", "Draconic Essence", "en-US"));
    }
}
