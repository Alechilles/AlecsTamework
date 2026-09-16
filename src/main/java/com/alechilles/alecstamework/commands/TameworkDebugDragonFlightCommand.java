package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.avatarflight.AvatarFlightActivator;
import com.alechilles.alecstamework.avatarflight.AvatarFlightClientFlightProbe;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountLifecycleService;
import com.alechilles.alecstamework.debug.PlayerInputDebugProbe;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Enables the transformed-player dragon flight prototype for runtime testing.
 */
public final class TameworkDebugDragonFlightCommand extends AbstractPlayerCommand {
    private static final AvatarFlightActivator ACTIVATOR = new AvatarFlightActivator();
    private static final AvatarFlightMountLifecycleService MOUNT_LIFECYCLE =
            new AvatarFlightMountLifecycleService();

    public TameworkDebugDragonFlightCommand() {
        super("dragon-flight", "server.tamework.commands.debugDragonFlight.description");
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.player.uuid.is.not.available"));
            return;
        }
        String[] args = getArgs(commandContext.getInputString());
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        if ("inputprobe".equals(action) || "inputlog".equals(action)) {
            String probeAction = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
            sendInputProbeResult(commandContext, playerUuid, probeAction);
            return;
        }
        if ("flightprobe".equals(action) || "clientflight".equals(action)) {
            String probeAction = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
            sendClientFlightProbeResult(commandContext, store, ref, playerUuid, probeAction);
            return;
        }
        if ("status".equals(action)) {
            sendStatus(commandContext, store, ref, playerUuid);
            return;
        }
        if (isOff(action)) {
            if (MOUNT_LIFECYCLE.hasMountSession(store, ref)) {
                sendMountResult(commandContext, MOUNT_LIFECYCLE.end(
                        store, ref, playerUuid, AvatarFlightMountLifecycleService.EndReason.COMMAND));
            } else {
                sendResult(commandContext, ACTIVATOR.disable(store, ref, playerUuid));
            }
            return;
        }
        if ("toggle".equals(action)) {
            if (ACTIVATOR.status(store, ref, playerUuid).active()) {
                if (MOUNT_LIFECYCLE.hasMountSession(store, ref)) {
                    sendMountResult(commandContext, MOUNT_LIFECYCLE.end(
                            store, ref, playerUuid, AvatarFlightMountLifecycleService.EndReason.COMMAND));
                } else {
                    sendResult(commandContext, ACTIVATOR.disable(store, ref, playerUuid));
                }
            } else {
                String configId = args.length > 1 ? args[1] : null;
                sendResult(commandContext, ACTIVATOR.enable(store, ref, playerUuid, configId));
            }
            return;
        }
        if ("on".equals(action) || "enable".equals(action) || "start".equals(action)) {
            String configId = args.length > 1 ? args[1] : null;
            sendResult(commandContext, ACTIVATOR.enable(store, ref, playerUuid, configId));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.usage.tw.debugdragonflight.on.configid.off.toggle"));
    }

    private static void sendStatus(@Nonnull CommandContext commandContext,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull UUID playerUuid) {
        AvatarFlightActivator.Status status = ACTIVATOR.status(store, ref, playerUuid);
        long inputAge = status.lastInputAtMs() == 0L ? -1L : System.currentTimeMillis() - status.lastInputAtMs();
        commandContext.sender().sendMessage(Message.join(Message.translation("server.tamework.commands.debugDragonFlight.statusHeading"), Message.raw(String.format(
                " active=%s config=%s mode=%s velocity=%.2f/%.2f/%.2f inputAgeMs=%s savedModel=%s flightProbe=%s inputLog=%s",
                status.active(),
                status.configId().isBlank() ? "<default>" : status.configId(),
                status.mode(),
                status.velocityX(),
                status.velocityY(),
                status.velocityZ(),
                inputAge < 0L ? "<none>" : Long.toString(inputAge),
                status.savedModelId() == null ? "<none>" : status.savedModelId(),
                AvatarFlightClientFlightProbe.isActive(playerUuid),
                PlayerInputDebugProbe.isEnabled(playerUuid)
        ))));
    }

