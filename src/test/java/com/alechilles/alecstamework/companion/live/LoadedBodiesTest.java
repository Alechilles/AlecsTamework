package com.alechilles.alecstamework.companion.live;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadedBodiesTest {
    @Test
    void aLateRemovalOfAReplacedBodyDoesNotUnregisterTheNewOne() {
        LoadedBodies<String> bodies = new LoadedBodies<>();
        UUID id = UUID.randomUUID();
        bodies.put(id, "old-ref");
        bodies.put(id, "new-ref");          // role change: re-added under a new ref

        assertFalse(bodies.removeIfSame(id, "old-ref"));
        assertEquals("new-ref", bodies.get(id));
        assertTrue(bodies.removeIfSame(id, "new-ref"));
        assertNull(bodies.get(id));
    }
}
