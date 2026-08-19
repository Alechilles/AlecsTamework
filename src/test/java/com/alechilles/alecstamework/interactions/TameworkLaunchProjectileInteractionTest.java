package com.alechilles.alecstamework.interactions;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.projectile.config.BallisticDataProvider;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards player-safe look targeting for projectile launches. */
class TameworkLaunchProjectileInteractionTest {
    /** Protects CAE ranged attacks from falling back to close-combat aiming. */
    @Test
    void exposesProjectileBallisticsToNpcAiming() {
        assertInstanceOf(
                BallisticDataProvider.class,
                new TameworkLaunchProjectileInteraction("test"));
    }

    @Test
    void positiveLookTargetDistanceUsesSourceLookWithoutFallbackTargetResolution() {
        Transform sourceLook = new Transform(10.0, 20.0, 30.0, 0.0F, 0.0F, 0.0F);
        AtomicInteger fallbackCalls = new AtomicInteger();

        Vector3d target = TameworkLaunchProjectileInteraction.resolveLookTargetPosition(
                sourceLook, 48.0, () -> {
                    fallbackCalls.incrementAndGet();
                    return new Vector3d();
                });

        assertEquals(10.0, target.x, 1.0e-9);
        assertEquals(20.0, target.y, 1.0e-9);
        assertEquals(-18.0, target.z, 1.0e-9);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void zeroLookTargetDistanceUsesExistingTargetFallback() {
        Vector3d fallbackTarget = new Vector3d(4.0, 5.0, 6.0);
        AtomicInteger fallbackCalls = new AtomicInteger();

        Vector3d target = TameworkLaunchProjectileInteraction.resolveLookTargetPosition(
                new Transform(10.0, 20.0, 30.0, 0.0F, 0.0F, 0.0F), 0.0,
                () -> {
                    fallbackCalls.incrementAndGet();
                    return fallbackTarget;
                });

        assertSame(fallbackTarget, target);
        assertEquals(1, fallbackCalls.get());
    }
}
