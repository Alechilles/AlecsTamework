package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.runtime.CompanionPersistenceModule;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Operator commands for the companion store: {@code /tw persistence start-fresh [confirm]}
 * (spec 12.3). While old saves that this version cannot convert block the world, it creates a new
 * empty store and asks for a server restart. Without {@code confirm} it only previews. It never
 * changes the old files and does not turn companion features on in the running server.
 */
public final class TameworkPersistenceCommandGroup extends AbstractCommandCollection {
    private static final String PREFIX = "server.tamework.commands.persistence.";

    /**
     * @param startFresh creates the empty store; null when the world is not in migration-required
     *                   mode, and the command then answers that there is nothing to do
     */
    public TameworkPersistenceCommandGroup(@Nullable Supplier<CompanionPersistenceModule.FreshStart> startFresh) {
        super("persistence", PREFIX + "description");
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        addSubCommand(new StartFresh(startFresh));
    }

    /** Touches only files and messages, so it runs off the world threads and works from the console. */
    private static final class StartFresh extends AbstractTameworkServerCommand {
        @Nullable private final Supplier<CompanionPersistenceModule.FreshStart> startFresh;

        StartFresh(@Nullable Supplier<CompanionPersistenceModule.FreshStart> startFresh) {
            super("start-fresh", PREFIX + "startFresh.description");
            requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
            setAllowsExtraArguments(true);
            this.startFresh = startFresh;
        }

        @Override
        protected void executeServer(@Nonnull CommandContext context) {
            String[] args = TameworkCommandInput.argumentsAfter(context.getInputString(), "start-fresh");
            if (context.getInputString().contains("--") || args.length > 1
                    || args.length == 1 && !"confirm".equalsIgnoreCase(args[0])) {
                context.sendMessage(Message.translation(PREFIX + "startFresh.usage"));
                return;
            }
            if (startFresh == null) {
                context.sendMessage(Message.translation(PREFIX + "startFresh.nothingToDo"));
                return;
            }
            if (args.length == 0) {
                context.sendMessage(Message.translation(PREFIX + "startFresh.preview"));
                return;
            }
            String key = switch (startFresh.get()) {
                case CREATED -> "startFresh.done";
                // The store was already created since this server started; only the restart is left.
                case NOT_NEEDED -> "startFresh.restartPending";
                case FAILED -> "startFresh.failed";
            };
            context.sendMessage(Message.translation(PREFIX + key));
        }
    }
}
