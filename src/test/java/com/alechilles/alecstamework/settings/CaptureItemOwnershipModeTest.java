package com.alechilles.alecstamework.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureItemOwnershipModeTest {

    @Test
    void theRetiredSettingsMapToTheModeThatKeepsTheirBehavior() {
        assertEquals(CaptureItemOwnershipMode.FOLLOWS_ITEM, CaptureItemOwnershipMode.resolve(null, true, true));
        assertEquals(CaptureItemOwnershipMode.FOLLOWS_ITEM, CaptureItemOwnershipMode.resolve(null, true, false));
        assertEquals(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, CaptureItemOwnershipMode.resolve(null, false, true));
        assertEquals(CaptureItemOwnershipMode.OWNER_ONLY, CaptureItemOwnershipMode.resolve(null, false, false));
    }

    @Test
    void aMissingRetiredValueCountsAsItsOldDefault() {
        assertEquals(CaptureItemOwnershipMode.FOLLOWS_ITEM, CaptureItemOwnershipMode.resolve(null, null, false));
        assertEquals(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, CaptureItemOwnershipMode.resolve(null, false, null));
        assertEquals(CaptureItemOwnershipMode.FOLLOWS_ITEM, CaptureItemOwnershipMode.resolve(null, null, null));
    }

    @Test
    void anExplicitModeWinsAndAnUnknownOneFallsBackToTheRetiredValues() {
        assertEquals(CaptureItemOwnershipMode.OWNER_ONLY, CaptureItemOwnershipMode.resolve("owner_only", true, true));
        assertEquals(CaptureItemOwnershipMode.CHANGES_ON_RELEASE,
                CaptureItemOwnershipMode.resolve("Sideways", false, true));
        assertEquals(CaptureItemOwnershipMode.FOLLOWS_ITEM, CaptureItemOwnershipMode.resolve("Sideways", null, null));
    }
}
