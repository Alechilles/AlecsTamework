package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Translates terminal persistence outcomes into isolated diagnostic signals.
 */
final class PersistenceFailureEmitter {
    private final Consumer<PersistenceFailureSignal> sink;

    PersistenceFailureEmitter(Consumer<PersistenceFailureSignal> sink) {
        this.sink = sink == null ? ignored -> { } : sink;
    }

    void write(
            OperationId operationId,
            OperationKind operationKind,
            PersistenceTransactionResult<?> result
    ) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> failed) {
            emit("persistence_write_failed", operationId.toString(),
                    operationKind.toString(), "final_write",
                    failed.failure().code(), failed.failure().cause());
        } else if (result instanceof PersistenceTransactionResult.Unknown<?> failed) {
            emit("persistence_write_failed", operationId.toString(),
                    operationKind.toString(), "final_write",
                    failed.failure().code(), failed.failure().cause());
        }
    }

    void read(
            PersistenceReadKind readKind,
            PersistenceReadResult.Failed<?> failed
    ) {
        emit("persistence_read_failed",
                "read:" + readKind + ":" + failed.failure().code(),
                readKind.toString(), "read", failed.failure().code(),
                failed.failure().cause());
    }

    void checkpoint(String checkpoint, Throwable failure) {
        String normalized = checkpoint == null || checkpoint.isBlank()
                ? "unknown"
                : checkpoint.trim().toLowerCase(Locale.ROOT);
        emit("persistence_checkpoint_failed",
                "checkpoint:" + normalized + ":" + exceptionClass(failure),
                normalized, "checkpoint", "checkpoint_failure", failure);
    }

    void shutdownTimeout(int outstandingOperations) {
        emit("persistence_shutdown_timeout",
                "shutdown_timeout:" + Math.max(0, outstandingOperations),
                "shutdown", "shutdown", "outstanding_operations", null);
    }

    void startup(PersistenceStartupNode node, Throwable failure) {
        emit("persistence_startup_failed",
                "startup:" + node.name() + ":" + exceptionClass(failure),
                node.name(), "startup", "startup_action_failed", failure);
    }

    private void emit(
            String eventName,
            String incidentKey,
            String operation,
            String phase,
            String reason,
            Throwable failure
    ) {
        try {
            sink.accept(new PersistenceFailureSignal(
                    eventName, incidentKey, operation, phase, reason, failure
            ));
        } catch (RuntimeException ignored) {
            // Diagnostics must never alter persistence control flow.
        }
    }

    private static String exceptionClass(Throwable failure) {
        return failure == null ? "unknown" : failure.getClass().getName();
    }
}
