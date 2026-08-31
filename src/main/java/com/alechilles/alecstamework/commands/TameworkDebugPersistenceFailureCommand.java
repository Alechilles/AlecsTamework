package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.runtime.PersistenceFailureSignal;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Sends one harmless synthetic failure through the persistence diagnostic path.
 */
public final class TameworkDebugPersistenceFailureCommand
        extends AbstractTameworkServerCommand {

    @Nullable private final Consumer<PersistenceFailureSignal> failureSink;

    public TameworkDebugPersistenceFailureCommand(
            @Nullable Consumer<PersistenceFailureSignal> failureSink
    ) {
        super(
                "simulateerror",
                "Send a synthetic persistence diagnostic without changing data."
        );
        this.failureSink = failureSink;
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        if (failureSink == null) {
            context.sender().sendMessage(Message.raw(
                    "Persistence diagnostics are not initialized."
            ));
            return;
        }

        String token = Long.toUnsignedString(System.nanoTime(), 36);
        try {
            failureSink.accept(new PersistenceFailureSignal(
                    "persistence_debug_simulated_failure",
                    "debug:" + token,
                    "debug_simulation",
                    "debug_command",
                    "simulated_" + token,
                    new IllegalStateException("Synthetic persistence diagnostic")
            ));
            context.sender().sendMessage(Message.raw(
                    "Synthetic persistence diagnostic requested (token=" + token
                            + "). No persistence data was changed."
            ));
        } catch (RuntimeException failure) {
            context.sender().sendMessage(Message.raw(
                    "Synthetic persistence diagnostic could not be requested."
            ));
        }
    }
}
