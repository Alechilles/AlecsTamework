package com.alechilles.alecstamework.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeedsResourceModeTest {
    @Test
    void blankAndUnknownValuesResolveToAutoFast() {
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue(null));
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue(""));
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue("Direct"));
    }

    @Test
    void configValuesRoundTripCaseInsensitively() {
        assertEquals(NeedsResourceMode.ACCURATE, NeedsResourceMode.fromConfigValue("accurate"));
        assertEquals(NeedsResourceMode.AUTO_FAST, NeedsResourceMode.fromConfigValue("AutoFast"));
        assertEquals(NeedsResourceMode.ALWAYS_FAST, NeedsResourceMode.fromConfigValue("alwaysfast"));
        assertEquals("AlwaysFast", NeedsResourceMode.ALWAYS_FAST.toConfigValue());
    }
}
