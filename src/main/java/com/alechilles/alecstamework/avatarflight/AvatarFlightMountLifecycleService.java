package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.AvatarFlightMountingSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Orchestrates transactional start, rollback, and idempotent end of NPC-backed avatar flight. */
public final class AvatarFlightMountLifecycleService {
    private final AvatarFlightMountPreflight preflight;
    private final AvatarFlightNpcParkingService parking;
    private final AvatarFlightActivator activator;

    public AvatarFlightMountLifecycleService() {
        this(new AvatarFlightMountPreflight(), new AvatarFlightNpcParkingService(), new AvatarFlightActivator());
    }

    AvatarFlightMountLifecycleService(AvatarFlightMountPreflight preflight,
                                      AvatarFlightNpcParkingService parking,
                                      AvatarFlightActivator activator) {
        this.preflight = preflight;
        this.parking = parking;
        this.activator = activator;
    }

    @Nonnull
    public Result start(@Nonnull Store<EntityStore> store,
                        @Nullable Ref<EntityStore> npcRef,
                        @Nullable Ref<EntityStore> playerRef,
                        @Nullable Role role,
                        @Nullable String configId) {
        AvatarFlightMountPreflight.Result checked = preflight.prepare(store, npcRef, playerRef, role, configId);
        if (!checked.ok() || checked.prepared() == null || npcRef == null || playerRef == null || role == null) {
            return Result.fail("Avatar-flight mount rejected: " + checked.reason());
        }
        AvatarFlightMountPreflight.Prepared prepared = checked.prepared();
        AvatarFlightSourceComponent source = parking.capture(
                store, npcRef, role, prepared.playerUuid(), prepared.originalRoleIndex());
        if (source == null) {
            return Result.fail("Avatar-flight mount rejected: source_snapshot_failed");
        }
        AvatarFlightMountSessionComponent session = createSession(store, npcRef, prepared);
        if (session == null) {
            return Result.fail("Avatar-flight mount rejected: session_snapshot_failed");
        }
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType =
                AvatarFlightMountSessionComponent.getComponentType();
        ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType = AvatarFlightSourceComponent.getComponentType();
        if (sessionType == null || sourceType == null) {
            return Result.fail("Avatar-flight mount rejected: component_type_unavailable");
        }
        store.putComponent(playerRef, sessionType, session);
        store.putComponent(npcRef, sourceType, source);
        movePlayerToSource(store, playerRef, npcRef);
        Map<String, String> sourceAttachmentIds =
                CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        AvatarFlightActivator.Result enabled = activator.enable(
                store, playerRef, UUID.fromString(prepared.playerUuid()),
                prepared.config().getId(), sourceAttachmentIds);
        if (!enabled.ok()) {
            rollback(store, npcRef, playerRef, sessionType, sourceType, source, prepared.playerUuid());
            return Result.fail(enabled.message());
        }
        if (!parking.park(store, npcRef, playerRef, role, source, prepared.emptyRoleIndex())) {
            rollback(store, npcRef, playerRef, sessionType, sourceType, source, prepared.playerUuid());
            return Result.fail("Avatar-flight mount failed while parking source NPC.");
        }
        session.setPhase(AvatarFlightMountPhase.ACTIVE);
        source.setPhase(AvatarFlightMountPhase.ACTIVE);
        log(Level.INFO, "started", session, null);
        return Result.ok("Avatar flight mounted with config=" + safeConfigId(prepared.config()));
    }

    @Nonnull
    public Result end(@Nonnull Store<EntityStore> store,
                      @Nonnull Ref<EntityStore> playerRef,
                      @Nonnull UUID playerUuid,
                      @Nonnull EndReason reason) {
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType =
                AvatarFlightMountSessionComponent.getComponentType();
        AvatarFlightMountSessionComponent session = sessionType == null
                ? null : store.getComponent(playerRef, sessionType);
        if (session == null) {
            AvatarFlightActivator.Result disabled = activator.disable(store, playerRef, playerUuid);
            return new Result(disabled.ok(), disabled.message());
        }
        if (isRestorationInProgress(session)) {
            return Result.ok("Avatar-flight mount cleanup already in progress.");
        }
        session.setPhase(AvatarFlightMountPhase.RESTORING);
        AvatarFlightActivator.Result disabled = activator.disable(store, playerRef, playerUuid);
        restoreSourceAndPlacePlayer(store, playerRef, session, reason);
        store.tryRemoveComponent(playerRef, sessionType);
        log(reason == EndReason.NORMAL ? Level.INFO : Level.WARNING, "ended", session, reason);
        if (disabled.ok()) {
            return Result.ok(disabled.message());
        }
        return Result.ok("Avatar-flight mount restored; player flight state was already absent. "
                + disabled.message());
    }

    public boolean hasMountSession(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> type =
                AvatarFlightMountSessionComponent.getComponentType();
        return type != null && store.getComponent(playerRef, type) != null;
    }

