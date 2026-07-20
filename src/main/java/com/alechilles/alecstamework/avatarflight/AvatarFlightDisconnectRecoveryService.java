package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Ends live avatar-flight sessions on disconnect while retaining source-side orphan recovery as fallback. */
public final class AvatarFlightDisconnectRecoveryService {
    private final AvatarFlightMountLifecycleService lifecycle;
    private final AvatarFlightActivator activator;

    public AvatarFlightDisconnectRecoveryService() {
        this(new AvatarFlightMountLifecycleService(), new AvatarFlightActivator());
    }

    AvatarFlightDisconnectRecoveryService(AvatarFlightMountLifecycleService lifecycle,
                                          AvatarFlightActivator activator) {
        this.lifecycle = lifecycle;
        this.activator = activator;
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        PlayerRef playerRef = event == null ? null : event.getPlayerRef();
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid == null) return;

        activator.preparePlayerDisconnect(playerUuid);
        try {
            World world = resolveWorld(playerRef);
            if (world == null) {
                activator.finishPlayerDisconnect(playerUuid);
                return;
            }
            world.execute(() -> recoverOnWorldThread(world, playerUuid));
        } catch (RuntimeException exception) {
            activator.finishPlayerDisconnect(playerUuid);
            logFailure(playerUuid, "schedule_failed", exception);
        }
    }

    private void recoverOnWorldThread(@Nonnull World world, @Nonnull UUID playerUuid) {
        try {
            Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
            if (playerRef == null || !playerRef.isValid()) return;
            Store<EntityStore> store = playerRef.getStore();
            lifecycle.end(
                    store,
                    playerRef,
                    playerUuid,
                    AvatarFlightMountLifecycleService.EndReason.DISCONNECT
            );
        } catch (RuntimeException exception) {
            logFailure(playerUuid, "cleanup_failed", exception);
        } finally {
            activator.finishPlayerDisconnect(playerUuid);
        }
    }

    @Nullable
    private static World resolveWorld(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> reference = playerRef.getReference();
        if (reference == null || !reference.isValid()) return null;
        Store<EntityStore> store = reference.getStore();
        return store.getExternalData() == null ? null : store.getExternalData().getWorld();
    }

    private static void logFailure(@Nonnull UUID playerUuid,
                                   @Nonnull String reason,
                                   @Nonnull RuntimeException exception) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) return;
        plugin.getLogger().at(Level.WARNING).withCause(exception).log(
                "TameworkAvatarFlight disconnect recovery failed: player=%s reason=%s",
                playerUuid,
                reason
        );
    }
}
