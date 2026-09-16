package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteDatabaseCompactionResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compacts retained persistence history and reclaims SQLite file space. */
public final class TameworkDebugCompactDatabaseCommand
        extends AbstractTameworkServerCommand {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    @Nullable
    private final PublicPersistenceOperations operations;

    public TameworkDebugCompactDatabaseCommand(
            @Nullable PublicPersistenceOperations operations
    ) {
        super(
                "compact",
                "server.tamework.commands.debugCompactDatabase.description"
        );
        requirePermission(TameworkCommandRoot.ROOT_PERMISSION);
        setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        this.operations = operations;
    }

    @Override
    protected void executeServer(@Nonnull CommandContext context) {
        CommandSender sender = context.sender();
        if (operations == null) {
            send(sender, Message.translation(
                    "server.tamework.commands.debugCompactDatabase.persistence.runtime.is.not.available"
            ));
            return;
        }

        final CompletionStage<SqliteDatabaseCompactionResult> stage;
        try {
            stage = operations.compactDatabase();
        } catch (RuntimeException failure) {
            finish(sender, null, failure);
            return;
        }
        if (stage == null) {
            finish(sender, null, new IllegalStateException(
                    "database_maintenance_returned_null"
            ));
            return;
        }

        final CompletableFuture<SqliteDatabaseCompactionResult> future;
        try {
            future = stage.toCompletableFuture();
        } catch (RuntimeException failure) {
            finish(sender, null, failure);
            return;
        }
        // Avoid claiming that maintenance started for an already-rejected request.
        if (!future.isCompletedExceptionally()) {
            send(sender, Message.translation(
                    "server.tamework.commands.debugCompactDatabase.maintenance.started"
            ));
        }
        future.whenComplete((result, failure) -> finish(sender, result, failure));
    }

    private void finish(
            @Nonnull CommandSender sender,
            @Nullable SqliteDatabaseCompactionResult result,
            @Nullable Throwable failure
    ) {
        if (failure != null || result == null) {
            Throwable cause = failure == null
                    ? new IllegalStateException("database_maintenance_returned_null")
                    : failure;
            LOGGER.at(Level.WARNING)
                    .withCause(cause)
                    .log("Database maintenance command failed.");
            send(sender, Message.translation(
                    "server.tamework.commands.debugCompactDatabase.maintenance.failed.see.server.log"
            ));
            return;
        }
        send(sender, Message.translation(
                "server.tamework.commands.debugCompactDatabase.maintenance.completed"
        ).param("0", formatMiB(result.bytesBefore()))
                .param("1", formatMiB(result.bytesAfter()))
                .param("2", String.valueOf(result.compactedOperations())));
    }

    private String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) BYTES_PER_MIB);
    }

    private void send(@Nonnull CommandSender sender, @Nonnull Message message) {
        sender.sendMessage(message);
    }
}
