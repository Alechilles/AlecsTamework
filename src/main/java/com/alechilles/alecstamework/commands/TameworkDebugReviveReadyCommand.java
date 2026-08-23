package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.revival.ReviveReadyRequest;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Makes one generic dead companion available for revival without bypassing persistence. */
public final class TameworkDebugReviveReadyCommand
        extends AbstractTameworkServerCommand {
    private final PublicPersistenceOperations operations;
    private final LongSupplier clock;

    public TameworkDebugReviveReadyCommand(
            @Nullable PublicPersistenceOperations operations
    ) {
        this(operations, System::currentTimeMillis);
    }

    TameworkDebugReviveReadyCommand(
            @Nullable PublicPersistenceOperations operations,
            @Nonnull LongSupplier clock
    ) {
        super("reviveready", "Make one dead generic companion ready to revive.");
        if (clock == null) {
            throw new IllegalArgumentException("Revive-ready clock is required");
        }
        this.operations = operations;
        this.clock = clock;
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        if (operations == null) {
            send(context, "Generic persistence is not available.");
            return;
        }
        ProfileId profileId = profileId(context);
        if (profileId == null) {
            send(context, "Usage: /tw debug persistence reviveready <profile UUID>");
            return;
        }
        long requestedAtMs = clock.getAsLong();
        OperationId operationId = OperationId.create();
        var submitted = operations.markReviveReady(
                operationId,
                new IdempotencyKey("debug-revive-ready:" + operationId),
                new ReviveReadyRequest(profileId, requestedAtMs)
        );
        if (!submitted.accepted()) {
            send(context, "Revive-ready request was not accepted.");
            return;
        }
        send(context, "Revive-ready request accepted for companion "
                + profileId + ".");
    }

    @Nullable
    private ProfileId profileId(@Nonnull CommandContext context) {
        String raw = TameworkCommandInput.firstArgument(
                context.getInputString(), "reviveready"
        );
        if (raw == null) {
            return null;
        }
        try {
            return ProfileId.parse(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void send(@Nonnull CommandContext context, @Nonnull String message) {
        context.sender().sendMessage(Message.raw(message));
    }
}
