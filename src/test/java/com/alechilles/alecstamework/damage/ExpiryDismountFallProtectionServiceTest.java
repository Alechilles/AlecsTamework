package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpiryDismountFallProtectionServiceTest {
    private final ExpiryDismountFallProtectionService protection =
            new ExpiryDismountFallProtectionService();
    private final UUID player = UUID.randomUUID();

    @Test
    void protects_only_the_armed_player_for_one_minute() {
        protection.arm(player, 100_000L);

        assertTrue(protection.isProtected(player, 100_000L));
        assertTrue(protection.isProtected(player, 159_999L));
        assertFalse(protection.isProtected(player, 160_000L));
        assertFalse(protection.isProtected(UUID.randomUUID(), 100_000L));
    }

    @Test
    void clearing_after_a_landing_stops_the_protection() {
        protection.arm(player, 100_000L);
        assertFalse(protection.clearWhenGrounded(player, 100_100L));
        assertTrue(protection.clearWhenGrounded(player, 100_250L));

        assertFalse(protection.isProtected(player, 100_001L));
    }
}
