package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Vector3d;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures native mounted client input into Tamework mounted-glide state without consuming the packet.
 */
final class MountedGlidePacketInputCapture {
    private long lastClientMovementDebugMs;
    private long lastMountMovementDebugMs;

    void capture(@Nonnull ClientMovement packet, @Nonnull IPacketHandler packetHandler) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        Ref<EntityStore> riderRef = playerRef.getReference();
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        Store<EntityStore> store = riderRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> captureOnWorld(packet, riderRef, store));
    }

    void capture(@Nonnull MountMovement packet, @Nonnull IPacketHandler packetHandler) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        Ref<EntityStore> riderRef = playerRef.getReference();
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        Store<EntityStore> store = riderRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> captureOnWorld(packet, riderRef, store));
    }

    private void captureOnWorld(@Nonnull ClientMovement packet,
                                @Nonnull Ref<EntityStore> riderRef,
                                @Nonnull Store<EntityStore> store) {
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType =
                instance == null ? null : instance.getMountedGlideRiderComponentType();
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountType =
                instance == null ? null : instance.getMountedGlideComponentType();
        if (riderType == null || mountType == null) {
            return;
        }
        TameworkMountedGlideRiderComponent rider = store.getComponent(riderRef, riderType);
        if (rider == null) {
            return;
        }
        Ref<EntityStore> mountRef = resolveMountRef(rider, store);
        if (mountRef == null || !mountRef.isValid() || !stillOwnedByRider(riderRef, mountRef, store)) {
            return;
        }
        TameworkMountedGlideComponent mount = store.getComponent(mountRef, mountType);
        if (mount == null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean captured = false;
        MovementStates states = packet.riderMovementStates != null ? packet.riderMovementStates : packet.movementStates;
        if (states != null) {
            captureStates(mount, states, now);
            captured = true;
        }
        if (packet.lookOrientation != null) {
            captureLook(mount, packet.lookOrientation, now);
            captured = true;
        } else if (packet.bodyOrientation != null) {
            captureLook(mount, packet.bodyOrientation, now);
            captured = true;
        }
        if (packet.wishMovement != null) {
            captureWish(mount, packet.wishMovement.x, packet.wishMovement.z, now);
            captured = true;
        } else if (packet.velocity != null) {
            captureVelocityMovement(mount, packet.velocity.x, packet.velocity.z, now);
            captured = true;
        }
        if (!captured) {
            return;
        }
        mount.setLastPacketInputAtMs(now);
        mount.setLastInputAtMs(now);
        logDebug(packet, mount);
        store.putComponent(mountRef, mountType, mount);
    }

    private void captureOnWorld(@Nonnull MountMovement packet,
                                @Nonnull Ref<EntityStore> riderRef,
                                @Nonnull Store<EntityStore> store) {
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType =
                instance == null ? null : instance.getMountedGlideRiderComponentType();
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountType =
                instance == null ? null : instance.getMountedGlideComponentType();
        if (riderType == null || mountType == null) {
            return;
        }
        TameworkMountedGlideRiderComponent rider = store.getComponent(riderRef, riderType);
        if (rider == null) {
            return;
        }
        Ref<EntityStore> mountRef = resolveMountRef(rider, store);
        if (mountRef == null || !mountRef.isValid() || !stillOwnedByRider(riderRef, mountRef, store)) {
            return;
        }
        TameworkMountedGlideComponent mount = store.getComponent(mountRef, mountType);
        if (mount == null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean captured = false;
        if (packet.movementStates != null) {
            captureStates(mount, packet.movementStates, now);
            captured = true;
        }
        if (packet.bodyOrientation != null) {
            captureLook(mount, packet.bodyOrientation, now);
            captured = true;
        }
        if (!captured) {
            return;
        }
        mount.setLastPacketInputAtMs(now);
        mount.setLastInputAtMs(now);
        logDebug(packet, mount);
        store.putComponent(mountRef, mountType, mount);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
                                             @Nonnull Store<EntityStore> store) {
        if (rider.getMountUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(rider.getMountUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean stillOwnedByRider(@Nonnull Ref<EntityStore> riderRef,
                                      @Nonnull Ref<EntityStore> mountRef,
                                      @Nonnull Store<EntityStore> store) {
        NPCMountComponent nativeMount = store.getComponent(mountRef, NPCMountComponent.getComponentType());
        if (nativeMount == null || nativeMount.getOwnerPlayerRef() == null) {
            return false;
        }
        Ref<EntityStore> ownerRef = nativeMount.getOwnerPlayerRef().getReference();
        return ownerRef != null && ownerRef.isValid() && ownerRef.equals(riderRef);
    }

    private void captureStates(@Nonnull TameworkMountedGlideComponent mount,
                               @Nullable MovementStates states,
                               long now) {
        if (states == null) {
            mount.captureControls(false, false, false, now);
            return;
        }
        mount.captureControls(
                states.jumping || states.swimJumping,
                states.sprinting || states.running,
                states.crouching || states.forcedCrouching,
                now
        );
    }

    private void captureLook(@Nonnull TameworkMountedGlideComponent mount,
                             @Nonnull com.hypixel.hytale.protocol.Direction direction,
                             long now) {
        mount.captureLookRotation(
                (float) Math.toDegrees(direction.yaw),
                (float) Math.toDegrees(direction.pitch),
                (float) Math.toDegrees(direction.roll),
                now
        );
    }

    private void captureWish(@Nonnull TameworkMountedGlideComponent mount,
                             double wishX,
                             double wishZ,
                             long now) {
        double horizontalLength = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (horizontalLength <= 0.0001) {
            mount.setHasMovementIntent(false);
            mount.setForwardIntent(0.0);
            mount.setStrafeIntent(0.0);
            mount.setLastInputAtMs(now);
            return;
        }
        double scale = horizontalLength > 1.0 ? 1.0 / horizontalLength : 1.0;
        mount.captureMovementIntent(wishZ * scale, wishX * scale, now);
    }

    private void captureVelocityMovement(@Nonnull TameworkMountedGlideComponent mount,
                                         double worldX,
                                         double worldZ,
                                         long now) {
        double horizontalLength = Math.sqrt(worldX * worldX + worldZ * worldZ);
        if (horizontalLength <= 0.0001) {
            return;
        }
        double normalizedX = worldX / horizontalLength;
        double normalizedZ = worldZ / horizontalLength;
        double yaw = mount.hasLookRotation() ? Math.toRadians(mount.getLookYawDegrees()) : 0.0;
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double strafe = clamp(normalizedX * rightX + normalizedZ * rightZ, -1.0, 1.0);
        double forward = clamp(normalizedX * forwardX + normalizedZ * forwardZ, -1.0, 1.0);
        mount.captureMovementIntent(forward, strafe, now);
    }

    private void logDebug(@Nonnull ClientMovement packet, @Nonnull TameworkMountedGlideComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastClientMovementDebugMs < 1000) {
            return;
        }
        lastClientMovementDebugMs = now;
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkGlide debug: packet source=clientMovement packetWish=%s packetVelocity=%s body=%s look=%s " +
                        "movementStates=%s riderStates=%s snapshotIntent=%s/%s hasIntent=%s snapshotLook=%s/%s hasLook=%s " +
                        "jump=%s sprint=%s crouch=%s lastPacketInputAtMs=%s",
                formatPosition(packet.wishMovement),
                formatVector(packet.velocity),
                formatDirection(packet.bodyOrientation),
                formatDirection(packet.lookOrientation),
                formatStates(packet.movementStates),
                formatStates(packet.riderMovementStates),
                mount.getForwardIntent(),
                mount.getStrafeIntent(),
                mount.hasMovementIntent(),
                mount.getLookYawDegrees(),
                mount.getLookPitchDegrees(),
                mount.hasLookRotation(),
                mount.isJumpHeld(),
                mount.isSprinting(),
                mount.isCrouching(),
                mount.getLastPacketInputAtMs()
        );
    }

    private void logDebug(@Nonnull MountMovement packet, @Nonnull TameworkMountedGlideComponent mount) {
        long now = System.currentTimeMillis();
        if (now - lastMountMovementDebugMs < 1000) {
            return;
        }
        lastMountMovementDebugMs = now;
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkGlide debug: packet source=mountMovement absolute=%s body=%s movementStates=%s " +
                        "snapshotLook=%s/%s hasLook=%s jump=%s sprint=%s crouch=%s lastPacketInputAtMs=%s",
                formatPosition(packet.absolutePosition),
                formatDirection(packet.bodyOrientation),
                formatStates(packet.movementStates),
                mount.getLookYawDegrees(),
                mount.getLookPitchDegrees(),
                mount.hasLookRotation(),
                mount.isJumpHeld(),
                mount.isSprinting(),
                mount.isCrouching(),
                mount.getLastPacketInputAtMs()
        );
    }

    @Nonnull
    private String formatPosition(@Nullable Position position) {
        return position == null ? "<none>" : position.x + "/" + position.y + "/" + position.z;
    }

    @Nonnull
    private String formatVector(@Nullable Vector3d vector) {
        return vector == null ? "<none>" : vector.x + "/" + vector.y + "/" + vector.z;
    }

    @Nonnull
    private String formatDirection(@Nullable com.hypixel.hytale.protocol.Direction direction) {
        return direction == null ? "<none>" : direction.yaw + "/" + direction.pitch + "/" + direction.roll;
    }

    @Nonnull
    private String formatStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return "jump=" + states.jumping +
                ",crouch=" + states.crouching +
                ",fly=" + states.flying +
                ",sprint=" + states.sprinting +
                ",ground=" + states.onGround +
                ",run=" + states.running +
                ",mounting=" + states.mounting;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
