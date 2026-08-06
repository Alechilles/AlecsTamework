package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests the isolated native-mount rider movement-settings scaling rule. */
class NativeMountMovementSettingsServiceTest {

    @Test
    void copiedSettingsScaleOnlyBaseSpeedOnce() {
        MovementSettings source = new MovementSettings();
        source.baseSpeed = 6.0F;
        source.acceleration = 0.35F;
        source.jumpForce = 13.0F;
        source.canFly = true;

        MovementSettings scaled = NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, 1.25);

        assertNotSame(source, scaled);
        assertEquals(6.0F, source.baseSpeed);
        assertEquals(7.5F, scaled.baseSpeed);
        assertEquals(source.acceleration, scaled.acceleration);
        assertEquals(source.jumpForce, scaled.jumpForce);
        assertEquals(source.canFly, scaled.canFly);
    }

    @Test
    void invalidInputsUseNeutralMultiplier() {
        MovementSettings source = new MovementSettings();
        source.baseSpeed = 6.0F;

        assertEquals(6.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, Double.NaN).baseSpeed);
        assertEquals(6.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(source, 0.0).baseSpeed);
        assertEquals(0.0F,
                NativeMountMovementSettingsService.copyWithScaledBaseSpeed(null, 1.25).baseSpeed);
    }

    @Test
    void mountedProfileResolutionRetainsTheRememberedSourceProfile() {
        NativeMountMovementSettingsService service = new NativeMountMovementSettingsService();
        service.rememberLiveRoleProfile("Tetrabird_Descent_Test", null);

        assertEquals("Mount", service.resolveMountedMovementConfigId("Tetrabird_Descent_Test", null));
    }

    @Test
    void unmountedRoleRecoveryReturnsCurrentRole() {
        assertEquals("Wolf_Default", NativeMountMovementSettingsService.resolveManagedRoleId(
                false, null, "Wolf_Default"));
    }

    @Test
    void mountedRoleLookupPassesTheMountOriginalIndexToNpcPlugin() throws Exception {
        NPCMountComponent mount = new NPCMountComponent();
        mount.setOriginalRoleIndex(42);
        RecordingNpcPlugin plugin = allocate(RecordingNpcPlugin.class);
        plugin.roleName = "Horse_Default";

        assertEquals("Horse_Default", new NativeMountedRoleLookup(plugin).resolve(mount));
        assertEquals(42, plugin.requestedIndex);
    }

    @Test
    void mountedRoleLookupReturnsNullWhenNpcPluginCannotMapOriginalIndex() throws Exception {
        NPCMountComponent mount = new NPCMountComponent();
        mount.setOriginalRoleIndex(99);
        RecordingNpcPlugin plugin = allocate(RecordingNpcPlugin.class);

        assertNull(new NativeMountedRoleLookup(plugin).resolve(mount));
        assertEquals(99, plugin.requestedIndex);
    }

    @Test
    void activeRiderLookupUsesSuppliedUuidAndReturnsPlayerEntity() throws Exception {
        UUID riderId = UUID.randomUUID();
        RecordingWorld world = allocate(RecordingWorld.class);
        EntityStore entityStore = new EntityStore(world);
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            Ref<EntityStore> riderRef = store.createReference();
            store.put(riderRef, Player.getComponentType(), allocate(Player.class));
            world.result = riderRef;

            Ref<EntityStore> resolved = new ActiveWorldRiderLookup(world, store).resolve(riderId);

            assertSame(riderRef, resolved);
            assertEquals(riderId, world.requestedUuid);
        }
    }

    @Test
    void activeRiderLookupRejectsMissingWorldEntity() throws Exception {
        UUID riderId = UUID.randomUUID();
        RecordingWorld world = allocate(RecordingWorld.class);
        EntityStore entityStore = new EntityStore(world);
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            assertNull(new ActiveWorldRiderLookup(world, store).resolve(riderId));
            assertEquals(riderId, world.requestedUuid);
        }
    }

    @Test
    void activeRiderLookupRejectsEntityWithoutPlayerComponent() throws Exception {
        UUID riderId = UUID.randomUUID();
        RecordingWorld world = allocate(RecordingWorld.class);
        EntityStore entityStore = new EntityStore(world);
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            world.result = store.createReference();

            assertNull(new ActiveWorldRiderLookup(world, store).resolve(riderId));
            assertEquals(riderId, world.requestedUuid);
        }
    }

    private static final class RecordingNpcPlugin extends NPCPlugin {
        private String roleName;
        private int requestedIndex = Integer.MIN_VALUE;

        private RecordingNpcPlugin() {
            super(null);
        }

        @Override
        public String getName(int originalRoleIndex) {
            requestedIndex = originalRoleIndex;
            return roleName;
        }
    }

    private static final class RecordingWorld extends World {
        private UUID requestedUuid;
        private Ref<EntityStore> result;

        private RecordingWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID riderUuid) {
            requestedUuid = riderUuid;
            return result;
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
}
