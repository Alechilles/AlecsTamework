package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.system.UpdateLocationSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tracks safe restoration positions, voluntary dismount input, and broken rider-side sessions. */
public final class AvatarFlightMountSessionSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType;
    private final ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType;
    private final ComponentType<EntityStore, AvatarFlightInputComponent> inputType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final AvatarFlightMountLifecycleService lifecycle = new AvatarFlightMountLifecycleService();
    private final AvatarFlightSourceFollowService sourceFollow = new AvatarFlightSourceFollowService();
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, AvatarFlightMovementSystem.class),
            new SystemDependency<>(Order.BEFORE, UpdateLocationSystems.TickingSystem.class)
    );

    public AvatarFlightMountSessionSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType,
            @Nonnull ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType,
            @Nonnull ComponentType<EntityStore, AvatarFlightInputComponent> inputType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType) {
        this.sessionType = sessionType;
        this.sourceType = sourceType;
        this.inputType = inputType;
        this.uuidType = uuidType;
        this.transformType = transformType;
        this.deathType = deathType;
        this.query = Query.and(sessionType, uuidType, transformType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> playerRef = chunk.getReferenceTo(index);
        AvatarFlightMountSessionComponent session = chunk.getComponent(index, sessionType);
        AvatarFlightInputComponent input = chunk.getComponent(index, inputType);
        UUIDComponent playerUuid = chunk.getComponent(index, uuidType);
        TransformComponent transform = chunk.getComponent(index, transformType);
        if (playerRef == null || session == null || playerUuid == null
                || playerUuid.getUuid() == null || transform == null) {
            return;
        }
        if (input == null) {
            scheduleEnd(playerRef, playerUuid.getUuid(), session,
                    AvatarFlightMountLifecycleService.EndReason.ORPHAN_RECOVERY, commandBuffer);
            return;
        }
        AvatarFlightMountLifecycleService.EndReason forced = forcedEndReason(
                store, playerRef, session, playerUuid.getUuid());
        if (forced != null) {
            scheduleEnd(playerRef, playerUuid.getUuid(), session, forced, commandBuffer);
            return;
        }
        syncSourceToRider(store, playerRef, session, transform, commandBuffer);
        if (input.isOnGround() && transform.getPosition() != null && transform.getRotation() != null) {
            session.captureLastSafeGround(
                    transform.getPosition().x,
                    transform.getPosition().y,
                    transform.getPosition().z,
                    transform.getRotation().yaw()
            );
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(session.getConfigId());
        AvatarFlightDismountPolicy.Decision decision = AvatarFlightDismountPolicy.evaluate(
                System.currentTimeMillis(),
                session.getDismountHoldStartedAtMs(),
                input.isOnGround(),
                input.isCrouching(),
                input.getForwardAxis(),
                config.getInput().getForwardDeadzone(),
                config.getMounting()
        );
        session.setDismountHoldStartedAtMs(decision.holdStartedAtMs());
        if (decision.suppressLaunch()) {
            suppressLaunch(input);
        }
        if (decision.complete()) {
            scheduleEnd(playerRef, playerUuid.getUuid(), session,
                    AvatarFlightMountLifecycleService.EndReason.NORMAL, commandBuffer);
        }
    }

    @Nullable
    private AvatarFlightMountLifecycleService.EndReason forcedEndReason(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            AvatarFlightMountSessionComponent session,
            UUID playerUuid) {
        if (!AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch())) {
            return AvatarFlightMountLifecycleService.EndReason.SERVER_RESTART;
        }
        if (AvatarFlightMountLifecycleService.isRestorationInProgress(session)) return null;
        if (store.getComponent(playerRef, deathType) != null) {
            return AvatarFlightMountLifecycleService.EndReason.PLAYER_DEAD;
        }
        if (!store.getExternalData().getWorld().getName().equals(session.getSourceWorld())) {
            return AvatarFlightMountLifecycleService.EndReason.WORLD_TRANSFER;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(session.getConfigId());
        if (config == null || !config.isEnabled()) {
            return AvatarFlightMountLifecycleService.EndReason.CONFIG_UNAVAILABLE;
        }
        Ref<EntityStore> sourceRef = resolve(store, session.getSourceNpcUuid());
        if (sourceRef == null || !sourceRef.isValid()) {
            return AvatarFlightMountLifecycleService.EndReason.SOURCE_MISSING;
        }
        AvatarFlightSourceComponent source = store.getComponent(sourceRef, sourceType);
        if (source == null || !playerUuid.toString().equals(source.getRiderUuid())) {
            return AvatarFlightMountLifecycleService.EndReason.SOURCE_MISSING;
        }
        if (!AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())) {
            return AvatarFlightMountLifecycleService.EndReason.SERVER_RESTART;
        }
        return null;
    }

    private void syncSourceToRider(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            AvatarFlightMountSessionComponent session,
            TransformComponent riderTransform,
            CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> sourceRef = resolve(store, session.getSourceNpcUuid());
        if (sourceRef == null || !sourceRef.isValid()) {
            return;
        }
        TransformComponent sourceTransform = commandBuffer.getComponent(
                sourceRef, transformType
        );
        double riderY = riderTransform.getPosition() == null
                ? Double.NaN : riderTransform.getPosition().y;
        if (!sourceFollow.sync(riderTransform, sourceTransform)) {
            return;
        }
        if (riderTransform.getPosition().y != riderY) {
            commandBuffer.putComponent(playerRef, transformType, riderTransform);
        }
        commandBuffer.putComponent(sourceRef, transformType, sourceTransform);
    }

    private void scheduleEnd(Ref<EntityStore> playerRef,
                             UUID playerUuid,
                             AvatarFlightMountSessionComponent session,
                             AvatarFlightMountLifecycleService.EndReason reason,
                             CommandBuffer<EntityStore> commandBuffer) {
        if (session.getPhase() == AvatarFlightMountPhase.RESTORING) return;
        commandBuffer.run(bufferStore -> lifecycle.end(bufferStore, playerRef, playerUuid, reason));
    }

    private static void suppressLaunch(AvatarFlightInputComponent input) {
        input.setLaunchChargeStartedAtMs(0L);
        input.setLaunchReleasedAtMs(0L);
        input.setLaunchHoldMs(0L);
    }

    @Nullable
    private static Ref<EntityStore> resolve(Store<EntityStore> store, String uuid) {
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull @Override public Query<EntityStore> getQuery() { return query; }
    @Nonnull @Override public Set<Dependency<EntityStore>> getDependencies() { return dependencies; }
}
