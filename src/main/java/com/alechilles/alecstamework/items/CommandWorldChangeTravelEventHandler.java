package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Routes connection and world-add events into exact player-arrival companion travel intents. */
public final class CommandWorldChangeTravelEventHandler {
    private final CommandItemFeatureHandler commandItems;
    private final CommandWorldJoinSessionTracker sessions;
    private final CommandWorldChangeArrivalTracker arrivals;

    public CommandWorldChangeTravelEventHandler(@Nonnull CommandItemFeatureHandler commandItems) {
        this(commandItems, new CommandWorldJoinSessionTracker(), new CommandWorldChangeArrivalTracker());
    }

    CommandWorldChangeTravelEventHandler(
            @Nonnull CommandItemFeatureHandler commandItems,
            @Nonnull CommandWorldJoinSessionTracker sessions,
            @Nonnull CommandWorldChangeArrivalTracker arrivals
    ) {
        this.commandItems = Objects.requireNonNull(commandItems, "commandItems");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
    }

    public void onPlayerConnect(@Nullable PlayerConnectEvent event) {
        sessions.onConnected(event == null || event.getPlayerRef() == null
                ? null : event.getPlayerRef().getUuid());
    }

    public void onPlayerDisconnect(@Nullable PlayerDisconnectEvent event) {
        UUID playerUuid = event == null || event.getPlayerRef() == null
                ? null : event.getPlayerRef().getUuid();
        sessions.onDisconnected(playerUuid);
        arrivals.clear(playerUuid);
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
        arrivals.mark(playerUuid, world.getName());
    }

    void onPlayerAdded(@Nullable World world, @Nullable UUID playerUuid) {
        if (world == null || playerUuid == null
                || !arrivals.consume(playerUuid, world.getName())) {
            return;
        }
        commandItems.queueWorldChangeTravelRelocationsForPlayerUuid(world, playerUuid);
    }
}
