package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Toggles spawner debug logging on the server.
 */
public final class TameworkDebugSpawnerCommand extends AbstractPlayerCommand {

    public TameworkDebugSpawnerCommand() {
        super("debugspawner", "Toggle Tamework spawner debug logging.");
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
        String raw = getFirstArg(commandContext);
        Boolean explicit = parseBoolean(raw);
        boolean enabled = explicit != null
                ? plugin.setDebugSpawnerEnabled(explicit)
                : plugin.toggleDebugSpawnerEnabled();
        commandContext.sender().sendMessage(Message.raw(
                "Tamework spawner debug logging: " + (enabled ? "enabled" : "disabled")
        ));
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if ("on".equals(value) || "true".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("off".equals(value) || "false".equals(value) || "0".equals(value)) {
            return false;
        }
        return null;
    }
}
