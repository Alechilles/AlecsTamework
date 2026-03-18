package com.alechilles.alecstamework.npc.components;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests breeding component enable-toggle defaults and cloning behavior. */
class TameworkBreedingComponentTest {

    @Test
    void legacyConstructorDefaultsBreedingDisabled() {
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                true,
                5678L,
                UUID.randomUUID()
        );

        assertFalse(component.isEnabled());
    }

    @Test
    void clonePreservesBreedingEnabledState() {
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                false,
                true,
                5678L,
                UUID.randomUUID()
        );

        TameworkBreedingComponent cloned = component.clone();

        assertTrue(cloned.isEnabled());
    }
}
