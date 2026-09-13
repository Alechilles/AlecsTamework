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
import java.util.ArrayList;
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.tamework.plugin.not.available"));
        }
        return plugin;
    }

    @Nullable
    static ApiSelfTestFixtureManager requireFixtureManager(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        ApiSelfTestFixtureManager manager = plugin.getApiSelfTestFixtureManager();
        if (manager == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.api.self.test.fixtures.are.not.available"));
        }
        return manager;
    }

    @Nullable
    static ApiSelfTestRunner requireRunner(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        ApiSelfTestRunner runner = plugin.getApiSelfTestRunner();
        if (runner == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.api.self.test.runner.is.not.available"));
        }
        return runner;
    }

    @Nullable
    static TameworkApi requireApi(@Nonnull CommandContext commandContext, @Nonnull Tamework plugin) {
        TameworkApi api = plugin.getApi();
        if (api == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.tamework.api.is.not.available"));
        }
        return api;
    }

    static boolean checkPermission(@Nonnull CommandContext commandContext) {
        if (TameworkApiTestPermission.hasAccess(commandContext.sender())) {
            return true;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.you.do.not.have.permission.to.use"));
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.no.api.self.test.fixture.set.found"));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTest.fixture")
                .param("0", fixtureSet.fixtureSetId().toString())
                .param("1", fixtureSet.worldName())
                .param("2", String.valueOf(fixtureSet.toolId()))
                .param("3", fixtureSet.fixtures().keySet().toString()));
        fixtureSet.fixtures().values().forEach(fixture -> commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiSelfTestCommandSupport.npcuuid.owner.role").param("0", String.valueOf(fixture.fixtureKey())).param("1", String.valueOf(fixture.npcUuid())).param("2", String.valueOf(fixture.ownerUuid())).param("3", String.valueOf(fixture.roleId()))));
    }

    static void sendReport(@Nonnull CommandContext commandContext,
                           @Nonnull Tamework plugin,
                           @Nonnull ApiSelfTestRunReport report,
                           @Nonnull ApiSelfTestRunner.Suite suite,
                           boolean verbose) {
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTest.reportTitle"));
        for (var result : report.suites()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTest.suite")
                    .param("0", Message.translation(result.passed()
                            ? "server.tamework.commands.apiTest.pass" : "server.tamework.commands.apiTest.fail"))
                    .param("1", result.suiteName()).param("2", result.passedCount())
                    .param("3", result.assertions().size()));
            for (var assertion : result.assertions()) {
                if (!verbose && assertion.passed()) {
                    continue;
                }
                // Assertion names and bounded failure evidence are diagnostic data.
                String detail = assertion.detail() == null ? "" : assertion.detail()
                        .replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ").trim();
                if (detail.length() > 240) {
                    detail = detail.substring(0, 237) + "...";
                }
                commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTest.assertion")
                        .param("0", Message.translation(assertion.passed()
                                ? "server.tamework.commands.apiTest.pass" : "server.tamework.commands.apiTest.fail"))
                        .param("1", assertion.name()).param("2", detail));
            }
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.apiTest.totals")
                .param("0", report.totalPassed()).param("1", report.totalAssertions())
                .param("2", report.totalFailed()));
        List<String> logLines = ApiSelfTestReportFormatter.format(report, true);
        String suiteName = suite.name().toLowerCase(Locale.ROOT).replace('_', '-');
        plugin.getLogger().at(Level.INFO).log(
                "[tw api test] run suite="
                        + suiteName
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

    /** Runs only fixture-free, non-world-mutating suites when invoked by the server console. */
    @Nullable
    static ApiSelfTestRunReport runConsoleSafe(@Nonnull ApiSelfTestRunner runner,
                                               @Nonnull Tamework plugin,
                                               @Nonnull TameworkApi api,
                                               @Nonnull ApiSelfTestRunner.Suite suite) {
        ApiSelfTestContext context = new ApiSelfTestContext(
                plugin, api, null, null, null, null, null);
        if (suite == ApiSelfTestRunner.Suite.CORE
                || suite == ApiSelfTestRunner.Suite.DIAGNOSTICS
                || suite == ApiSelfTestRunner.Suite.COMMAND_HUD
                || suite == ApiSelfTestRunner.Suite.HYDRAGON_INTEGRATIONS) {
            return runner.run(context, suite);
        }
        if (suite != ApiSelfTestRunner.Suite.ALL) return null;
        ArrayList<com.alechilles.alecstamework.selftest.ApiSelfTestSuiteResult> suites =
                new ArrayList<>();
        suites.addAll(runner.run(context, ApiSelfTestRunner.Suite.CORE).suites());
        suites.addAll(runner.run(context, ApiSelfTestRunner.Suite.COMMAND_HUD).suites());
        suites.addAll(runner.run(context, ApiSelfTestRunner.Suite.DIAGNOSTICS).suites());
        suites.addAll(runner.run(context, ApiSelfTestRunner.Suite.HYDRAGON_INTEGRATIONS).suites());
        return new ApiSelfTestRunReport(suites);
    }
}
