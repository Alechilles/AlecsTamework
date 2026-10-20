package com.alechilles.alecstamework.interactions;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Guards player-safe look targeting for projectile launches. */
class TameworkLaunchProjectileInteractionTest {
    /** Legacy Hydra interactions must keep aiming at eye height and gain no optional effects. */
    @Test
    void omittedEggFieldsPreserveLegacyAimingAndEffects() {
        TameworkLaunchProjectileInteraction interaction = decode("{}");
        Vector3d feet = new Vector3d(4, 20, 6);
        Vector3d aim = interaction.resolveEntityAimPosition(feet, 1.5);
        AtomicInteger markers = new AtomicInteger();

        interaction.emitLandingMarker(aim, (id, position) -> markers.incrementAndGet());

        assertEquals(new Vector3d(4, 21.5, 6), aim);
        assertEquals(0, markers.get());
        assertNull(interaction.buildImpactEffectComponent(UUID.randomUUID()));
    }

    @Test
    void groundOffsetUsesFeetAndMarkerKeepsTheFrozenSolverPosition() {
        TameworkLaunchProjectileInteraction interaction = decode("""
                {"TargetGroundOffset": 0.05, "LandingMarkerParticleSystemId": "egg_marker"}
                """);
        Vector3d feet = new Vector3d(4, 20, 6);
        Vector3d aim = interaction.resolveEntityAimPosition(feet, 1.5);
        AtomicReference<String> particleId = new AtomicReference<>();
        AtomicReference<Vector3d> marker = new AtomicReference<>();

        interaction.emitLandingMarker(aim, (id, position) -> {
            particleId.set(id);
            marker.set(position);
        });
        aim.set(100, 100, 100);
        feet.set(200, 200, 200);

        assertEquals("egg_marker", particleId.get());
        assertEquals(new Vector3d(4, 20.05, 6), marker.get());
    }

    @Test
    void markerFailureLeavesTheOptionalPresentationFailureLocal() {
        TameworkLaunchProjectileInteraction interaction = decode("""
                {"LandingMarkerParticleSystemId": "egg_marker"}
                """);
        interaction.emitLandingMarker(new Vector3d(4, 20.05, 6), (id, position) -> {
            throw new IllegalArgumentException("Missing particle asset");
        });
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

    private static TameworkLaunchProjectileInteraction decode(String json) {
        BsonDocument document = BsonDocument.parse(json);
        document.put("ProjectileId", new BsonString("egg"));
        return TameworkLaunchProjectileInteraction.CODEC.decode(document, new ExtraInfo());
    }
}
