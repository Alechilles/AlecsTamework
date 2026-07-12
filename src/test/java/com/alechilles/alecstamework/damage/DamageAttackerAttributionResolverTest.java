package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DamageAttackerAttributionResolverTest {
    private final DamageAttackerAttributionResolver resolver = new DamageAttackerAttributionResolver();

    @Test
    void directEntitySourceUsesItsEntityReference() {
        UUID attacker = UUID.randomUUID();
        Damage.EntitySource source = new Damage.EntitySource(null);

        assertEquals(attacker, resolver.resolve(source, ignored -> attacker));
    }

    @Test
    void projectilePrefersAttributedShooterThenFallsBackToProjectile() {
        UUID attacker = UUID.randomUUID();
        Damage.ProjectileSource source = new Damage.ProjectileSource(null, null);
        AtomicInteger calls = new AtomicInteger();

        UUID resolved = resolver.resolve(source, ignored -> calls.incrementAndGet() == 1 ? null : attacker);

        assertEquals(attacker, resolved);
        assertEquals(2, calls.get());
    }

    @Test
    void environmentalAndMissingSourcesAreUnattributed() {
        Damage.Source environment = new Damage.Source() {
        };

        assertNull(resolver.resolve(environment, ignored -> UUID.randomUUID()));
        assertNull(resolver.resolve(null, ignored -> UUID.randomUUID()));
    }
}
