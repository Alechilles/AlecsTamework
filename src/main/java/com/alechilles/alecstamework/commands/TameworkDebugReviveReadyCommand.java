package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Makes every dead generic linked companion owned by the caller revive-ready. */
public final class TameworkDebugReviveReadyCommand extends AbstractPlayerCommand {
    @Nullable
    private final ReviveReadyOwnerBatchService service;

    public TameworkDebugReviveReadyCommand(
            @Nullable PublicPersistenceQueries queries,
            @Nullable PublicPersistenceOperations operations
    ) {
        super(
                "reviveready",
                "server.tamework.commands.debugReviveReady.description"
        );
        service = queries == null || operations == null
                ? null
                : new ReviveReadyOwnerBatchService(queries, operations);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        if (service == null) {
            send(context, Message.translation("server.tamework.commands.debugReviveReady.generic.persistence.is.not.available"));
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            send(context, Message.translation("server.tamework.commands.debugReviveReady.could.not.resolve.your.player.uuid"));
            return;
        }
        ReviveReadyOwnerBatchService.UpdateResult result =
                service.markAll(new OwnerId(playerUuid));
        if (result.projectionLagging()) {
            send(context, Message.translation("server.tamework.commands.debugReviveReady.linked.companion.data.is.still.updating.retry"));
            return;
        }
        if (result.total() == 0) {
            send(context, Message.translation("server.tamework.commands.debugReviveReady.no.dead.linked.companions.were.found.for"));
            return;
        }
        send(context, Message.translation("server.tamework.commands.debugReviveReady.dead.linked.companion.revive.ready.update.total").param("0", String.valueOf(result.total())).param("1", String.valueOf(result.accepted())).param("2", String.valueOf(result.alreadyReady())).param("3", String.valueOf(result.rejected())));
    }

    private void send(@Nonnull CommandContext context, @Nonnull Message message) {
        context.sender().sendMessage(message);
    }
}
