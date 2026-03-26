package com.alechilles.alecstamework.config.overrides;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwConfigOverrideManagerPackKeyTest {

    @Test
    void normalizeSourcePackKeyStripsOverridePrefix() {
        assertEquals(
                "Alechilles:Alec's Animal Husbandry!",
                TwConfigOverrideManager.normalizeSourcePackKey(
                        "tamework-overrides::Alechilles:Alec's Animal Husbandry!"
                )
        );
    }

    @Test
    void normalizeSourcePackKeyDecodesEncodedPackKey() {
        assertEquals(
                "Alechilles:Alec's Animal Husbandry!",
                TwConfigOverrideManager.normalizeSourcePackKey(
                        "Alechilles%3AAlec%27s+Animal+Husbandry%21"
                )
        );
    }
}
