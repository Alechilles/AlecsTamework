package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.Vector2i;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.SubPacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Supplements active mounted glide sessions with packet-level movement and mouse-look snapshots.
 */
public final class MountedGlidePacketHandler implements SubPacketHandler {
    private static final ConcurrentMap<UUID, GlideSession> ACTIVE_GLIDES = new ConcurrentHashMap<>();
    private static final float MOUSE_LOOK_DEGREES_PER_UNIT = 0.14323945f;
    private static final float MIN_CAMERA_PITCH_DEGREES = -85.0f;
    private static final float MAX_CAMERA_PITCH_DEGREES = 85.0f;

    private final IPacketHandler packetHandler;
    private Consumer<ToServerPacket> clientMovementDelegate;
    private Consumer<ToServerPacket> mouseInteractionDelegate;

    public MountedGlidePacketHandler(@Nonnull IPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    public static void registerGlide(@Nonnull UUID riderEntityUuid, @Nonnull UUID mountUuid, @Nonnull World world) {
        registerGlide(null, riderEntityUuid, mountUuid, world);
    }

    public static void registerGlide(@Nullable UUID playerUuid,
                                     @Nonnull UUID riderEntityUuid,
                                     @Nonnull UUID mountUuid,
                                     @Nonnull World world) {
        GlideSession session = new GlideSession(riderEntityUuid, mountUuid, world);
        ACTIVE_GLIDES.put(riderEntityUuid, session);
        if (playerUuid != null) {
            ACTIVE_GLIDES.put(playerUuid, session);
        }
    }

    public static void unregisterGlide(@Nonnull UUID riderUuid) {
        ACTIVE_GLIDES.remove(riderUuid);
        ACTIVE_GLIDES.forEach((key, session) -> {
            if (session.riderEntityUuid.equals(riderUuid) || session.mountUuid.equals(riderUuid)) {
                ACTIVE_GLIDES.remove(key, session);
            }
        });
    }

    public static void unregisterGlide(@Nullable String riderUuid) {
        if (riderUuid == null || riderUuid.isBlank()) {
            return;
        }
        try {
            unregisterGlide(UUID.fromString(riderUuid));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void registerHandlers() {
        clientMovementDelegate = findRegisteredHandler(ClientMovement.PACKET_ID);
        mouseInteractionDelegate = findRegisteredHandler(MouseInteraction.PACKET_ID);
        packetHandler.registerHandler(ClientMovement.PACKET_ID, packet -> handleClientMovement((ClientMovement) packet));
        packetHandler.registerHandler(MouseInteraction.PACKET_ID, packet -> handleMouseInteraction((MouseInteraction) packet));
    }

    private void handleClientMovement(@Nonnull ClientMovement packet) {
        if (!tryCaptureClientMovement(packet)) {
            delegate(clientMovementDelegate, packet);
        }
    }

    private void handleMouseInteraction(@Nonnull MouseInteraction packet) {
        tryCaptureMouseMotion(packet);
        delegate(mouseInteractionDelegate, packet);
    }

    private boolean tryCaptureClientMovement(@Nonnull ClientMovement packet) {
        GlideSession session = resolveRegisteredGlideSession();
        if (session == null) {
            return false;
        }
        session.world.execute(() -> {
            GlideContext current = resolveGlideContext(session);
            if (current == null) {
                unregisterGlide(session.riderEntityUuid);
                return;
            }
            TameworkMountedGlideComponent mount = current.store.getComponent(current.mountRef, current.mountType);
            if (mount == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (packet.bodyOrientation != null) {
                mount.captureLookRotation(
                        radiansToDegrees(packet.bodyOrientation.yaw),
                        radiansToDegrees(packet.bodyOrientation.pitch),
                        radiansToDegrees(packet.bodyOrientation.roll),
                        now
                );
            }
            if (packet.lookOrientation != null) {
                mount.captureLookRotation(
                        radiansToDegrees(packet.lookOrientation.yaw),
                        radiansToDegrees(packet.lookOrientation.pitch),
                        radiansToDegrees(packet.lookOrientation.roll),
                        now
                );
            }
            if (packet.riderMovementStates != null) {
                captureStates(mount, packet.riderMovementStates, now);
            } else if (packet.movementStates != null) {
                captureStates(mount, packet.movementStates, now);
            }
            if (packet.wishMovement != null) {
                mount.captureMovementIntent(packet.wishMovement.z, packet.wishMovement.x, now);
            } else if (packet.velocity != null) {
                mount.captureMovementIntent(packet.velocity.z, packet.velocity.x, now);
            }
            current.store.putComponent(current.mountRef, current.mountType, mount);
        });
        return true;
    }

    private boolean tryCaptureMouseMotion(@Nonnull MouseInteraction packet) {
        GlideSession session = resolveRegisteredGlideSession();
        if (session == null || packet.mouseMotion == null || packet.mouseMotion.relativeMotion == null) {
            return false;
        }
        Vector2i relativeMotion = packet.mouseMotion.relativeMotion;
        session.world.execute(() -> {
            GlideContext current = resolveGlideContext(session);
            if (current == null) {
                unregisterGlide(session.riderEntityUuid);
                return;
            }
            TameworkMountedGlideComponent mount = current.store.getComponent(current.mountRef, current.mountType);
            if (mount == null) {
                return;
            }
            float yaw = mount.getLookYawDegrees() - relativeMotion.x * MOUSE_LOOK_DEGREES_PER_UNIT;
            float pitch = clampFloat(
                    mount.getLookPitchDegrees() - relativeMotion.y * MOUSE_LOOK_DEGREES_PER_UNIT,
                    MIN_CAMERA_PITCH_DEGREES,
                    MAX_CAMERA_PITCH_DEGREES
            );
            mount.captureLookRotation(yaw, pitch, 0.0f, System.currentTimeMillis());
            current.store.putComponent(current.mountRef, current.mountType, mount);
        });
        return true;
    }

    private void captureStates(@Nonnull TameworkMountedGlideComponent mount,
                               @Nonnull MovementStates states,
                               long now) {
        mount.captureControls(
                states.jumping || states.swimJumping,
                states.sprinting || states.running,
                states.crouching || states.forcedCrouching,
                now
        );
    }

    @Nullable
    private GlideSession resolveRegisteredGlideSession() {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        if (playerRef == null) {
            return null;
        }
        UUID playerUuid = playerRef.getUuid();
        return playerUuid == null ? null : ACTIVE_GLIDES.get(playerUuid);
    }

    @Nullable
    private GlideContext resolveGlideContext(@Nonnull GlideSession session) {
        Ref<EntityStore> riderRef = session.world.getEntityRef(session.riderEntityUuid);
        Ref<EntityStore> mountRef = session.world.getEntityRef(session.mountUuid);
        if (riderRef == null || mountRef == null || !riderRef.isValid() || !mountRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = riderRef.getStore();
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType =
                instance == null ? null : instance.getMountedGlideRiderComponentType();
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountType =
                instance == null ? null : instance.getMountedGlideComponentType();
        if (riderType == null || mountType == null) {
            return null;
        }
        TameworkMountedGlideRiderComponent rider = store.getComponent(riderRef, riderType);
        if (rider == null || !session.mountUuid.toString().equals(rider.getMountUuid())) {
            return null;
        }
        if (store.getComponent(mountRef, mountType) == null) {
            return null;
        }
        return new GlideContext(riderRef, mountRef, store, mountType);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Consumer<ToServerPacket> findRegisteredHandler(int packetId) {
        Class<?> current = packetHandler.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField("handlers");
                field.setAccessible(true);
                Consumer<ToServerPacket>[] handlers = (Consumer<ToServerPacket>[]) field.get(packetHandler);
                return handlers != null && packetId >= 0 && packetId < handlers.length ? handlers[packetId] : null;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private void delegate(@Nullable Consumer<ToServerPacket> delegate, @Nonnull ToServerPacket packet) {
        if (delegate != null) {
            delegate.accept(packet);
        }
    }

    private static float radiansToDegrees(float radians) {
        return (float) Math.toDegrees(radians);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record GlideContext(
            Ref<EntityStore> riderRef,
            Ref<EntityStore> mountRef,
            Store<EntityStore> store,
            ComponentType<EntityStore, TameworkMountedGlideComponent> mountType
    ) {
    }

    private record GlideSession(UUID riderEntityUuid, UUID mountUuid, World world) {
    }
}
