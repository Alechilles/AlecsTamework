package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Console-safe command base for short Tamework operations that do not require a
 * player entity or a world-thread handoff.
 */
abstract class AbstractTameworkServerCommand extends AbstractAsyncCommand {
    protected AbstractTameworkServerCommand(@Nonnull String name, @Nonnull String description) {
        super(name, description);
    }

    @Override
    @Nonnull
    protected final CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        executeServer(context);
        return CompletableFuture.completedFuture(null);
    }

    protected abstract void executeServer(@Nonnull CommandContext context);
}
