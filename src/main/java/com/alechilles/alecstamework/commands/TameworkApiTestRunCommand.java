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
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * `/tw api test run [suite] [verbose]`
 */
public final class TameworkApiTestRunCommand extends AbstractPlayerCommand {
    public TameworkApiTestRunCommand() {
        super("run", "Run the live Tamework API self-test suites.");
        requirePermission(TameworkApiTestPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
        setAllowsExtraArguments(true);
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
        ApiSelfTestRunner runner = TameworkApiSelfTestCommandSupport.requireRunner(commandContext, plugin);
        TameworkApi api = TameworkApiSelfTestCommandSupport.requireApi(commandContext, plugin);
        if (manager == null || runner == null || api == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve the player for API self-tests."));
            return;
        }

        ParsedArgs parsed = parse(commandContext);
        if (parsed == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "Usage: /tw api test run [core|profile|command-links|configs|progression|interaction-extensions|policies|diagnostics|all] [verbose]"
            ));
            return;
        }
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
                player,
                report,
                parsed.suite(),
                parsed.verbose()
        );
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
