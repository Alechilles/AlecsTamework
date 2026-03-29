package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureSet;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * `/tw api test status`
 */
public final class TameworkApiTestStatusCommand extends AbstractPlayerCommand {
    public TameworkApiTestStatusCommand() {
        super("status", "Show the current API self-test fixture status.");
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
            TameworkApiSelfTestCommandSupport.sendFixtureStatus(commandContext, null);
            return;
        }
        ApiSelfTestFixtureSet fixtureSet = manager.resolveFixtureSet(player, store, world).orElse(null);
        TameworkApiSelfTestCommandSupport.sendFixtureStatus(commandContext, fixtureSet);
    }
}
