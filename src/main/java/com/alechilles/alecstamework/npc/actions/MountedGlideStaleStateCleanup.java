package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Clears stale mounted-glide links before the interaction path evaluates active mount state.
 */
final class MountedGlideStaleStateCleanup {
    private MountedGlideStaleStateCleanup() {
    }

    static void clearInvalidRiderState(
            Ref<EntityStore> playerRef,
            Player playerComponent,
            Role role,
            ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType,
            ComponentType<EntityStore, TameworkMountedGlideComponent> mountType,
            ComponentType<EntityStore, NPCMountComponent> nativeMountType,
            Store<EntityStore> store) {
        TameworkMountedGlideRiderComponent rider = store.getComponent(playerRef, riderType);
        if (rider == null) {
            return;
        }

        Ref<EntityStore> mountRef = resolveMountRefFromRider(rider, store);
        TameworkMountedGlideComponent mount = mountRef == null || !mountRef.isValid()
                ? null
                : store.getComponent(mountRef, mountType);
        NPCMountComponent nativeMount = mountRef == null || !mountRef.isValid()
                ? null
                : store.getComponent(mountRef, nativeMountType);
        CleanupState state = CleanupState.from(rider, mountRef, mount, nativeMount, playerRef, store);
        if (!state.stale()) {
            return;
        }

        logStaleCleanup(role, rider, playerComponent, mountRef, mount, nativeMount, state);
        if (mountRef != null && mountRef.isValid()) {
            clearMountSideState(playerRef, mountRef, mount, nativeMount, mountType, nativeMountType, store);
        }
        store.tryRemoveComponent(playerRef, riderType);
    }

    private static void clearMountSideState(
            Ref<EntityStore> playerRef,
            Ref<EntityStore> mountRef,
            TameworkMountedGlideComponent mount,
            NPCMountComponent nativeMount,
            ComponentType<EntityStore, TameworkMountedGlideComponent> mountType,
            ComponentType<EntityStore, NPCMountComponent> nativeMountType,
            Store<EntityStore> store) {
        if (mount != null && mountMatchesRider(mount, playerRef, store)) {
            NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
            if (npc != null) {
                restoreNpcRole(mountRef, npc, mount, nativeMount, store);
            }
            store.tryRemoveComponent(mountRef, mountType);
        }
        store.tryRemoveComponent(mountRef, nativeMountType);
        store.ensureAndGetComponent(mountRef, Interactable.getComponentType());
    }

    private static Ref<EntityStore> resolveMountRefFromRider(TameworkMountedGlideRiderComponent rider,
                                                             Store<EntityStore> store) {
        String expectedMountUuid = rider.getMountUuid();
        if (expectedMountUuid == null || expectedMountUuid.isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(expectedMountUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean riderMatchesMount(TameworkMountedGlideRiderComponent rider,
                                             Ref<EntityStore> mountRef,
                                             Store<EntityStore> store) {
        String expectedMountUuid = rider.getMountUuid();
        if (expectedMountUuid == null || expectedMountUuid.isBlank()) {
            return false;
        }
        UUIDComponent uuid = store.getComponent(mountRef, UUIDComponent.getComponentType());
        return uuid != null && uuid.getUuid() != null && expectedMountUuid.equals(uuid.getUuid().toString());
    }

    private static boolean mountMatchesRider(TameworkMountedGlideComponent mount,
                                             Ref<EntityStore> playerRef,
                                             Store<EntityStore> store) {
        String expectedRiderUuid = mount.getRiderUuid();
        if (expectedRiderUuid == null || expectedRiderUuid.isBlank()) {
            return false;
        }
        UUIDComponent uuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
        return uuid != null && uuid.getUuid() != null && expectedRiderUuid.equals(uuid.getUuid().toString());
    }

    private static void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                        @Nonnull NPCEntity npc,
                                        @Nonnull TameworkMountedGlideComponent mount,
                                        @Nonnull Store<EntityStore> store) {
        npc.playAnimation(mountRef, AnimationSlot.Status, null, store);
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        StateSupport support = NpcSupportAccess.state(role, mountRef, store);
        if (mount.getPreviousState().isBlank() || support == null) {
            return;
        }
        if (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(mount.getPreviousState()) == StateSupport.NO_STATE) {
            return;
        }
        support.setState(mountRef, mount.getPreviousState(), mount.getPreviousSubState(), store);
    }

    private static void restoreNpcRole(@Nonnull Ref<EntityStore> mountRef,
                                       @Nonnull NPCEntity npc,
                                       @Nonnull TameworkMountedGlideComponent mount,
                                       NPCMountComponent nativeMount,
                                       @Nonnull Store<EntityStore> store) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Status, null, store);
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        if (nativeMount != null) {
            String state = mount.getPreviousState().isBlank() ? "Idle" : mount.getPreviousState();
            String subState = mount.getPreviousSubState().isBlank() ? null : mount.getPreviousSubState();
            RoleChangeSystem.requestRoleChange(
                    mountRef,
                    role,
                    nativeMount.getOriginalRoleIndex(),
                    false,
                    state,
                    subState,
                    store
            );
            return;
        }
        restoreNpcState(mountRef, npc, mount, store);
    }

