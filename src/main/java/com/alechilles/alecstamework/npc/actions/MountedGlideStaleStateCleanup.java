package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
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
            ComponentType<EntityStore, MountedComponent> mountedType,
            Store<EntityStore> store) {
        TameworkMountedGlideRiderComponent rider = store.getComponent(playerRef, riderType);
        if (rider == null || InteractionMountEffects.hasActiveNativeMount(playerComponent)) {
            return;
        }

        Ref<EntityStore> mountRef = resolveMountRefFromRider(rider, store);
        TameworkMountedGlideComponent mount = mountRef == null || !mountRef.isValid()
                ? null
                : store.getComponent(mountRef, mountType);
        MountedComponent mounted = store.getComponent(playerRef, mountedType);
        CleanupState state = CleanupState.from(rider, mountRef, mount, mounted, playerRef, store);
        if (!state.stale()) {
            return;
        }

        logStaleCleanup(role, rider, playerComponent, mountRef, mount, mounted, state);
        if (mountRef != null && mountRef.isValid()) {
            clearMountSideState(playerRef, mountRef, mount, mountType, store);
        }
        if (mountedBelongsToMount(mounted, mountRef)) {
            store.tryRemoveComponent(playerRef, mountedType);
        }
        store.tryRemoveComponent(playerRef, riderType);
    }

    private static void clearMountSideState(
            Ref<EntityStore> playerRef,
            Ref<EntityStore> mountRef,
            TameworkMountedGlideComponent mount,
            ComponentType<EntityStore, TameworkMountedGlideComponent> mountType,
            Store<EntityStore> store) {
        if (mount != null && mountMatchesRider(mount, playerRef, store)) {
            NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
            if (npc != null) {
                restoreNpcState(mountRef, npc, mount, store);
            }
            store.tryRemoveComponent(mountRef, mountType);
        }
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
        if (mount.getPreviousState().isBlank() || role.getStateSupport() == null) {
            return;
        }
        StateSupport support = role.getStateSupport();
        if (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(mount.getPreviousState()) == StateSupport.NO_STATE) {
            return;
        }
        support.setState(mountRef, mount.getPreviousState(), mount.getPreviousSubState(), store);
    }

    private static void logStaleCleanup(Role role,
                                        TameworkMountedGlideRiderComponent rider,
                                        Player playerComponent,
                                        Ref<EntityStore> mountRef,
                                        TameworkMountedGlideComponent mount,
                                        MountedComponent mounted,
                                        CleanupState state) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                        "TameworkMount debug: stage=%s role=%s reason=%s " +
                        "riderMountUuid=%s playerMountEntityId=%s mountRefValid=%s hasGlideMount=%s hasMounted=%s " +
                        "mountMissing=%s mountInvalid=%s mountComponentMissing=%s mountedComponentMissing=%s " +
                        "mountUuidMismatch=%s riderUuidMismatch=%s mountedTargetMismatch=%s existingMountRider=%s",
                "mounted_glide",
                role == null ? "<null>" : safeValue(role.getRoleName()),
                "stale_rider_cleanup",
                safeValue(rider.getMountUuid()),
                playerComponent.getMountEntityId(),
                mountRef != null && mountRef.isValid(),
                mount != null,
                mounted != null,
                state.mountMissing,
                state.mountInvalid,
                state.mountComponentMissing,
                state.mountedComponentMissing,
                state.mountUuidMismatch,
                state.riderUuidMismatch,
                state.mountedTargetMismatch,
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
            boolean mountedComponentMissing,
            boolean mountUuidMismatch,
            boolean riderUuidMismatch,
            boolean mountedTargetMismatch) {
        static CleanupState from(TameworkMountedGlideRiderComponent rider,
                                 Ref<EntityStore> mountRef,
                                 TameworkMountedGlideComponent mount,
                                 MountedComponent mounted,
                                 Ref<EntityStore> playerRef,
                                 Store<EntityStore> store) {
            boolean hasValidMountRef = mountRef != null && mountRef.isValid();
            boolean mountUuidMismatch = hasValidMountRef && !riderMatchesMount(rider, mountRef, store);
            boolean riderUuidMismatch = mount != null && !mountMatchesRider(mount, playerRef, store);
            boolean mountedTargetMismatch = hasValidMountRef && mounted != null && !mountRef.equals(mounted.getMountedToEntity());
            return new CleanupState(
                    mountRef == null,
                    mountRef != null && !mountRef.isValid(),
                    mount == null,
                    mounted == null,
                    mountUuidMismatch,
                    riderUuidMismatch,
                    mountedTargetMismatch
            );
        }

        boolean stale() {
            return mountMissing
                    || mountInvalid
                    || mountComponentMissing
                    || mountedComponentMissing
                    || mountUuidMismatch
                    || riderUuidMismatch
                    || mountedTargetMismatch;
        }
    }

    private static boolean mountedBelongsToMount(MountedComponent mounted, Ref<EntityStore> mountRef) {
        if (mounted == null) {
            return false;
        }
        Ref<EntityStore> mountedTo = mounted.getMountedToEntity();
        return mountedTo == null || !mountedTo.isValid() || mountedTo.equals(mountRef);
    }
}
