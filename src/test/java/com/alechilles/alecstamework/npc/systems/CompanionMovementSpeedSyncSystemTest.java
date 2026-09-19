package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for companion movement-speed lifecycle change detection. */
class CompanionMovementSpeedSyncSystemTest {
    @Test
    void unregisteredTamedTypeStopsMovementReadsInsteadOfCrashingTheWorld() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             ValidatingMovementStore store = new ValidatingMovementStore()) {
            ComponentType<EntityStore, TameworkTamedComponent> tamedType = store.getRegistry()
                    .registerComponent(TameworkTamedComponent.class, TameworkTamedComponent::new);
            Field field = Tamework.class.getDeclaredField("tamedComponentType");
            field.setAccessible(true);
            field.set(Tamework.getInstance(), tamedType);
            Ref<EntityStore> ref = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(UUID.randomUUID());
            npc.setRoleName("Wild_MovementTest");
            store.put(ref, NPCEntity.getComponentType(), npc);
            store.put(ref, tamedType, new TameworkTamedComponent(false));

            new CompanionMovementSpeedSyncSystem().tick(0.05f, 0, store);
            assertEquals(1, store.tamedReads, "Active registrations must still resolve tame state.");
            int readsBeforeUnregister = store.componentReads;
            store.getRegistry().unregisterComponent(tamedType);

            CompanionMovementSpeedSyncSystem system = new CompanionMovementSpeedSyncSystem();
            assertDoesNotThrow(() -> system.tick(0.05f, 0, store));
            assertDoesNotThrow(() -> system.refreshImmediately(ref, store));
            assertEquals(readsBeforeUnregister, store.componentReads,
                    "Movement refreshes must stop before accessing components after unregistration.");
        }
    }

    private static final class ValidatingMovementStore extends TestEntityComponentStore {
        private int componentReads;
        private int tamedReads;

        private ValidatingMovementStore() {
            super(new EntityStore(null));
        }

        @Override
        public <T extends Component<EntityStore>> T getComponent(
                Ref<EntityStore> ref, ComponentType<EntityStore, T> type) {
            componentReads++;
            if (type == TameworkTamedComponent.getComponentType()) {
                // Match Store's validation using a real registered/unregistered component type.
                type.validate();
                tamedReads++;
            }
            return super.getComponent(ref, type);
        }
    }

    @Test
    void lifecycleCompletionCommitsOnlyWhenNpcAndCurrentMountedRiderRefreshesSucceed() {
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, false, false, false)));
        assertTrue(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, true, true)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(false, false, false, false)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, false, false)));
        assertFalse(CompanionMovementSpeedSyncSystem.isRefreshComplete(
                new CompanionMovementSpeedSyncSystem.RefreshCompletion(true, true, true, false)));
    }

    @Test
    void glideOrAvatarFlightMarkerExcludesNativeMovementRefresh() {
        assertTrue(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(true, false));
        assertTrue(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(false, true));
        assertFalse(CompanionMovementSpeedSyncSystem.shouldSkipManagedMovement(false, false));
    }

    @Test
    void fingerprintChangesForEveryManagedLifecycleInput() {
        UUID npcId = UUID.randomUUID();
        CompanionMovementSpeedSyncSystem.MovementSpeedFingerprint baseline =
                fingerprint(npcId, "Wolf_Default", 11L, 3L, false, null);

        assertFalse(CompanionMovementSpeedSyncSystem.hasChanged(baseline, baseline));
        assertFalse(changed(baseline, npcId, "Wolf_Default", 11L, 3L, false, null));
        assertTrue(changed(baseline, npcId, "Wolf_Armored", 11L, 3L, false, null));
        assertTrue(changed(baseline, npcId, "Wolf_Default", 12L, 3L, false, null));
        assertTrue(changed(baseline, npcId, "Wolf_Default", 11L, 4L, false, null));
        assertTrue(changed(baseline, npcId, "Wolf_Default", 11L, 3L, true, UUID.randomUUID()));
        assertTrue(changed(baseline, npcId, "Wolf_Default", 11L, 3L, false, UUID.randomUUID()));
    }

    @Test
    void nativeMountRefreshUsesExactSpeedWhileUnmountedRefreshKeepsEffectQuantization() throws Exception {
        var resolved = new CompanionMovementSpeedResolver().resolve(
                new TwCompanionMovementConfig.ResolvedMovement("test:cow", 1.0, 0.5, 2.0, List.of()),
                Map.of(),
                1.024
        );

        assertEquals(1.024, CompanionMovementSpeedSyncSystem.selectAppliedMultiplier(true, resolved), 0.0000001);
        assertEquals(1.0, CompanionMovementSpeedSyncSystem.selectAppliedMultiplier(false, resolved), 0.0000001);
    }

    private static CompanionMovementSpeedSyncSystem.MovementSpeedFingerprint fingerprint(
            UUID npcId, String roleId, long inputSignature, long configRevision,
            boolean nativeMounted, UUID riderId) {
        return new CompanionMovementSpeedSyncSystem.MovementSpeedFingerprint(
                npcId, roleId, inputSignature, configRevision, nativeMounted, riderId);
    }

    private static boolean changed(
            CompanionMovementSpeedSyncSystem.MovementSpeedFingerprint previous,
            UUID npcId, String roleId, long inputSignature, long configRevision,
            boolean nativeMounted, UUID riderId) {
        return CompanionMovementSpeedSyncSystem.hasChanged(
                previous, npcId, roleId, inputSignature, configRevision, nativeMounted, riderId);
    }
}