    private static void logStaleCleanup(Role role,
                                        TameworkMountedGlideRiderComponent rider,
                                        Player playerComponent,
                                        Ref<EntityStore> mountRef,
                                        TameworkMountedGlideComponent mount,
                                        NPCMountComponent nativeMount,
                                        CleanupState state) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                        "TameworkMount debug: stage=%s role=%s reason=%s " +
                        "riderMountUuid=%s playerMountEntityId=%s mountRefValid=%s hasGlideMount=%s hasNativeMount=%s " +
                        "mountMissing=%s mountInvalid=%s mountComponentMissing=%s nativeMountMissing=%s " +
                        "mountUuidMismatch=%s riderUuidMismatch=%s nativeOwnerMismatch=%s existingMountRider=%s",
                "mounted_glide",
                role == null ? "<null>" : safeValue(role.getRoleName()),
                "stale_rider_cleanup",
                safeValue(rider.getMountUuid()),
                playerComponent.getMountEntityId(),
                mountRef != null && mountRef.isValid(),
                mount != null,
                nativeMount != null,
                state.mountMissing,
                state.mountInvalid,
                state.mountComponentMissing,
                state.nativeMountMissing,
                state.mountUuidMismatch,
                state.riderUuidMismatch,
                state.nativeOwnerMismatch,
                mount == null ? "<none>" : safeValue(mount.getRiderUuid())
        );
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        String text = value.toString();
        return text == null || text.isBlank() ? "<blank>" : text;
    }

    private record CleanupState(
            boolean mountMissing,
            boolean mountInvalid,
            boolean mountComponentMissing,
            boolean nativeMountMissing,
            boolean mountUuidMismatch,
            boolean riderUuidMismatch,
            boolean nativeOwnerMismatch) {
        static CleanupState from(TameworkMountedGlideRiderComponent rider,
                                 Ref<EntityStore> mountRef,
                                 TameworkMountedGlideComponent mount,
                                 NPCMountComponent nativeMount,
                                 Ref<EntityStore> playerRef,
                                 Store<EntityStore> store) {
            boolean hasValidMountRef = mountRef != null && mountRef.isValid();
            boolean mountUuidMismatch = hasValidMountRef && !riderMatchesMount(rider, mountRef, store);
            boolean riderUuidMismatch = mount != null && !mountMatchesRider(mount, playerRef, store);
            boolean nativeOwnerMismatch = hasValidMountRef
                    && nativeMount != null
                    && !nativeMountOwnedByRider(nativeMount, playerRef);
            return new CleanupState(
                    mountRef == null,
                    mountRef != null && !mountRef.isValid(),
                    mount == null,
                    nativeMount == null,
                    mountUuidMismatch,
                    riderUuidMismatch,
                    nativeOwnerMismatch
            );
        }

        boolean stale() {
            return mountMissing
                    || mountInvalid
                    || mountComponentMissing
                    || nativeMountMissing
                    || mountUuidMismatch
                    || riderUuidMismatch
                    || nativeOwnerMismatch;
        }
    }

    private static boolean nativeMountOwnedByRider(NPCMountComponent nativeMount, Ref<EntityStore> playerRef) {
        if (nativeMount.getOwnerPlayerRef() == null) {
            return false;
        }
        Ref<EntityStore> ownerRef = nativeMount.getOwnerPlayerRef().getReference();
        return ownerRef != null && ownerRef.isValid() && ownerRef.equals(playerRef);
    }
}
