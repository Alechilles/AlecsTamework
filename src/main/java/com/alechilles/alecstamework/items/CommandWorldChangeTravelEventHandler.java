package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Routes connection and world-ready events into command-companion travel without recalling on login. */
public final class CommandWorldChangeTravelEventHandler {
    private static final long WORLD_CHANGE_SETTLE_DELAY_MS = 250L;

    private final CommandItemFeatureHandler commandItems;
    private final CommandWorldJoinSessionTracker sessions;

    public CommandWorldChangeTravelEventHandler(@Nonnull CommandItemFeatureHandler commandItems) {
        this(commandItems, new CommandWorldJoinSessionTracker());
    }

    CommandWorldChangeTravelEventHandler(
            @Nonnull CommandItemFeatureHandler commandItems,
            @Nonnull CommandWorldJoinSessionTracker sessions
    ) {
        this.commandItems = Objects.requireNonNull(commandItems, "commandItems");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public void onPlayerConnect(@Nullable PlayerConnectEvent event) {
        sessions.onConnected(event == null || event.getPlayerRef() == null
                ? null : event.getPlayerRef().getUuid());
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        sessions.onDisconnected(event == null || event.getPlayerRef() == null
                ? null : event.getPlayerRef().getUuid());
    }

    public void onAddPlayerToWorld(@Nullable AddPlayerToWorldEvent event) {
        if (event == null || event.getWorld() == null || event.getHolder() == null) {
            return;
        }
        commandItems.canonicalizePlayerCommandInventory(event.getHolder());
        PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        World world = event.getWorld();
        if (!sessions.isWorldChange(playerUuid)) {
            world.execute(() -> commandItems.dismountPlayerAfterWorldJoin(world, playerUuid));
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> commandItems
                        .queueWorldChangeTravelRelocationsForPlayerUuid(world, playerUuid)),
                CompletableFuture.delayedExecutor(
                        WORLD_CHANGE_SETTLE_DELAY_MS, TimeUnit.MILLISECONDS
                )
        );
    }
}
