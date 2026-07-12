package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * `/tw api test prepare`
 */
public final class TameworkApiTestPrepareCommand extends AbstractPlayerCommand {
    public TameworkApiTestPrepareCommand() {
        super("prepare", "Provision the bundled API self-test fixtures.");
        requirePermission(TameworkApiTestPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        if (!TameworkApiSelfTestCommandSupport.checkPermission(commandContext)) {
            return;
        }
        Tamework plugin = TameworkApiSelfTestCommandSupport.requirePlugin(commandContext);
        if (plugin == null) {
            return;
        }
        ApiSelfTestFixtureManager manager = TameworkApiSelfTestCommandSupport.requireFixtureManager(commandContext, plugin);
        if (manager == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve the player for fixture setup."));
            return;
        }
        manager.prepareAsync(player, store, ref, world).whenComplete((result, failure) -> {
            LeaseBoundWorldDispatcher.execute(world, () -> {
                if (failure != null || result == null) {
                    commandContext.sender().sendMessage(Message.raw(
                            "Failed to prepare API self-test fixtures safely."
                    ));
                    return;
                }
                commandContext.sender().sendMessage(Message.raw(result.summary()));
                if (result.fixtureSet() != null) {
                    TameworkApiSelfTestCommandSupport.sendFixtureStatus(
                            commandContext, result.fixtureSet()
                    );
                }
            });
        });
    }
}
