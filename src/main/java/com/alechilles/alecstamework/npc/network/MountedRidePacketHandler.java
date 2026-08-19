package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountLifecycleService;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightPacketInputCapture;
import com.alechilles.alecstamework.debug.PlayerInputDebugProbe;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.systems.MountedRideClientAttachment;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.packets.entities.MountMovement;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.SubPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles NPC dismount packets for Tamework rides before vanilla attempts the NPCMountComponent path.
 */
public final class MountedRidePacketHandler implements SubPacketHandler {
    private static final ConcurrentMap<UUID, RideSession> ACTIVE_TAMEWORK_RIDES = new ConcurrentHashMap<>();
    private final IPacketHandler packetHandler;
    private Consumer<ToServerPacket> clientMovementDelegate;
    private Consumer<ToServerPacket> mountMovementDelegate;
    private Consumer<ToServerPacket> mouseInteractionDelegate;
    private Consumer<ToServerPacket> interactionChainsDelegate;
    private final AvatarFlightMountLifecycleService avatarFlightMountLifecycle =
            new AvatarFlightMountLifecycleService();
    private final AvatarFlightPacketInputCapture avatarFlightPacketInputCapture = new AvatarFlightPacketInputCapture();
    private final MountedGlidePacketInputCapture glidePacketInputCapture = new MountedGlidePacketInputCapture();
    private final MountedRideInputApplier inputApplier = new MountedRideInputApplier();
    private long lastInputFailureLogMs;

