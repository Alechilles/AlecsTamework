package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Toggles exact-model visualization proxies for loaded natural spawn beacons.
 */
public final class TameworkShowSpawnBeaconsCommand extends AbstractPlayerCommand {
    private final SpawnBeaconVisualizationService visualizationService;

    public TameworkShowSpawnBeaconsCommand(
            @Nonnull SpawnBeaconVisualizationService visualizationService
    ) {
        super("spawn-beacons", "server.tamework.commands.showSpawnBeacons.description");
        this.visualizationService = visualizationService;
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.showSpawnBeacons.unable.to.resolve.your.player.uuid"));
            return;
        }

        TameworkShowSpawnMarkersCommandSupport.ParseResult parse =
                TameworkShowSpawnMarkersCommandSupport.parse(commandContext.getInputString());
        if (parse.mode() == TameworkShowSpawnMarkersCommandSupport.Mode.INVALID) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.showSpawnBeacons.usage.tw.showspawnbeacons.radius.off"));
            return;
        }
        if (parse.mode() == TameworkShowSpawnMarkersCommandSupport.Mode.OFF) {
            SpawnBeaconVisualizationService.DisableResult result =
                    visualizationService.disable(playerUuid, world, store);
            commandContext.sender().sendMessage(result.wasActive() ? Message.translation("server.tamework.commands.showSpawnBeacons.spawn.beacon.visualization.disabled") : Message.translation("server.tamework.commands.showSpawnBeacons.spawn.beacon.visualization.was.not.active"));
            return;
        }

        SpawnBeaconVisualizationService.EnableResult result =
                visualizationService.enable(world, store, playerRef, parse.radius());
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.showSpawnBeacons.spawn.beacon.visualization.enabled.within.blocks.showing").param("0", String.valueOf(formatNumber(result.radius()))).param("1", String.valueOf(result.visibleCount())).param("2", String.valueOf((result.visibleCount() == 1 ? "" : "s"))).param("3", String.valueOf((result.skippedCount() == 0
                        ? "."
                        : "; skipped " + result.skippedCount() + " without usable visuals."))));
        sendSummaries(commandContext, result.summaries(), result.visibleCount());
    }

    private static void sendSummaries(@Nonnull CommandContext commandContext,
                                      @Nonnull List<SpawnBeaconVisualizationService.BeaconSummary> summaries,
                                      int visibleCount) {
        for (SpawnBeaconVisualizationService.BeaconSummary summary : summaries) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.showSpawnBeacons.at").param("0", String.valueOf(displayConfigId(summary.configId()))).param("1", String.valueOf(formatPosition(summary.position()))));
        }
        if (visibleCount > summaries.size()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.showSpawnBeacons.and.more.loaded.beacon.s").param("0", String.valueOf((visibleCount - summaries.size()))));
        }
    }

    private static String displayConfigId(String configId) {
        return configId == null || configId.isBlank() ? "<unknown>" : configId;
    }

    private static String formatPosition(@Nonnull Vector3d position) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", position.x, position.y, position.z);
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