    /**
     * A persisted RESTORING marker belongs only to the process that started the teardown.
     * A later process must resume it so a crash cannot leave the player transformed forever.
     */
    static boolean isRestorationInProgress(@Nonnull AvatarFlightMountSessionComponent session) {
        return session.getPhase() == AvatarFlightMountPhase.RESTORING
                && AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch());
    }

    private AvatarFlightMountSessionComponent createSession(Store<EntityStore> store,
                                                            Ref<EntityStore> npcRef,
                                                            AvatarFlightMountPreflight.Prepared prepared) {
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null || transform.getRotation() == null) return null;
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                prepared.npcUuid(),
                store.getExternalData().getWorld().getName(),
                prepared.config().getId(),
                System.currentTimeMillis()
        );
        session.captureOrigin(transform.getPosition().x, transform.getPosition().y,
                transform.getPosition().z, transform.getRotation().yaw());
        session.captureLastSafeGround(transform.getPosition().x, transform.getPosition().y,
                transform.getPosition().z, transform.getRotation().yaw());
        return session;
    }

    private void rollback(Store<EntityStore> store,
                          Ref<EntityStore> npcRef,
                          Ref<EntityStore> playerRef,
                          ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType,
                          ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType,
                          AvatarFlightSourceComponent source,
                          String playerUuid) {
        activator.disable(store, playerRef, UUID.fromString(playerUuid));
        parking.restore(store, npcRef, source,
                source.getOriginX(), source.getOriginY(), source.getOriginZ(), source.getOriginYaw());
        store.tryRemoveComponent(npcRef, sourceType);
        store.tryRemoveComponent(playerRef, sessionType);
    }

    private void restoreSourceAndPlacePlayer(Store<EntityStore> store,
                                             Ref<EntityStore> playerRef,
                                             AvatarFlightMountSessionComponent session,
                                             EndReason reason) {
        Ref<EntityStore> sourceRef = resolve(store, session.getSourceNpcUuid());
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(session.getConfigId());
        AvatarFlightMountingSettings settings = config == null
                ? new AvatarFlightMountingSettings()
                : config.getMounting();
        boolean useLastSafe = reason == EndReason.NORMAL
                && settings.isRestoreNpcAtLastSafeGround()
                && session.isLastSafeGroundValid();
        double x = useLastSafe ? session.getLastSafeGroundX() : session.getOriginX();
        double y = useLastSafe ? session.getLastSafeGroundY() : session.getOriginY();
        double z = useLastSafe ? session.getLastSafeGroundZ() : session.getOriginZ();
        float yaw = useLastSafe ? session.getLastSafeGroundYaw() : session.getOriginYaw();
        if (sourceRef != null && sourceRef.isValid()) {
            ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType =
                    AvatarFlightSourceComponent.getComponentType();
            AvatarFlightSourceComponent source = sourceType == null ? null : store.getComponent(sourceRef, sourceType);
            if (source != null) {
                source.setPhase(AvatarFlightMountPhase.RESTORING);
                parking.restore(store, sourceRef, source, x, y, z, yaw);
                store.tryRemoveComponent(sourceRef, sourceType);
            }
        }
        placePlayer(store, playerRef, x, y, z, yaw, settings.getPlayerDismountOffset());
    }

    private static void movePlayerToSource(Store<EntityStore> store,
                                           Ref<EntityStore> playerRef,
                                           Ref<EntityStore> sourceRef) {
        Player player = store.getComponent(playerRef, Player.getComponentType());
        TransformComponent source = store.getComponent(sourceRef, TransformComponent.getComponentType());
        if (player == null || source == null || source.getPosition() == null) return;
        player.moveTo(playerRef, source.getPosition().x, source.getPosition().y, source.getPosition().z, store);
    }

    private static void placePlayer(Store<EntityStore> store,
                                    Ref<EntityStore> playerRef,
                                    double x,
                                    double y,
                                    double z,
                                    float yaw,
                                    double offset) {
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player == null) return;
        double targetX = x - Math.sin(yaw) * offset;
        double targetZ = z - Math.cos(yaw) * offset;
        player.moveTo(playerRef, targetX, y, targetZ, store);
    }

    @Nullable
    private static Ref<EntityStore> resolve(Store<EntityStore> store, String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void log(Level level,
                            String action,
                            AvatarFlightMountSessionComponent session,
                            @Nullable EndReason reason) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) return;
        plugin.getLogger().at(level).log(
                "TameworkAvatarFlight mount %s: playerSource=%s config=%s phase=%s reason=%s",
                action,
                session.getSourceNpcUuid(),
                session.getConfigId(),
                session.getPhaseName(),
                reason == null ? "<none>" : reason
        );
    }

    private static String safeConfigId(TwAvatarFlightConfig config) {
        return config.getId() == null || config.getId().isBlank() ? "<default>" : config.getId();
    }

    public enum EndReason {
        NORMAL,
        COMMAND,
        PLAYER_DEAD,
        SOURCE_MISSING,
        CONFIG_UNAVAILABLE,
        WORLD_TRANSFER,
        DISCONNECT,
        SERVER_RESTART,
        ORPHAN_RECOVERY
    }

    public record Result(boolean ok, @Nonnull String message) {
        static Result ok(String message) { return new Result(true, message); }
        static Result fail(String message) { return new Result(false, message); }
    }
}