    public MountedRidePacketHandler(@Nonnull IPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    public static void registerRide(@Nonnull UUID riderEntityUuid, @Nonnull UUID mountUuid, @Nonnull World world) {
        registerRide(null, riderEntityUuid, mountUuid, world);
    }

    public static void registerRide(@Nullable UUID playerUuid,
                                    @Nonnull UUID riderEntityUuid,
                                    @Nonnull UUID mountUuid,
                                    @Nonnull World world) {
        RideSession session = new RideSession(riderEntityUuid, mountUuid, world);
        synchronized (ACTIVE_TAMEWORK_RIDES) {
            replaceRideAlias(riderEntityUuid);
            if (playerUuid != null) {
                replaceRideAlias(playerUuid);
            }
            ACTIVE_TAMEWORK_RIDES.put(riderEntityUuid, session);
            if (playerUuid != null) {
                ACTIVE_TAMEWORK_RIDES.put(playerUuid, session);
            }
        }
    }

    public static void unregisterRide(@Nonnull UUID riderUuid) {
        RideSession session;
        synchronized (ACTIVE_TAMEWORK_RIDES) {
            session = ACTIVE_TAMEWORK_RIDES.get(riderUuid);
            if (session == null) {
                for (RideSession candidate : ACTIVE_TAMEWORK_RIDES.values()) {
                    if (candidate.riderEntityUuid.equals(riderUuid)) {
                        session = candidate;
                        break;
                    }
                }
            }
            if (session != null) {
                removeRideAliases(session);
            } else {
                ACTIVE_TAMEWORK_RIDES.remove(riderUuid);
            }
        }
        if (session != null) {
            session.mailbox.invalidate();
        }
    }

    @Nullable
    static RideSession currentRideSession(@Nonnull UUID alias) {
        synchronized (ACTIVE_TAMEWORK_RIDES) {
            return ACTIVE_TAMEWORK_RIDES.get(alias);
        }
    }

    static boolean unregisterRide(@Nonnull RideSession session) {
        session.mailbox.invalidate();
        boolean removed = false;
        synchronized (ACTIVE_TAMEWORK_RIDES) {
            for (var entry : ACTIVE_TAMEWORK_RIDES.entrySet()) {
                if (entry.getValue() == session) {
                    removed |= ACTIVE_TAMEWORK_RIDES.remove(entry.getKey(), session);
                }
            }
        }
        return removed;
    }

    private static void replaceRideAlias(@Nonnull UUID alias) {
        RideSession existing = ACTIVE_TAMEWORK_RIDES.get(alias);
        if (existing != null) {
            existing.mailbox.invalidate();
            removeRideAliases(existing);
        }
    }

    private static void removeRideAliases(@Nonnull RideSession session) {
        ACTIVE_TAMEWORK_RIDES.forEach((key, value) -> {
            if (value == session) {
                ACTIVE_TAMEWORK_RIDES.remove(key, session);
            }
        });
    }

    public static void unregisterRide(@Nullable String riderUuid) {
        if (riderUuid == null || riderUuid.isBlank()) {
            return;
        }
        try {
            unregisterRide(UUID.fromString(riderUuid));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void registerHandlers() {
        clientMovementDelegate = findRegisteredHandler(ClientMovement.PACKET_ID);
        mountMovementDelegate = findRegisteredHandler(MountMovement.PACKET_ID);
        mouseInteractionDelegate = findRegisteredHandler(MouseInteraction.PACKET_ID);
        interactionChainsDelegate = findRegisteredHandler(SyncInteractionChains.PACKET_ID);
        packetHandler.registerHandler(DismountNPC.PACKET_ID, packet -> handle((DismountNPC) packet));
        packetHandler.registerHandler(ClientMovement.PACKET_ID, packet -> handleClientMovement((ClientMovement) packet));
        packetHandler.registerHandler(MountMovement.PACKET_ID, packet -> handleMountMovement((MountMovement) packet));
        packetHandler.registerHandler(MouseInteraction.PACKET_ID, packet -> handleMouseInteraction((MouseInteraction) packet));
        packetHandler.registerHandler(
                SyncInteractionChains.PACKET_ID,
                packet -> handleInteractionChains((SyncInteractionChains) packet)
        );
    }

    private void handleClientMovement(@Nonnull ClientMovement packet) {
        PlayerInputDebugProbe.logClientMovement(packetHandler.getPlayerRef(), packet);
        avatarFlightPacketInputCapture.capture(packet, packetHandler);
        glidePacketInputCapture.capture(packet, packetHandler);
        if (!tryHandleTameworkClientMovement(packet)) {
            delegate(clientMovementDelegate, packet);
        }
    }

    private void handleMountMovement(@Nonnull MountMovement packet) {
        PlayerInputDebugProbe.logMountMovement(packetHandler.getPlayerRef(), packet);
        glidePacketInputCapture.capture(packet, packetHandler);
        if (!tryHandleTameworkMountMovement(packet)) {
            delegate(mountMovementDelegate, packet);
        }
    }

    private void handleMouseInteraction(@Nonnull MouseInteraction packet) {
        PlayerInputDebugProbe.logMouseInteraction(packetHandler.getPlayerRef(), packet);
        tryHandleTameworkMouseInteraction(packet);
        delegate(mouseInteractionDelegate, packet);
    }

    private void handleInteractionChains(@Nonnull SyncInteractionChains packet) {
        if (containsInitialUse(packet)) {
            PlayerRef playerRef = packetHandler.getPlayerRef();
            Ref<EntityStore> riderRef = playerRef == null ? null : playerRef.getReference();
            if (riderRef != null && riderRef.isValid()) {
                Store<EntityStore> store = riderRef.getStore();
                World world = store == null || store.getExternalData() == null
                        ? null : store.getExternalData().getWorld();
                if (world != null) {
                    world.execute(() -> handleAvatarFlightDismount(riderRef, store));
                }
            }
        }
        delegate(interactionChainsDelegate, packet);
    }

    static boolean containsInitialUse(@Nonnull SyncInteractionChains packet) {
        if (packet.updates == null) return false;
        for (SyncInteractionChain update : packet.updates) {
            if (update != null && update.initial && update.interactionType == InteractionType.Use) {
                return true;
            }
        }
        return false;
    }

    private boolean tryHandleTameworkClientMovement(@Nonnull ClientMovement packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null) {
            return false;
        }
        MountedRideInputMailbox.ClientMovementSnapshot snapshot =
                MountedRideInputMailbox.ClientMovementSnapshot.from(packet);
        if (session.mailbox.offerClientMovement(snapshot)) {
            scheduleRideInput(session);
        }
        return true;
    }

    private boolean tryHandleTameworkMountMovement(@Nonnull MountMovement packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null) {
            return false;
        }
        MountedRideInputMailbox.MountMovementSnapshot snapshot =
                MountedRideInputMailbox.MountMovementSnapshot.from(packet);
        if (session.mailbox.offerMountMovement(snapshot)) {
            scheduleRideInput(session);
        }
        return true;
    }

    private boolean tryHandleTameworkMouseInteraction(@Nonnull MouseInteraction packet) {
        RideSession session = resolveRegisteredRideSession();
        if (session == null) {
            return false;
        }
        MountedRideInputMailbox.MouseInteractionSnapshot snapshot =
                MountedRideInputMailbox.MouseInteractionSnapshot.from(packet);
        if (!snapshot.hasMouseMotion() || !snapshot.hasRelativeMotion()) {
            return false;
        }
        if (session.mailbox.offerMouseInteraction(snapshot)) {
            scheduleRideInput(session);
        }
        return true;
    }

    private void drainRideInput(@Nonnull RideSession session) {
        if (!isCurrentSession(session)) {
            if (session.mailbox.completeDrain()) {
                unregisterRide(session);
            }
            return;
        }
        MountedRideInputMailbox.Batch batch = session.mailbox.takeBatch();
        boolean scheduleFollowUp = false;
        try {
            if (!isCurrentSession(session)) {
                return;
            }
            RideContext current = resolveRideContext(session);
            if (current == null) {
                unregisterRide(session);
                return;
            }
            TameworkRideMountComponent mount = current.mount;
            boolean capturedInput = inputApplier.apply(batch, current.mountRef, current.store, mount);
            if (capturedInput) {
                mount.setLastInputAtMs(System.currentTimeMillis());
                current.store.putComponent(current.mountRef, current.mountType, mount);
            }
        } catch (RuntimeException failure) {
            unregisterRide(session);
            logInputFailure("drain", failure);
            return;
        } finally {
            scheduleFollowUp = session.mailbox.completeDrain();
        }
        if (scheduleFollowUp) {
            if (isCurrentSession(session)) {
                scheduleRideInput(session);
            } else {
                unregisterRide(session);
            }
        }
    }

    private void scheduleRideInput(@Nonnull RideSession session) {
        try {
            session.world.execute(() -> drainRideInput(session));
        } catch (RuntimeException failure) {
            unregisterRide(session);
            logInputFailure("schedule", failure);
        }
    }

    private void handle(@Nonnull DismountNPC packet) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        Ref<EntityStore> riderRef = playerRef.getReference();
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        Store<EntityStore> store = riderRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> handleOnWorldThread(riderRef, store));
    }

    private void handleOnWorldThread(@Nonnull Ref<EntityStore> riderRef, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (handleAvatarFlightDismount(riderRef, store)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkRideRiderComponent> riderType =
                instance == null ? null : instance.getRideRiderComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> mountType =
                instance == null ? null : instance.getRideMountComponentType();
        TameworkRideRiderComponent rider = riderType == null ? null : store.getComponent(riderRef, riderType);
        Ref<EntityStore> mountRef = rider == null ? null : resolveMountRef(rider, store);
        TameworkRideMountComponent mount = mountRef == null || !mountRef.isValid() || mountType == null
                ? null
                : store.getComponent(mountRef, mountType);
        if (mount != null) {
            logDebug(
                    "TameworkRide debug: dismountPacket riderUuid=%s mountUuid=%s",
                    mount.getRiderUuid(),
                    rider == null ? "<none>" : rider.getMountUuid()
            );
            unregisterRide(mount.getRiderUuid());
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                unregisterRide(playerUuid);
            }
            MountedRideClientAttachment.placeRiderAtMountAnchor(store, riderRef, mountRef, mount);
            MountedRideClientAttachment.detach(store, riderRef);
            PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
            if (playerInput != null) {
                playerInput.getMovementUpdateQueue().clear();
            }
            restoreNpcState(mountRef, mount, store);
            if (riderType != null) {
                store.tryRemoveComponent(riderRef, riderType);
            }
            store.tryRemoveComponent(mountRef, mountType);
            store.tryRemoveComponent(riderRef, MountedComponent.getComponentType());
            return;
        }
        if (handleMountedGlideDismount(riderRef, store, instance)) {
            return;
        }
        handleVanillaDismount(riderRef, store, player);
    }

    private boolean handleAvatarFlightDismount(@Nonnull Ref<EntityStore> riderRef,
                                               @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType =
                AvatarFlightMountSessionComponent.getComponentType();
        if (sessionType == null || store.getComponent(riderRef, sessionType) == null) {
            return false;
        }
        UUIDComponent identity = store.getComponent(riderRef, UUIDComponent.getComponentType());
        UUID playerUuid = identity == null ? null : identity.getUuid();
        if (playerUuid != null) {
            avatarFlightMountLifecycle.end(
                    store,
                    riderRef,
                    playerUuid,
                    AvatarFlightMountLifecycleService.EndReason.NORMAL
            );
        }
        return true;
    }

    private boolean handleMountedGlideDismount(@Nonnull Ref<EntityStore> riderRef,
                                               @Nonnull Store<EntityStore> store,
                                               @Nullable Tamework instance) {
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> riderType =
                instance == null ? null : instance.getMountedGlideRiderComponentType();
        ComponentType<EntityStore, TameworkMountedGlideComponent> mountType =
                instance == null ? null : instance.getMountedGlideComponentType();
        TameworkMountedGlideRiderComponent rider = riderType == null ? null : store.getComponent(riderRef, riderType);
        if (rider == null) {
            return false;
        }
        Ref<EntityStore> mountRef = resolveMountedGlideMountRef(rider, store);
        TameworkMountedGlideComponent mount = mountRef == null || !mountRef.isValid() || mountType == null
                ? null
                : store.getComponent(mountRef, mountType);
        logDebug(
                "TameworkGlide debug: dismountPacket riderUuid=%s mountUuid=%s mountedGlide=%s",
                riderRef,
                rider.getMountUuid(),
                mount != null
        );
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player != null) {
            player.setMountEntityId(0);
            MountPlugin.resetOriginalPlayerMovementSettings(riderRef, store);
        }
        if (mountRef != null && mountRef.isValid()) {
            NPCMountComponent nativeMount = store.getComponent(mountRef, NPCMountComponent.getComponentType());
            if (mount != null) {
                restoreNpcRole(mountRef, mount, nativeMount, store);
            }
            removeNativeMountComponent(mountRef, store);
            store.ensureAndGetComponent(mountRef, Interactable.getComponentType());
            if (mountType != null) {
                store.tryRemoveComponent(mountRef, mountType);
            }
        }
        if (riderType != null) {
            store.tryRemoveComponent(riderRef, riderType);
        }
        return true;
    }

    private void removeNativeMountComponent(@Nonnull Ref<EntityStore> mountRef,
                                            @Nonnull Store<EntityStore> store) {
        NPCMountComponent nativeMount = store.getComponent(mountRef, NPCMountComponent.getComponentType());
        if (nativeMount != null) {
            nativeMount.setOwnerPlayerRef(null);
            store.tryRemoveComponent(mountRef, NPCMountComponent.getComponentType());
        }
    }

    @Nullable
    private RideSession resolveRegisteredRideSession() {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        if (playerRef == null) {
            return null;
        }
        UUID playerUuid = playerRef.getUuid();
        return playerUuid == null ? null : ACTIVE_TAMEWORK_RIDES.get(playerUuid);
    }

    private static boolean isCurrentSession(@Nonnull RideSession session) {
        return ACTIVE_TAMEWORK_RIDES.get(session.riderEntityUuid) == session;
    }

    @Nullable
    private RideContext resolveRideContext(@Nonnull RideSession session) {
        Ref<EntityStore> riderRef = session.world.getEntityRef(session.riderEntityUuid);
        Ref<EntityStore> mountRef = session.world.getEntityRef(session.mountUuid);
        if (riderRef == null || mountRef == null || !riderRef.isValid() || !mountRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = riderRef.getStore();
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkRideRiderComponent> riderType =
                instance == null ? null : instance.getRideRiderComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> mountType =
                instance == null ? null : instance.getRideMountComponentType();
        if (riderType == null || mountType == null) {
            return null;
        }
        TameworkRideRiderComponent rider = store.getComponent(riderRef, riderType);
        if (rider == null) {
            return null;
        }
        if (!session.mountUuid.toString().equals(rider.getMountUuid())) {
            return null;
        }
        TameworkRideMountComponent mount = store.getComponent(mountRef, mountType);
        if (mount == null) {
            return null;
        }
        return new RideContext(
                mountRef,
                store,
                mountType,
                mount
        );
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

    private void handleVanillaDismount(@Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Player player) {
        MountedComponent mounted = store.getComponent(riderRef, MountedComponent.getComponentType());
        if (mounted == null) {
            MountPlugin.checkDismountNpc(store, riderRef, player);
            return;
        }
        if (mounted.getControllerType() == MountController.BlockMount) {
            store.tryRemoveComponent(riderRef, MountedComponent.getComponentType());
        }
    }

    private void logDebug(@Nonnull String message, Object... args) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(String.format(message, args));
    }

    private void logInputFailure(@Nonnull String phase, @Nonnull RuntimeException failure) {
        long now = System.currentTimeMillis();
        if (now - lastInputFailureLogMs < 1000) {
            return;
        }
        lastInputFailureLogMs = now;
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        try {
            instance.getLogger().at(Level.WARNING).log(
                    String.format("TameworkRide input %s failed; ride session invalidated: %s", phase, failure)
            );
        } catch (RuntimeException ignored) {
            // Diagnostics must never strand input cleanup or replace the original failure.
        }
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkRideMountComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        applyState(role, mountRef, store, mount.getPreviousState(), mount.getPreviousSubState());
    }

    private void restoreNpcRole(@Nonnull Ref<EntityStore> mountRef,
                                @Nonnull TameworkMountedGlideComponent mount,
                                @Nullable NPCMountComponent nativeMount,
                                @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        if (nativeMount != null) {
            String state = mount.getPreviousState().isBlank() ? "Idle" : mount.getPreviousState();
            String subState = mount.getPreviousSubState().isBlank() ? null : mount.getPreviousSubState();
            RoleChangeSystem.requestRoleChange(
                    mountRef,
                    npc.getRole(),
                    nativeMount.getOriginalRoleIndex(),
                    false,
                    state,
                    subState,
                    store
            );
            return;
        }
        restoreNpcState(mountRef, mount, store);
    }

    private void restoreNpcState(@Nonnull Ref<EntityStore> mountRef,
                                 @Nonnull TameworkMountedGlideComponent mount,
                                 @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(mountRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        applyState(role, mountRef, store, mount.getPreviousState(), mount.getPreviousSubState());
    }

    private void applyState(@Nonnull Role role,
                            @Nonnull Ref<EntityStore> mountRef,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull String state,
                            @Nonnull String subState) {
        StateSupport support = NpcSupportAccess.state(role, mountRef, store);
        if (state.isBlank() || support == null) {
            return;
        }
        if (support.getStateHelper() != null && support.getStateHelper().getStateIndex(state) == StateSupport.NO_STATE) {
            return;
        }
        support.setState(mountRef, state, subState, store);
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
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

    @Nullable
    private Ref<EntityStore> resolveMountedGlideMountRef(@Nonnull TameworkMountedGlideRiderComponent rider,
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

    private record RideContext(
            Ref<EntityStore> mountRef,
            Store<EntityStore> store,
            ComponentType<EntityStore, TameworkRideMountComponent> mountType,
            TameworkRideMountComponent mount
    ) {
    }

    static record RideSession(UUID riderEntityUuid, UUID mountUuid, World world, MountedRideInputMailbox mailbox) {
        private RideSession(UUID riderEntityUuid, UUID mountUuid, World world) {
            this(riderEntityUuid, mountUuid, world, new MountedRideInputMailbox());
        }
    }

}