    private static void sendClientFlightProbeResult(@Nonnull CommandContext commandContext,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nonnull Ref<EntityStore> ref,
                                                    @Nonnull UUID playerUuid,
                                                    @Nonnull String action) {
        if ("status".equals(action)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.client.flight.probe.active.inputlog").param("0", String.valueOf(AvatarFlightClientFlightProbe.isActive(playerUuid))).param("1", String.valueOf(PlayerInputDebugProbe.isEnabled(playerUuid))));
            return;
        }
        if (isOff(action)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.disable(store, ref, playerUuid);
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.join(localizedResult(result.message()), Message.raw(" "), Message.translation("server.tamework.commands.debugDragonFlight.input.probe.disabled")));
            return;
        }
        if ("toggle".equals(action) && AvatarFlightClientFlightProbe.isActive(playerUuid)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.disable(store, ref, playerUuid);
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.join(localizedResult(result.message()), Message.raw(" "), Message.translation("server.tamework.commands.debugDragonFlight.input.probe.disabled")));
            return;
        }
        if ("toggle".equals(action) || "on".equals(action) || "enable".equals(action) || "start".equals(action)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.enable(store, ref, playerUuid);
            if (result.ok()) {
                PlayerInputDebugProbe.enable(playerUuid);
            }
            commandContext.sender().sendMessage(localizedResult(result.message()));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.usage.tw.debugdragonflight.flightprobe.on.off.toggle"));
    }

    private static void sendInputProbeResult(@Nonnull CommandContext commandContext,
                                             @Nonnull UUID playerUuid,
                                             @Nonnull String action) {
        if ("status".equals(action)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.input.probe.active").param("0", String.valueOf(PlayerInputDebugProbe.isEnabled(playerUuid))));
            return;
        }
        if (isOff(action)) {
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.input.probe.disabled"));
            return;
        }
        if ("toggle".equals(action) && PlayerInputDebugProbe.isEnabled(playerUuid)) {
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.input.probe.disabled"));
            return;
        }
        if ("toggle".equals(action) || "on".equals(action) || "enable".equals(action) || "start".equals(action)) {
            PlayerInputDebugProbe.enable(playerUuid);
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.input.probe.enabled"));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.debugDragonFlight.usage.tw.debugdragonflight.inputprobe.on.off.toggle"));
    }

    private static void sendResult(@Nonnull CommandContext commandContext,
                                   @Nonnull AvatarFlightActivator.Result result) {
        commandContext.sender().sendMessage(localizedResult(result.message()));
    }

    private static void sendMountResult(@Nonnull CommandContext commandContext,
                                        @Nonnull AvatarFlightMountLifecycleService.Result result) {
        commandContext.sender().sendMessage(localizedResult(result.message()));
    }

    /** Translates the legacy debug service result without changing its diagnostic contract. */
    private static Message localizedResult(String text) {
        String key = switch (text) {
            case "Avatar flight component types are not registered." -> "tamework.commands.debugDragonFlight.result.avatar.flight.component.types.are.not.registered";
            case "Avatar flight is already active." -> "tamework.commands.debugDragonFlight.result.avatar.flight.is.already.active";
            case "Hold Flightmaster's Talisman before starting avatar flight." -> "tamework.commands.debugDragonFlight.result.hold.flightmaster.s.talisman.before.starting.avatar.flight";
            case "Avatar flight disabled, but no saved model or skin fallback was available." -> "tamework.commands.debugDragonFlight.result.avatar.flight.disabled.but.no.saved.model.or.skin";
            case "Client flight probe failed: MovementManager is unavailable." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.failed.movementmanager.is.unavailable";
            case "Client flight probe failed: MovementStatesComponent is unavailable." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.failed.movementstatescomponent.is.unavailable";
            case "Client flight probe failed: Player packet handler is unavailable." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.failed.player.packet.handler.is.unavailable";
            case "Client flight probe enabled and input logging enabled." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.enabled.and.input.logging.enabled";
            case "Client flight probe was not active." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.was.not.active";
            case "Client flight probe disabled and movement settings restored." -> "tamework.commands.debugDragonFlight.result.client.flight.probe.disabled.and.movement.settings.restored";
            case "Avatar-flight mount rejected: source_snapshot_failed" -> "tamework.commands.debugDragonFlight.result.avatar.flight.mount.rejected.source.snapshot.failed";
            case "Avatar-flight mount rejected: session_snapshot_failed" -> "tamework.commands.debugDragonFlight.result.avatar.flight.mount.rejected.session.snapshot.failed";
            case "Avatar-flight mount rejected: component_type_unavailable" -> "tamework.commands.debugDragonFlight.result.avatar.flight.mount.rejected.component.type.unavailable";
            case "Avatar-flight mount failed while parking source NPC." -> "tamework.commands.debugDragonFlight.result.avatar.flight.mount.failed.while.parking.source.npc";
            case "Avatar-flight mount cleanup already in progress." -> "tamework.commands.debugDragonFlight.result.avatar.flight.mount.cleanup.already.in.progress";
            case "Avatar flight disabled." -> "tamework.commands.debugDragonFlight.result.avatar.flight.disabled";
            case "Avatar flight disabled and model restored." -> "tamework.commands.debugDragonFlight.result.avatar.flight.disabled.and.model.restored";
            default -> null;
        };
        if (key != null) {
            return Message.translation("server." + key);
        }
        if (text.startsWith("Avatar flight config is disabled: ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.configDisabled").param("0", text.substring(34));
        }
        if (text.startsWith("Avatar flight model asset not found: ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.modelMissing").param("0", text.substring(37));
        }
        if (text.startsWith("Avatar flight enabled with ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.enabled").param("0", text.substring(27));
        }
        if (text.startsWith("Avatar flight mounted with ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.mounted").param("0", text.substring(27));
        }
        if (text.startsWith("Avatar-flight mount rejected: ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.mountRejected").param("0", text.substring(30));
        }
        if (text.startsWith("Avatar-flight mount restored; player flight state was already absent. ")) {
            return Message.translation("server.tamework.commands.debugDragonFlight.result.mountRestored")
                    .param("0", localizedResult(text.substring(70)));
        }
        return Message.translation("server.tamework.commands.debugDragonFlight.result.unavailable");
    }

    private static boolean isOff(@Nonnull String value) {
        return "off".equals(value) || "disable".equals(value) || "stop".equals(value)
                || "reset".equals(value) || "restore".equals(value);
    }

    @Nonnull
    static String[] getArgs(String input) {
        return TameworkCommandInput.argumentsAfter(input, "dragon-flight");
    }
}
