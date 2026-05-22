package com.alechilles.alecstamework.commands;

import java.util.Locale;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.selftest.AssetPatchSelfTestPack;
import com.alechilles.alecstamework.assets.patches.selftest.AssetPatchSelfTestResult;
import com.alechilles.alecstamework.assets.patches.selftest.AssetPatchSelfTestRunner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Runs isolated optional asset patch fixtures through the live patch reload path.
 */
public final class TameworkPatchesSelfTestCommand extends AbstractPlayerCommand {
    public TameworkPatchesSelfTestCommand() {
        super("selftest", "Run optional Tamework patch self-tests.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
        setAllowsExtraArguments(true);
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
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw patches."));
            return;
        }
        AssetPatchService service = plugin.getAssetPatchService();
        AssetPatchSelfTestPack pack = plugin.getAssetPatchSelfTestPack();
        if (service == null || pack == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework patch self-test service is not available."));
            return;
        }
        boolean cleanup = isCleanup(commandContext.getInputString());
        AssetPatchSelfTestRunner runner = new AssetPatchSelfTestRunner(pack, service, plugin.getLogger());
        commandContext.sender().sendMessage(Message.raw(cleanup
                ? "Cleaning Tamework patch self-test fixtures..."
                : "Running Tamework patch self-test fixtures..."
        ));
        try {
            AssetPatchSelfTestResult result = cleanup ? runner.cleanup() : runner.run();
            sendResult(commandContext, result);
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Tamework patch self-test command failed.");
            commandContext.sender().sendMessage(Message.raw("Tamework patch self-test failed. See server log."));
        }
    }

    private static boolean isCleanup(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String[] tokens = input.trim().split("\\s+");
        for (int i = 3; i < tokens.length; i++) {
            if ("cleanup".equals(tokens[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void sendResult(@Nonnull CommandContext commandContext,
                                   @Nonnull AssetPatchSelfTestResult result) {
        commandContext.sender().sendMessage(Message.raw(result.summaryLine()));
        for (AssetPatchSelfTestResult.CaseResult caseResult : result.cases()) {
            commandContext.sender().sendMessage(Message.raw(formatCase(caseResult)));
        }
        if (!result.passed()) {
            commandContext.sender().sendMessage(Message.raw("Patch self-test failures were logged with per-target diagnostics."));
        } else if (result.restartRequiredCount() > 0) {
            commandContext.sender().sendMessage(Message.raw("Some targets generated successfully but require a server restart."));
        }
    }

    @Nonnull
    private static String formatCase(@Nonnull AssetPatchSelfTestResult.CaseResult caseResult) {
        String generated = caseResult.generated() ? "generated successfully" : "not generated";
        String reload = switch (caseResult.reloadOutcome()) {
            case HOT_RELOADED -> "hot-reloaded successfully";
            case RESTART_REQUIRED -> "restart required";
            case NOT_APPLICABLE -> "reload not applicable";
            case FAILED -> "reload failed";
        };
        String status = caseResult.passed() ? "passed" : "failed";
        return caseResult.id()
                + ": "
                + status
                + " ("
                + generated
                + ", "
                + reload
                + ") "
                + caseResult.detail();
    }
}
