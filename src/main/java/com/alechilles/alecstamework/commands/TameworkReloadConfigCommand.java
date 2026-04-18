package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
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
        telemetryEvents.recordUsage("reload_config_command_used", "Triggered by /tw reloadconfig.");
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
                telemetryEvents.recordLifecycle("reload_config", durationMs, false, "Async /tw reloadconfig failed.");
                telemetryEvents.recordPerformance("reload_config_duration", durationMs, (double) durationMs, "Failed /tw reloadconfig duration.");
                telemetryEvents.recordError("reload_config_failed", throwable, "Async /tw reloadconfig failed.");
                commandContext.sender().sendMessage(Message.raw("Reload failed. See server log for details."));
                return;
            }
            plugin.applyDebugConfigDefaults();
            telemetryEvents.recordLifecycle("reload_config", durationMs, true, "Reloaded Tamework configs via /tw reloadconfig.");
            telemetryEvents.recordPerformance("reload_config_duration", durationMs, (double) durationMs, "Successful /tw reloadconfig duration.");
            if (summary.reloadResult().hasErrors()) {
                telemetryEvents.recordError(
                        "reload_config_override_errors",
                        null,
                        "Reloaded with " + summary.reloadResult().getErrors().size() + " override error(s)."
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

    private record ReloadSummary(@Nonnull TwConfigOverrideManager.ReloadResult reloadResult,
                                 int itemLoaded,
                                 int totalSpawners,
                                 int totalNaming) {
    }
}
