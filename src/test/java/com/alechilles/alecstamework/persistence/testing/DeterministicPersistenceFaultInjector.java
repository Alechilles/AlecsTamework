package com.alechilles.alecstamework.persistence.testing;

import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Deterministic, instance-local test injector keyed by stable checkpoint and test IDs. */
public final class DeterministicPersistenceFaultInjector implements PersistenceCheckpointHook {
    private final Map<PersistenceCheckpoint, ArmedFault> armed =
            new EnumMap<>(PersistenceCheckpoint.class);

    public synchronized DeterministicPersistenceFaultInjector arm(
            @Nonnull String testId,
            @Nonnull PersistenceCheckpoint checkpoint,
            @Nonnull FaultMode mode,
            int failures) {
        if (testId.isBlank() || failures <= 0) throw new IllegalArgumentException("testId/failures");
        armed.put(checkpoint, new ArmedFault(testId.trim(), mode, new AtomicInteger(failures)));
        return this;
    }

    @Override
    public void hit(@Nonnull PersistenceCheckpoint checkpoint,
                    @Nullable PersistenceOperationMetadata metadata) throws Exception {
        ArmedFault fault;
        synchronized (this) {
            fault = armed.get(checkpoint);
            if (fault == null || fault.remaining().get() <= 0) return;
            if (fault.remaining().decrementAndGet() == 0) armed.remove(checkpoint);
        }
        throw failure(fault, checkpoint, metadata);
    }

    private Exception failure(ArmedFault fault,
                              PersistenceCheckpoint checkpoint,
                              PersistenceOperationMetadata metadata) {
        String operation = metadata == null ? "none" : metadata.taskName();
        String message = "injected:" + fault.testId() + ":" + checkpoint.name()
                + ":" + operation;
        return switch (fault.mode()) {
            case SQLITE_BUSY_LOCKED -> new SQLException("database is locked; " + message, "SQLITE_BUSY", 5);
            case IO_CONNECTION_FAILURE -> new SQLException(message, "08006");
            case TRANSACTION_ROLLBACK, EXCEPTION_COMMIT_KNOWN_ABSENT,
                    EXCEPTION_COMMIT_KNOWN_PRESENT, EXCEPTION_COMMIT_OUTCOME_UNKNOWN,
                    PROCESS_TERMINATION, RUNTIME_PUBLICATION_EXCEPTION,
                    DIAGNOSTICS_TELEMETRY_EXCEPTION, DELAYED_OR_MISSING_EVIDENCE,
                    CONTRADICTORY_LIVE_PROJECTION, FAILED_INTEGRITY_RESULT,
                    DETERMINISTIC_DOMAIN_REJECTION -> new InjectedPersistenceFaultException(
                            fault.testId(), checkpoint, fault.mode(), message);
            case IO_FILE_FAILURE -> new IOException(message);
        };
    }

    public enum FaultMode {
        DETERMINISTIC_DOMAIN_REJECTION,
        SQLITE_BUSY_LOCKED,
        IO_CONNECTION_FAILURE,
        IO_FILE_FAILURE,
        TRANSACTION_ROLLBACK,
        EXCEPTION_COMMIT_KNOWN_ABSENT,
        EXCEPTION_COMMIT_KNOWN_PRESENT,
        EXCEPTION_COMMIT_OUTCOME_UNKNOWN,
        PROCESS_TERMINATION,
        RUNTIME_PUBLICATION_EXCEPTION,
        DIAGNOSTICS_TELEMETRY_EXCEPTION,
        DELAYED_OR_MISSING_EVIDENCE,
        CONTRADICTORY_LIVE_PROJECTION,
        FAILED_INTEGRITY_RESULT
    }

    public static final class InjectedPersistenceFaultException extends Exception {
        private final String testId;
        private final PersistenceCheckpoint checkpoint;
        private final FaultMode mode;

        private InjectedPersistenceFaultException(String testId,
                                                  PersistenceCheckpoint checkpoint,
                                                  FaultMode mode,
                                                  String message) {
            super(message);
            this.testId = Objects.requireNonNull(testId, "testId");
            this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        public String testId() {
            return testId;
        }

        public PersistenceCheckpoint checkpoint() {
            return checkpoint;
        }

        public FaultMode mode() {
            return mode;
        }
    }

    private record ArmedFault(@Nonnull String testId,
                              @Nonnull FaultMode mode,
                              @Nonnull AtomicInteger remaining) {
    }
}
