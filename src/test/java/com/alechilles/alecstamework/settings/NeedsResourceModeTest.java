package com.alechilles.alecstamework.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeedsResourceModeTest {
    @Test
    void blankAndUnknownValuesResolveToAccurate() {
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue(null));
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue(""));
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue("Direct"));
    }

    @Test
    void configValuesRoundTripCaseInsensitively() {
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue("accurate"));
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue("AutoFast"));
        assertEquals(NeedsResourceMode.ALWAYS_FAST, NeedsResourceMode.fromConfigValue("alwaysfast"));
        assertEquals("AlwaysFast", NeedsResourceMode.ALWAYS_FAST.toConfigValue());
    }
}
