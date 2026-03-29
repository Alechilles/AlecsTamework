package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.selftest.ApiSelfTestContext;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureSet;
import com.alechilles.alecstamework.selftest.ApiSelfTestReportFormatter;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunReport;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class TameworkApiSelfTestCommandSupport {
    private TameworkApiSelfTestCommandSupport() {
    }

    @Nullable
    static Tamework requirePlugin(@Nonnull CommandContext commandContext) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
        }
        return plugin;
    }

    @Nullable
    static ApiSelfTestFixtureManager requireFixtureManager(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        ApiSelfTestFixtureManager manager = plugin.getApiSelfTestFixtureManager();
        if (manager == null) {
            commandContext.sender().sendMessage(Message.raw("API self-test fixtures are not available."));
        }
        return manager;
    }

    @Nullable
    static ApiSelfTestRunner requireRunner(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        ApiSelfTestRunner runner = plugin.getApiSelfTestRunner();
        if (runner == null) {
            commandContext.sender().sendMessage(Message.raw("API self-test runner is not available."));
        }
        return runner;
    }

    @Nullable
    static TameworkApi requireApi(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        TameworkApi api = plugin.getApi();
        if (api == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework API is not available."));
        }
        return api;
    }

    static boolean checkPermission(@Nonnull CommandContext commandContext) {
        if (TameworkApiTestPermission.hasAccess(commandContext.sender())) {
            return true;
        }
        commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw api test."));
        return false;
    }

    @Nonnull
    static ApiSelfTestContext buildContext(@Nonnull Tamework plugin,
                                           @Nonnull TameworkApi api,
                                           @Nonnull Player player,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> ref,
                                           @Nonnull World world,
                                           @Nullable ApiSelfTestFixtureSet fixtureSet) {
        return new ApiSelfTestContext(plugin, api, player, store, ref, world, fixtureSet);
    }

    static void sendFixtureStatus(@Nonnull CommandContext commandContext, @Nullable ApiSelfTestFixtureSet fixtureSet) {
        if (fixtureSet == null) {
            commandContext.sender().sendMessage(Message.raw("No API self-test fixture set found in this world."));
            return;
        }
        commandContext.sender().sendMessage(Message.raw(ApiSelfTestReportFormatter.formatFixtureSet(fixtureSet)));
        fixtureSet.fixtures().values().forEach(fixture -> commandContext.sender().sendMessage(Message.raw(
                " - "
                        + fixture.fixtureKey()
                        + ": npcUuid="
                        + fixture.npcUuid()
                        + ", owner="
                        + fixture.ownerUuid()
                        + ", role="
                        + fixture.roleId()
        )));
    }

    static void sendReport(@Nonnull CommandContext commandContext,
                           @Nonnull Tamework plugin,
                           @Nonnull Player player,
                           @Nonnull ApiSelfTestRunReport report,
                           @Nonnull ApiSelfTestRunner.Suite suite,
                           boolean verbose) {
        List<String> lines = ApiSelfTestReportFormatter.format(report, verbose);
        for (String line : lines) {
            commandContext.sender().sendMessage(Message.raw(line));
        }
        List<String> logLines = ApiSelfTestReportFormatter.format(report, true);
        String suiteName = suite.name().toLowerCase(Locale.ROOT).replace('_', '-');
        plugin.getLogger().at(Level.INFO).log(
                "[tw api test] run suite="
                        + suiteName
                        + " requester="
                        + player.getUuid()
                        + " chatVerbose="
                        + verbose
                        + " totals="
                        + report.totalPassed()
                        + "/"
                        + report.totalAssertions()
        );
        for (String line : logLines) {
            plugin.getLogger().at(Level.INFO).log("[tw api test] " + line);
        }
    }
}
