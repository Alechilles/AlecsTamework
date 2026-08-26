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
        super("run", "Run the live Tamework API self-test suites.");
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
            commandContext.sender().sendMessage(Message.raw(
                    "Usage: /tw api test run [core|profile|command-links|configs|progression|interaction-extensions|trait-effects|policies|command-ui|command-hud|diagnostics|hydragon-integrations|all] [verbose]"
            ));
            return;
        }
        PlayerExecution playerExecution = resolvePlayerExecution(commandContext);
        if (playerExecution == null) {
            ApiSelfTestRunReport report = TameworkApiSelfTestCommandSupport.runConsoleSafe(
                    runner, plugin, api, parsed.suite());
            if (report == null) {
                commandContext.sender().sendMessage(Message.raw(
                        "That suite requires prepared in-world fixtures. From the console use "
                                + "core, command-hud, diagnostics, hydragon-integrations, or all for the read-only aggregate."));
                return;
            }
            TameworkApiSelfTestCommandSupport.sendReport(
                    commandContext, plugin, report, parsed.suite(), parsed.verbose());
            return;
        }
        ApiSelfTestFixtureManager manager = TameworkApiSelfTestCommandSupport.requireFixtureManager(
                commandContext, plugin);
        if (manager == null) return;
        Player player = playerExecution.player();
        Store<EntityStore> store = playerExecution.store();
        Ref<EntityStore> ref = playerExecution.ref();
        World world = playerExecution.world();
        ApiSelfTestFixtureSet fixtureSet = manager.resolveFixtureSet(player, store, world).orElse(null);
        ApiSelfTestContext context = TameworkApiSelfTestCommandSupport.buildContext(
                plugin,
                api,
                player,
                store,
                ref,
                world,
                fixtureSet
        );
        ApiSelfTestRunReport report = runner.run(context, parsed.suite());
        TameworkApiSelfTestCommandSupport.sendReport(
                commandContext,
                plugin,
                report,
                parsed.suite(),
                parsed.verbose()
        );
    }

    @Nullable
    private static PlayerExecution resolvePlayerExecution(@Nonnull CommandContext context) {
        if (!context.isPlayer()) return null;
        PlayerRef playerRef = context.senderAs(PlayerRef.class);
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (playerRef == null || ref == null || !ref.isValid()) return null;
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null || world.getEntityStore() == null) return null;
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) return null;
        Player player = store.getComponent(ref, Player.getComponentType());
        return player == null ? null : new PlayerExecution(player, store, ref, world);
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

    private record PlayerExecution(@Nonnull Player player,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull World world) { }
}
