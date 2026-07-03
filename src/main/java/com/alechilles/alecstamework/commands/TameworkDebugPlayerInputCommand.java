package com.alechilles.alecstamework.commands;

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
 * Toggles verbose input diagnostics for the executing player.
 */
public final class TameworkDebugPlayerInputCommand extends AbstractPlayerCommand {
    public TameworkDebugPlayerInputCommand() {
        super("debugplayerinput", "Toggle player input diagnostics for mount-avatar experiments.");
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

        String arg = getFirstArg(commandContext);
        if ("status".equals(arg)) {
            commandContext.sender().sendMessage(Message.raw(
                    "Tamework player input diagnostics: "
                            + (PlayerInputDebugProbe.isEnabled(playerUuid) ? "enabled" : "disabled")
                            + " for you; total enabled=" + PlayerInputDebugProbe.enabledCount()
            ));
            return;
        }

        boolean enabled;
        if ("on".equals(arg) || "true".equals(arg) || "1".equals(arg)) {
            PlayerInputDebugProbe.enable(playerUuid);
            enabled = true;
        } else if ("off".equals(arg) || "false".equals(arg) || "0".equals(arg)) {
            PlayerInputDebugProbe.disable(playerUuid);
            enabled = false;
        } else if (PlayerInputDebugProbe.isEnabled(playerUuid)) {
            PlayerInputDebugProbe.disable(playerUuid);
            enabled = false;
        } else {
            PlayerInputDebugProbe.enable(playerUuid);
            enabled = true;
        }

        commandContext.sender().sendMessage(Message.raw(
                "Tamework player input diagnostics: " + (enabled ? "enabled" : "disabled")
        ));
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return "";
        }
        String[] tokens = input.trim().split("\\s+");
        return tokens.length < 3 ? "" : tokens[2].toLowerCase(Locale.ROOT);
    }
}
