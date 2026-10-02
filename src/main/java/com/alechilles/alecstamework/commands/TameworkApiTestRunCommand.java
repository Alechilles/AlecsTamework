package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.selftest.ApiSelfTestContext;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureSet;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunReport;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * `/tw api test run [suite] [verbose]`
 */
public final class TameworkApiTestRunCommand extends AbstractTameworkServerCommand {
    public TameworkApiTestRunCommand() {
        super("run", "server.tamework.commands.apiTestRun.description");
        requirePermission(TameworkApiTestPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
        setAllowsExtraArguments(true);
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
        ApiSelfTestRunner runner = TameworkApiSelfTestCommandSupport.requireRunner(commandContext, plugin);
        TameworkApi api = TameworkApiSelfTestCommandSupport.requireApi(commandContext, plugin);
        if (runner == null || api == null) {
            return;
        }

        ParsedArgs parsed = parse(commandContext);
        if (parsed == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTestRun.usage.tw.api.test.run.core.profile"));
            return;
        }
        World world = senderWorld(commandContext);
        if (world == null) {
            runWithoutPlayer(commandContext, plugin, runner, api, parsed);
            return;
        }
        // The command runs off the world thread; the player and the fixtures are only readable on it.
        Ref<EntityStore> ref = commandContext.senderAsPlayerRef();
        world.execute(() -> runAsPlayer(commandContext, plugin, runner, api, parsed, world, ref));
    }

    private static void runWithoutPlayer(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin,
                                         @Nonnull ApiSelfTestRunner runner, @Nonnull TameworkApi api,
                                         @Nonnull ParsedArgs parsed) {
        ApiSelfTestRunReport report = TameworkApiSelfTestCommandSupport.runConsoleSafe(
                runner, plugin, api, parsed.suite());
        if (report == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTestRun.that.suite.requires.prepared.in.world.fixtures"));
            return;
        }
        TameworkApiSelfTestCommandSupport.sendReport(
                commandContext, plugin, report, parsed.suite(), parsed.verbose());
    }

    private static void runAsPlayer(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin,
                                    @Nonnull ApiSelfTestRunner runner, @Nonnull TameworkApi api,
                                    @Nonnull ParsedArgs parsed, @Nonnull World world,
                                    @Nonnull Ref<EntityStore> ref) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null) {
            runWithoutPlayer(commandContext, plugin, runner, api, parsed);
            return;
        }
        ApiSelfTestFixtureManager manager = TameworkApiSelfTestCommandSupport.requireFixtureManager(
                commandContext, plugin);
        if (manager == null) return;
        ApiSelfTestFixtureSet fixtureSet = manager.resolveFixtureSet(player, store, world).orElse(null);
        ApiSelfTestContext context = TameworkApiSelfTestCommandSupport.buildContext(
                plugin, api, player, store, ref, world, fixtureSet);
        ApiSelfTestRunReport report = runner.run(context, parsed.suite());
        TameworkApiSelfTestCommandSupport.sendReport(
                commandContext, plugin, report, parsed.suite(), parsed.verbose());
    }

    /** The sending player's world, or null for the console or a player whose world is gone. */
    @Nullable
    private static World senderWorld(@Nonnull CommandContext context) {
        if (!context.isPlayer()) return null;
        PlayerRef playerRef = context.senderAs(PlayerRef.class);
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (playerRef == null || ref == null) return null;
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        return world == null || world.getEntityStore() == null ? null : world;
    }

    private ParsedArgs parse(@Nonnull CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return new ParsedArgs(ApiSelfTestRunner.Suite.ALL, false);
        }
        String[] tokens = input.trim().split("\\s+");
        ApiSelfTestRunner.Suite suite = ApiSelfTestRunner.Suite.ALL;
        boolean verbose = false;
        for (int i = 4; i < tokens.length; i++) {
            String token = tokens[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if ("verbose".equals(normalized)) {
                verbose = true;
                continue;
            }
            try {
                suite = ApiSelfTestRunner.Suite.parse(normalized);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        return new ParsedArgs(suite, verbose);
    }

    private record ParsedArgs(@Nonnull ApiSelfTestRunner.Suite suite, boolean verbose) {
    }

}
