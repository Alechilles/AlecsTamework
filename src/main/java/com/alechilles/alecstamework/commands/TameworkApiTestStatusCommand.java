package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureSet;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * `/tw api test status`
 */
public final class TameworkApiTestStatusCommand extends AbstractTameworkServerCommand {
    public TameworkApiTestStatusCommand() {
        super("status", "Show the current API self-test fixture status.");
        requirePermission(TameworkApiTestPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
    }

    @Override
    protected void executeServer(@Nonnull CommandContext commandContext) {
        if (!TameworkApiSelfTestCommandSupport.checkPermission(commandContext)) {
            return;
        }
        Tamework plugin = TameworkApiSelfTestCommandSupport.requirePlugin(commandContext);
        if (plugin == null) {
            return;
        }
        if (!commandContext.isPlayer()) {
            commandContext.sender().sendMessage(Message.raw(
                    "API self-test status: runner="
                            + (plugin.getApiSelfTestRunner() == null ? "unavailable" : "ready")
                            + ", fixtures=player-scoped, prepare/reset=player-scoped. "
                            + "Use /tw api test run all for the console-safe aggregate."));
            return;
        }
        ApiSelfTestFixtureManager manager = TameworkApiSelfTestCommandSupport.requireFixtureManager(commandContext, plugin);
        if (manager == null) {
            return;
        }
        PlayerRef playerRef = commandContext.senderAs(PlayerRef.class);
        Ref<EntityStore> ref = commandContext.senderAsPlayerRef();
        World world = playerRef == null ? null : Universe.get().getWorld(playerRef.getWorldUuid());
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (ref == null || !ref.isValid() || store == null || world == null) {
            TameworkApiSelfTestCommandSupport.sendFixtureStatus(commandContext, null);
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
