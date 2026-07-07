package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.avatarflight.AvatarFlightActivator;
import com.alechilles.alecstamework.avatarflight.AvatarFlightClientFlightProbe;
import com.alechilles.alecstamework.debug.PlayerInputDebugProbe;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Enables the transformed-player dragon flight prototype for runtime testing.
 */
public final class TameworkDebugDragonFlightCommand extends AbstractPlayerCommand {
    private static final AvatarFlightActivator ACTIVATOR = new AvatarFlightActivator();

    public TameworkDebugDragonFlightCommand() {
        super("debugdragonflight", "Toggle transformed-player dragon flight testing.");
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
            commandContext.sender().sendMessage(Message.raw("Player UUID is not available."));
            return;
        }
        String[] args = getArgs(commandContext);
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
            sendResult(commandContext, ACTIVATOR.disable(store, ref, playerUuid));
            return;
        }
        if ("toggle".equals(action)) {
            if (ACTIVATOR.status(store, ref, playerUuid).active()) {
                sendResult(commandContext, ACTIVATOR.disable(store, ref, playerUuid));
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
        commandContext.sender().sendMessage(Message.raw(
                "Usage: /tw debugdragonflight [on [configId] | off | toggle | status | inputprobe [on|off|toggle|status] | flightprobe [on|off|toggle|status]]"
        ));
    }

    private static void sendStatus(@Nonnull CommandContext commandContext,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull UUID playerUuid) {
        AvatarFlightActivator.Status status = ACTIVATOR.status(store, ref, playerUuid);
        long inputAge = status.lastInputAtMs() == 0L ? -1L : System.currentTimeMillis() - status.lastInputAtMs();
        commandContext.sender().sendMessage(Message.raw(String.format(
                "Avatar flight: active=%s config=%s mode=%s velocity=%.2f/%.2f/%.2f inputAgeMs=%s savedModel=%s flightProbe=%s inputLog=%s",
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
        )));
    }

    private static void sendClientFlightProbeResult(@Nonnull CommandContext commandContext,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nonnull Ref<EntityStore> ref,
                                                    @Nonnull UUID playerUuid,
                                                    @Nonnull String action) {
        if ("status".equals(action)) {
            commandContext.sender().sendMessage(Message.raw(
                    "Client flight probe: active=" + AvatarFlightClientFlightProbe.isActive(playerUuid)
                            + " inputLog=" + PlayerInputDebugProbe.isEnabled(playerUuid)
            ));
            return;
        }
        if (isOff(action)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.disable(store, ref, playerUuid);
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.raw(result.message() + " Input logging disabled."));
            return;
        }
        if ("toggle".equals(action) && AvatarFlightClientFlightProbe.isActive(playerUuid)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.disable(store, ref, playerUuid);
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.raw(result.message() + " Input logging disabled."));
            return;
        }
        if ("toggle".equals(action) || "on".equals(action) || "enable".equals(action) || "start".equals(action)) {
            AvatarFlightClientFlightProbe.Result result = AvatarFlightClientFlightProbe.enable(store, ref, playerUuid);
            if (result.ok()) {
                PlayerInputDebugProbe.enable(playerUuid);
            }
            commandContext.sender().sendMessage(Message.raw(result.message()));
            return;
        }
        commandContext.sender().sendMessage(Message.raw(
                "Usage: /tw debugdragonflight flightprobe [on|off|toggle|status]"
        ));
    }

    private static void sendInputProbeResult(@Nonnull CommandContext commandContext,
                                             @Nonnull UUID playerUuid,
                                             @Nonnull String action) {
        if ("status".equals(action)) {
            commandContext.sender().sendMessage(Message.raw(
                    "Input probe: active=" + PlayerInputDebugProbe.isEnabled(playerUuid)
            ));
            return;
        }
        if (isOff(action)) {
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.raw("Input probe disabled."));
            return;
        }
        if ("toggle".equals(action) && PlayerInputDebugProbe.isEnabled(playerUuid)) {
            PlayerInputDebugProbe.disable(playerUuid);
            commandContext.sender().sendMessage(Message.raw("Input probe disabled."));
            return;
        }
        if ("toggle".equals(action) || "on".equals(action) || "enable".equals(action) || "start".equals(action)) {
            PlayerInputDebugProbe.enable(playerUuid);
            commandContext.sender().sendMessage(Message.raw("Input probe enabled."));
            return;
        }
        commandContext.sender().sendMessage(Message.raw(
                "Usage: /tw debugdragonflight inputprobe [on|off|toggle|status]"
        ));
    }

    private static void sendResult(@Nonnull CommandContext commandContext,
                                   @Nonnull AvatarFlightActivator.Result result) {
        commandContext.sender().sendMessage(Message.raw(result.message()));
    }

    private static boolean isOff(@Nonnull String value) {
        return "off".equals(value) || "disable".equals(value) || "stop".equals(value)
                || "reset".equals(value) || "restore".equals(value);
    }

    @Nonnull
    private static String[] getArgs(@Nonnull CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        return tokens.length <= 2 ? new String[0] : Arrays.copyOfRange(tokens, 2, tokens.length);
    }
}
