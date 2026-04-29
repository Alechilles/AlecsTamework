package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Reloads Tamework item feature configs from disk.
 */
public final class TameworkReloadConfigCommand extends AbstractPlayerCommand {
    private static final String COMMAND_NAME = "/tw reloadconfig";

    public TameworkReloadConfigCommand() {
        super("reloadconfig", "Reload Tamework item feature configs.");
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
        TameworkTelemetryEvents telemetryEvents = plugin.getTelemetryEvents();
        long startedAtNanos = System.nanoTime();
        telemetryEvents.recordUsage(
                "reload_config_command_used",
                reloadContext("Triggered by " + COMMAND_NAME + ".")
                        .detail("source", "command")
                        .build()
        );
        commandContext.sender().sendMessage(Message.raw("Reloading Tamework configs..."));
        CompletableFuture.supplyAsync(() -> {
            TameworkSettingsStore.invalidateRuntimeGlobalOverridesCache();
            TwConfigOverrideManager.ReloadResult reloadResult = plugin.reloadConfigOverrides(world);
            int loaded = plugin.reloadItemFeatureConfigs();
            int totalSpawners = plugin.getItemFeatureRegistry() != null
                    ? plugin.getItemFeatureRegistry().snapshot().size()
                    : 0;
            int totalNaming = plugin.getNameItemRegistry() != null
                    ? plugin.getNameItemRegistry().snapshot().size()
                    : 0;
            return new ReloadSummary(reloadResult, loaded, totalSpawners, totalNaming);
        }).whenComplete((summary, throwable) -> world.execute(() -> {
            int durationMs = telemetryEvents.elapsedMillis(startedAtNanos);
            if (throwable != null || summary == null) {
                plugin.getLogger().at(Level.WARNING).withCause(throwable).log("Async /tw reloadconfig failed.");
                telemetryEvents.recordLifecycle(
                        "reload_config",
                        durationMs,
                        false,
                        reloadContext("Async " + COMMAND_NAME + " failed.")
                                .detail("source", "command")
                                .detail("result", "failed")
                                .build()
                );
                telemetryEvents.recordPerformance(
                        "reload_config_duration",
                        durationMs,
                        (double) durationMs,
                        reloadContext("Failed " + COMMAND_NAME + " duration.")
                                .detail("result", "failed")
                                .build()
                );
                telemetryEvents.recordError(
                        "reload_config_failed",
                        throwable,
                        reloadContext("Async " + COMMAND_NAME + " failed.")
                                .detail("source", "command")
                                .detail("result", "failed")
                                .build()
                );
                commandContext.sender().sendMessage(Message.raw("Reload failed. See server log for details."));
                return;
            }
            plugin.applyDebugConfigDefaults();
            String result = TameworkReloadConfigTelemetry.reloadResultLabel(summary.reloadResult());
            boolean partial = "partial".equals(result);
            telemetryEvents.recordLifecycle(
                    "reload_config",
                    durationMs,
                    !partial,
                    reloadSummaryContext("Reloaded Tamework configs via " + COMMAND_NAME + ".", summary, result)
                            .detail("source", "command")
                            .build()
            );
            telemetryEvents.recordPerformance(
                    "reload_config_duration",
                    durationMs,
                    (double) durationMs,
                    reloadSummaryContext("Completed " + COMMAND_NAME + " duration.", summary, result).build()
            );
            if (partial) {
                telemetryEvents.recordError(
                        "reload_config_override_errors",
                        null,
                        reloadSummaryContext(
                                "Reloaded with " + summary.reloadResult().getErrors().size() + " override error(s).",
                                summary,
                                "partial"
                        ).build()
                );
            }
            commandContext.sender().sendMessage(Message.raw(
                    "Reloaded Tamework configs. OverridePacks=" + summary.reloadResult().getLoadedPacks()
                            + " OverrideDirs=" + summary.reloadResult().getLoadedDirectories()
                            + " ItemLoaded=" + summary.itemLoaded()
                            + " Spawners=" + summary.totalSpawners()
                            + " Naming=" + summary.totalNaming()
                            + " DebugDefaults=applied"
            ));
            if (summary.reloadResult().hasErrors()) {
                commandContext.sender().sendMessage(Message.raw(
                        "Override reload reported " + summary.reloadResult().getErrors().size()
                                + " error(s). See server log."
                ));
            }
        }));
    }

    @Nonnull
    private static TelemetryEventContext.Builder reloadContext(@Nonnull String detail) {
        return TameworkTelemetryEvents.commandContext(COMMAND_NAME, "config", "reload_config")
                .operation("reload")
                .detail(detail);
    }

    @Nonnull
    private static TelemetryEventContext.Builder reloadSummaryContext(@Nonnull String detail,
                                                                     @Nonnull ReloadSummary summary,
                                                                     @Nonnull String result) {
        return reloadContext(detail)
                .detail("result", result)
                .detail("itemConfigCount", summary.itemLoaded())
                .detail("totalSpawners", summary.totalSpawners())
                .detail("totalNaming", summary.totalNaming())
                .detail("overridePacks", summary.reloadResult().getLoadedPacks())
                .detail("overrideDirectories", summary.reloadResult().getLoadedDirectories())
                .detail("overrideErrorCount", summary.reloadResult().getErrors().size());
    }

    private record ReloadSummary(@Nonnull TwConfigOverrideManager.ReloadResult reloadResult,
                                 int itemLoaded,
                                 int totalSpawners,
                                 int totalNaming) {
    }
}
