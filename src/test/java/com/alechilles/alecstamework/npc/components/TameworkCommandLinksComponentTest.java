package com.alechilles.alecstamework.npc.components;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void withToolIdUpdatesPreserveHomePosition() {
        UUID owner = UUID.randomUUID();
        Vector3d home = new Vector3d(12.5, 70.0, -42.25);
        TameworkCommandLinksComponent component = new TameworkCommandLinksComponent(
                owner,
                new String[] { "tool-a" },
                home
        );

        TameworkCommandLinksComponent added = component.withToolIdAdded("tool-b");
        TameworkCommandLinksComponent removed = added.withToolIdRemoved("tool-a");

        assertTrue(added.hasHome());
        assertTrue(removed.hasHome());
        assertNotNull(added.getHomePosition());
        assertNotNull(removed.getHomePosition());
        assertTrue(Math.abs(added.getHomePosition().x - home.x) < 0.0001);
        assertTrue(Math.abs(added.getHomePosition().y - home.y) < 0.0001);
        assertTrue(Math.abs(added.getHomePosition().z - home.z) < 0.0001);
        assertTrue(Math.abs(removed.getHomePosition().x - home.x) < 0.0001);
        assertTrue(Math.abs(removed.getHomePosition().y - home.y) < 0.0001);
        assertTrue(Math.abs(removed.getHomePosition().z - home.z) < 0.0001);
    }
}
