package com.alechilles.alecstamework.commands;

import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.assets.patches.AssetPatchReloadMode;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;
import com.alechilles.alecstamework.assets.patches.AssetPatchTargetClassifier;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Regenerates optional patches and reloads generated targets when a safe runtime route exists.
 */
public final class TameworkPatchesReloadCommand extends AbstractPlayerCommand {
    public TameworkPatchesReloadCommand() {
        super("reload", "Reload optional Tamework patches.");
        requirePermission(TameworkConfigPermission.NODE);
        setPermissionGroups("OP", "Admin", "Operator");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getAssetPatchService() == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework patch service is not available."));
            return;
        }
        if (!TameworkConfigPermission.hasAccess(commandContext.sender())) {
            commandContext.sender().sendMessage(Message.raw("You do not have permission to use /tw patches."));
            return;
        }
        AssetPatchService service = plugin.getAssetPatchService();
        commandContext.sender().sendMessage(Message.raw("Reloading Tamework patches..."));
        try {
            AssetPatchStatus status = service.reload();
            if (!hasTameworkConfigTargets(status)) {
                sendPatchStatus(commandContext, status);
                return;
            }
            commandContext.sender().sendMessage(Message.raw(
                    "Generated Tamework config patches changed; reloading Tamework configs..."
            ));
            CompletableFuture.supplyAsync(() -> {
                TwConfigOverrideManager.ReloadResult reloadResult = plugin.reloadConfigOverrides(world);
                int itemLoaded = plugin.reloadItemFeatureConfigs();
                return new ConfigReloadSummary(reloadResult, itemLoaded);
            }).whenComplete((summary, throwable) -> world.execute(() -> {
                sendPatchStatus(commandContext, status);
                if (throwable != null || summary == null) {
                    plugin.getLogger().at(Level.WARNING).withCause(throwable).log(
                            "Async Tamework config reload after patch generation failed."
                    );
                    commandContext.sender().sendMessage(Message.raw(
                            "Generated patches were written, but Tamework config reload failed. See server log."
                    ));
                    return;
                }
                commandContext.sender().sendMessage(Message.raw(
                        "Reloaded generated Tamework config patches. OverridePacks="
                                + summary.reloadResult().getLoadedPacks()
                                + " OverrideDirs="
                                + summary.reloadResult().getLoadedDirectories()
                                + " ItemLoaded="
                                + summary.itemLoaded()
                ));
                if (summary.reloadResult().hasErrors()) {
                    commandContext.sender().sendMessage(Message.raw(
                            "Config reload reported " + summary.reloadResult().getErrors().size()
                                    + " error(s). See server log."
                    ));
                }
            }));
        } catch (RuntimeException ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Tamework asset patch reload command failed.");
            commandContext.sender().sendMessage(Message.raw("Asset patch reload failed. See server log."));
        }
    }

    private static void sendPatchStatus(@Nonnull CommandContext commandContext, @Nonnull AssetPatchStatus status) {
        commandContext.sender().sendMessage(Message.raw(status.summaryLine()));
        if (status.hasFailures()) {
            commandContext.sender().sendMessage(Message.raw("Asset patch reload reported failures. See server log."));
        }
        if (!status.getRestartRequiredTargets().isEmpty()) {
            commandContext.sender().sendMessage(Message.raw(
                    "Some generated patch targets require a config reload or server restart. "
                            + "Use /tw patches status for details."
            ));
        }
    }

    private static boolean hasTameworkConfigTargets(@Nonnull AssetPatchStatus status) {
        for (String target : status.getGeneratedTargets()) {
            if (isTameworkConfigTarget(target)) {
                return true;
            }
        }
        for (String target : status.getRemovedGeneratedTargets()) {
            if (isTameworkConfigTarget(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTameworkConfigTarget(@Nonnull String target) {
        return AssetPatchTargetClassifier.classify(target).reloadMode() == AssetPatchReloadMode.TAMEWORK_CONFIG;
    }

    private record ConfigReloadSummary(@Nonnull TwConfigOverrideManager.ReloadResult reloadResult,
                                       int itemLoaded) {
    }
}
