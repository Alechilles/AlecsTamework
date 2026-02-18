package com.alechilles.alecstamework.npc.components;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for command link component tool-id behavior. */
class TameworkCommandLinksComponentTest {

    @Test
    void withToolIdAddedDeduplicatesValues() {
        UUID owner = UUID.randomUUID();
        TameworkCommandLinksComponent component = new TameworkCommandLinksComponent(
                owner,
                new String[] { "tool-a", "tool-a" }
        );

        TameworkCommandLinksComponent updated = component.withToolIdAdded("tool-b");

        assertTrue(updated.containsToolId("tool-a"));
        assertTrue(updated.containsToolId("tool-b"));
    }

    @Test
    void withToolIdRemovedDropsValue() {
        UUID owner = UUID.randomUUID();
        TameworkCommandLinksComponent component = new TameworkCommandLinksComponent(
                owner,
                new String[] { "tool-a", "tool-b" }
        );

        TameworkCommandLinksComponent updated = component.withToolIdRemoved("tool-a");

        assertFalse(updated.containsToolId("tool-a"));
        assertTrue(updated.containsToolId("tool-b"));
    }
}
