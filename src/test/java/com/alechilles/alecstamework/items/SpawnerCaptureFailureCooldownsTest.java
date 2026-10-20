package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerCaptureFailureCooldownsTest {

    @Test
    void cooldownBlocksOnlyTheSamePlayerAndItemConfigUntilItEnds() {
        SpawnerCaptureFailureCooldowns cooldowns = new SpawnerCaptureFailureCooldowns();
        UUID player = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();

        cooldowns.record(player, "Capture_Crate", 1_500L, 1_000L);

        assertTrue(cooldowns.active(player, "Capture_Crate", attempt, 1_499L));
        assertFalse(cooldowns.active(UUID.randomUUID(), "Capture_Crate", attempt, 1_499L));
        assertFalse(cooldowns.active(player, "Other_Crate", attempt, 1_499L));
        assertFalse(cooldowns.active(player, "Capture_Crate", attempt, 1_500L));
    }
}
