package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies observable mounted-input application from one coalesced batch. */
class MountedRideInputApplierTest {

    @Test
    void appliesClientThenMountThenMouseAndPrefersWishOverAbsoluteFallback() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> mountRef = store.createReference();
            store.put(
                    mountRef,
                    TransformComponent.getComponentType(),
                    new TransformComponent(new Vector3d(0.0, 0.0, 0.0), new Rotation3f())
            );
            TameworkRideMountComponent mount = new TameworkRideMountComponent();
            MountedRideInputMailbox.Batch batch = new MountedRideInputMailbox.Batch(
                    clientInput(),
                    mountInput(),
                    new MountedRideInputMailbox.MouseInteractionSnapshot(true, true, 4, -2)
            );

            assertTrue(new MountedRideInputApplier().apply(batch, mountRef, store, mount));

            assertEquals(2.0f, mount.getBodyYaw(), 0.0001f);
            assertEquals(1.99f, mount.getHeadYaw(), 0.0001f);
            assertEquals(0.005f, mount.getHeadPitch(), 0.0001f);
            assertEquals(0.4472135955, mount.getWishX(), 0.0001);
            assertEquals(-0.8944271910, mount.getWishZ(), 0.0001);
            assertTrue(mount.isRiderJumping());
            assertTrue(mount.isRiderCrouching());
            assertTrue(mount.isRiderFlying());
            assertTrue(mount.isRiderSprinting());
        }
    }

    @Test
    void appliesMountAbsolutePositionWhenClientProvidesNoWishMovement() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> mountRef = store.createReference();
            store.put(
                    mountRef,
                    TransformComponent.getComponentType(),
                    new TransformComponent(new Vector3d(10.0, 5.0, 10.0), new Rotation3f())
            );
            TameworkRideMountComponent mount = new TameworkRideMountComponent();
            MountedRideInputMailbox.Batch batch = new MountedRideInputMailbox.Batch(
                    clientInputWithoutWish(),
                    absoluteMountInput(),
                    null
            );

            assertTrue(new MountedRideInputApplier().apply(batch, mountRef, store, mount));

            assertTrue(mount.hasWishMovement());
            assertEquals(0.0, mount.getWishX(), 0.0001);
            assertEquals(0.0, mount.getWishY(), 0.0001);
            assertEquals(1.0, mount.getWishZ(), 0.0001);
        }
    }

    private static MountedRideInputMailbox.ClientMovementSnapshot clientInput() {
        return new MountedRideInputMailbox.ClientMovementSnapshot(
                true,
                0.5f,
                0.0f,
                0.0f,
                false,
                0.0f,
                0.0f,
                0.0f,
                true,
                0.5,
                0.0,
                -1.0,
                false,
                0.0,
                0.0,
                0.0,
                false,
                0,
                true,
                MountedRideInputMailbox.STATE_JUMPING
        );
    }

    private static MountedRideInputMailbox.MountMovementSnapshot mountInput() {
        return new MountedRideInputMailbox.MountMovementSnapshot(
                true,
                100.0,
                0.0,
                100.0,
                true,
                2.0f,
                0.0f,
                0.0f,
                true,
                MountedRideInputMailbox.STATE_SWIM_JUMPING
                        | MountedRideInputMailbox.STATE_FORCED_CROUCHING
                        | MountedRideInputMailbox.STATE_FLYING
                        | MountedRideInputMailbox.STATE_RUNNING
        );
    }

    private static MountedRideInputMailbox.ClientMovementSnapshot clientInputWithoutWish() {
        return new MountedRideInputMailbox.ClientMovementSnapshot(
                false,
                0.0f,
                0.0f,
                0.0f,
                false,
                0.0f,
                0.0f,
                0.0f,
                false,
                0.0,
                0.0,
                0.0,
                false,
                0.0,
                0.0,
                0.0,
                false,
                0,
                false,
                0
        );
    }

    private static MountedRideInputMailbox.MountMovementSnapshot absoluteMountInput() {
        return new MountedRideInputMailbox.MountMovementSnapshot(
                true,
                10.0,
                5.0,
                8.0,
                false,
                0.0f,
                0.0f,
                0.0f,
                false,
                0
        );
    }
}
