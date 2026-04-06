package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Marks a player's dead linked NPC snapshots as immediately respawn-ready.
 */
public final class TameworkDebugReviveReadyCommand extends AbstractPlayerCommand {

    public TameworkDebugReviveReadyCommand() {
        super("debugreviveready", "Mark your dead linked NPCs as revive-ready.");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }
        CommandLinkedNpcDeathService deathService = plugin.getCommandLinkedNpcDeathService();
        if (deathService == null) {
            commandContext.sender().sendMessage(Message.raw("Dead linked-NPC tracking is not available."));
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        if (playerUuid == null) {
            commandContext.sender().sendMessage(Message.raw("Could not resolve your player UUID."));
            return;
        }
        CommandLinkedNpcDeathService.RespawnReadyUpdateResult result =
                deathService.markOwnerDeadSnapshotsRespawnReady(playerUuid);
        if (result.totalOwned() <= 0) {
            commandContext.sender().sendMessage(Message.raw("No dead linked NPCs were found for your player."));
            return;
        }
        commandContext.sender().sendMessage(Message.raw(
                "Dead linked NPC respawn-ready update: total="
                        + result.totalOwned()
                        + ", markedReady="
                        + result.markedReady()
                        + ", alreadyReady="
                        + result.alreadyReady()
        ));
    }
}
