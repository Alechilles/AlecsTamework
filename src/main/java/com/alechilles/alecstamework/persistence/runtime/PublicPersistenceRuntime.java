package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Single composition root and lifecycle authority for released public
 * persistence behavior.
 */
public final class PublicPersistenceRuntime implements AutoCloseable {
    private final PublicPersistenceRuntimeConfiguration configuration;
    private final PublicPersistenceRuntimeState state;
    private final PersistenceStartupCoordinator startup;

    public PublicPersistenceRuntime(
            @Nonnull PublicPersistenceRuntimeConfiguration configuration
    ) {
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "Public persistence runtime configuration is required"
            );
        }
        this.configuration = configuration;
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        state = new PublicPersistenceRuntimeState(
                configuration,
                registry,
                new PublicPersistenceWorkflowTracker()
        );
        startup = new PersistenceStartupCoordinator(
                registry,
                state.actions()
        );
        state.bind(startup);
    }

    /** Starts or resumes the one dependency graph. */
    @Nonnull
    public CompletionStage<PersistenceStartupReport> start() {
        return startup.advance();
    }

    /** Returns the current graph-derived readiness evidence. */
    @Nonnull
    public PersistenceStartupReport report() {
        return startup.report();
    }

    /** Returns graph-derived readiness for one registered feature. */
    @Nonnull
    public PersistenceReadinessLevel readiness(
            @Nonnull PersistenceFeatureId featureId
    ) {
        return startup.readiness(featureId);
    }

    /** Returns the selected database only after OPEN_TARGET completes. */
    @Nonnull
    public Optional<Path> databasePath() {
        return state.databasePath();
    }

    /** Returns whether startup reused, created, or imported the target. */
    @Nonnull
    public Optional<PublicPersistenceTarget.Origin> targetOrigin() {
        return state.targetOrigin();
    }

    /** Returns the one typed mutation facade after the target is open. */
    @Nonnull
    public PublicPersistenceOperations operations() {
        return state.requireOperations();
    }

    /**
     * Returns typed reads after canonical startup state has been validated.
     * Reads remain available if a later bounded startup step fails closed.
     */
    @Nonnull
    public PublicPersistenceQueries queries() {
        if (!startup.report().completedNodes().contains(
                com.alechilles.alecstamework.persistence.control
                        .PersistenceStartupNode.LOAD_CANONICAL
        )) {
            throw new IllegalStateException(
                    "public_persistence_canonical_reads_not_ready"
            );
        }
        return state.requireQueries();
    }

    /** Executes or resumes the ordered, bounded shutdown protocol. */
    @Nonnull
    public PublicPersistenceShutdownReport shutdown(
            @Nonnull Duration timeout
    ) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Runtime shutdown timeout is required and non-negative"
            );
        }
        return state.shutdown(timeout);
    }

    @Override
    public void close() {
        shutdown(configuration.shutdownTimeout());
    }
}
