package com.alechilles.alecstamework.npc.systems;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for attachment sync cache pruning during multi-world ticks. */
class CompanionAttachmentSyncSystemTest {
    @Test
    void pruneInactiveKeysDoesNotUseFailFastKeyViewRetainAll() {
        UUID activeNpc = UUID.randomUUID();
        UUID inactiveNpc = UUID.randomUUID();
        UUID concurrentNpc = UUID.randomUUID();
        Map<UUID, String> cache = new HashMap<>();
        cache.put(activeNpc, "active");
        cache.put(inactiveNpc, "inactive");

        HashSet<UUID> activeIds = new MutatingActiveNpcSet(cache, activeNpc, concurrentNpc);

        assertDoesNotThrow(() -> CompanionAttachmentSyncSystem.pruneInactiveKeys(cache, activeIds));
        assertTrue(cache.containsKey(activeNpc));
        assertFalse(cache.containsKey(inactiveNpc));
    }

    private static final class MutatingActiveNpcSet extends HashSet<UUID> {
        private final Map<UUID, String> cache;
        private final UUID activeNpc;
        private final UUID concurrentNpc;
        private boolean mutated;

        private MutatingActiveNpcSet(Map<UUID, String> cache, UUID activeNpc, UUID concurrentNpc) {
            this.cache = cache;
            this.activeNpc = activeNpc;
            this.concurrentNpc = concurrentNpc;
            add(activeNpc);
        }

        @Override
        public boolean contains(Object value) {
            if (!mutated) {
                mutated = true;
                cache.put(concurrentNpc, "concurrent");
            }
            return activeNpc.equals(value);
        }
    }
}
