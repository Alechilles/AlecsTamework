package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCheckpointResult;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteKernelShutdownReport;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import javax.annotation.Nonnull;

/**
 * Always-available, payload-free operational state for replacement storage.
 *
 * <p>This snapshot remains available before database opening, during deferred
 * world evidence, after startup failure, and after shutdown. Durable row-level
 * health is intentionally kept in {@link PublicPersistenceDiagnosticsSnapshot}
 * and joined only when its isolated reader is available.</p>
 */
public record PublicPersistenceOperationalStatus(
        @Nonnull PersistenceEngineMode engine,
        @Nonnull Path dataDirectory,
        @Nonnull Optional<Path> databasePath,
        @Nonnull Optional<PublicPersistenceTarget.Origin> targetOrigin,
        @Nonnull OptionalInt schemaVersion,
        @Nonnull StorageMode storageMode,
        @Nonnull PersistenceStartupReport startup,
        @Nonnull Map<PersistenceStartupNode, NodeState> startupNodes,
        @Nonnull CheckpointEvidence lastCheckpoint,
        @Nonnull List<String> guidance
) {
    public PublicPersistenceOperationalStatus {
        if (engine == null || dataDirectory == null || databasePath == null
                || targetOrigin == null || schemaVersion == null
                || storageMode == null || startup == null
                || startupNodes == null
                || !startupNodes.keySet().equals(
                java.util.Set.of(PersistenceStartupNode.values())
        ) || lastCheckpoint == null || guidance == null
                || guidance.isEmpty()
                || guidance.stream().anyMatch(
                value -> value == null || value.isBlank()
        )) {
            throw new IllegalArgumentException(
                    "Complete persistence operational status is required"
            );
        }
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
        databasePath = databasePath.map(
                path -> path.toAbsolutePath().normalize()
        );
        startupNodes = Map.copyOf(startupNodes);
        guidance = List.copyOf(guidance);
    }

    /** Process-level write posture, distinct from bounded feature circuits. */
    public enum StorageMode {
        STARTING,
        READ_WRITE,
        READ_ONLY,
        DRAINING,
        CLOSED
    }

    /** Exact state of one node in the single startup DAG. */
    public enum NodeState {
        PENDING,
        RUNNING,
        DEFERRED,
        COMPLETED,
        FAILED,
        BLOCKED
    }

    /** Last ordered WAL checkpoint evidence, if shutdown reached that step. */
    public record CheckpointEvidence(
            @Nonnull Status status,
            int logFrames,
            int checkpointedFrames,
            String failureCode
    ) {
        public CheckpointEvidence {
            if (status == null || logFrames < 0 || checkpointedFrames < 0
                    || (status == Status.COMPLETED
                    && (failureCode != null
                    || checkpointedFrames > logFrames))
                    || (status == Status.FAILED
                    && (failureCode == null || failureCode.isBlank()))
                    || (status != Status.FAILED && failureCode != null)) {
                throw new IllegalArgumentException(
                        "Consistent checkpoint evidence is required"
                );
            }
            failureCode = normalize(failureCode);
        }

        public enum Status {
            NOT_ATTEMPTED,
            COMPLETED,
            FAILED,
            SKIPPED_WRITER_ACTIVE
        }

        static CheckpointEvidence notAttempted() {
            return new CheckpointEvidence(
                    Status.NOT_ATTEMPTED, 0, 0, null
            );
        }

        static CheckpointEvidence from(
                SqliteKernelShutdownReport kernel
        ) {
            if (kernel == null) {
                return notAttempted();
            }
            var checkpoint = kernel.checkpoint();
            if (checkpoint.status()
                    == SqliteKernelShutdownReport.CheckpointStatus
                    .SKIPPED_WRITER_ACTIVE) {
                return new CheckpointEvidence(
                        Status.SKIPPED_WRITER_ACTIVE, 0, 0, null
                );
            }
            if (checkpoint.result() instanceof
                    SqliteCheckpointResult.Completed completed) {
                return new CheckpointEvidence(
                        Status.COMPLETED,
                        completed.logFrames(),
                        completed.checkpointedFrames(),
                        null
                );
            }
            SqliteCheckpointResult.Failed failed =
                    (SqliteCheckpointResult.Failed) checkpoint.result();
            return new CheckpointEvidence(
                    Status.FAILED,
                    0,
                    0,
                    failed.failure().code()
            );
        }
    }

    static Map<PersistenceStartupNode, NodeState> nodeStates(
            PersistenceStartupReport report
    ) {
        EnumMap<PersistenceStartupNode, NodeState> states =
                new EnumMap<>(PersistenceStartupNode.class);
        boolean blocked = false;
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            NodeState state;
            if (report.completedNodes().contains(node)) {
                state = NodeState.COMPLETED;
            } else if (node == report.runningNode()) {
                state = NodeState.RUNNING;
            } else if (node == report.deferredNode()) {
                state = NodeState.DEFERRED;
            } else if (node == report.failedNode()) {
                state = NodeState.FAILED;
                blocked = true;
            } else {
                state = blocked ? NodeState.BLOCKED : NodeState.PENDING;
            }
            states.put(node, state);
        }
        return Map.copyOf(states);
    }

    static StorageMode storageMode(
            PersistenceStartupReport report,
            boolean shutdownStarted,
            boolean shutdownTerminal
    ) {
        if (shutdownTerminal) {
            return StorageMode.CLOSED;
        }
        if (shutdownStarted) {
            return StorageMode.DRAINING;
        }
        if (report.readiness()
                == PersistenceReadinessLevel.GLOBAL_READ_ONLY) {
            return StorageMode.READ_ONLY;
        }
        if (report.readiness()
                == PersistenceReadinessLevel.MUTATION_READY) {
            return StorageMode.READ_WRITE;
        }
        return StorageMode.STARTING;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
