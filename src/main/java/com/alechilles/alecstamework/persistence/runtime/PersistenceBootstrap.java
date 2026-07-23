package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Repository-free composition and lifecycle boundary for replacement storage.
 *
 * <p>The bootstrap is the only object the plugin needs to start, inspect, and
 * stop persistence. Gameplay receives the focused domain facade bundle only
 * after canonical startup has completed.</p>
 */
public final class PersistenceBootstrap implements AutoCloseable {
    private final PublicPersistenceRuntime runtime;

    public PersistenceBootstrap(
            @Nonnull PublicPersistenceRuntimeConfiguration configuration
    ) {
        runtime = new PublicPersistenceRuntime(configuration);
    }

    /** Starts or resumes the descriptor-derived readiness graph. */
    @Nonnull
    public CompletionStage<PersistenceStartupReport> start() {
        return runtime.start();
    }

    /** Returns the current graph-derived startup evidence. */
    @Nonnull
    public PersistenceStartupReport report() {
        return runtime.report();
    }

    /** Returns graph-derived readiness for one registered feature. */
    @Nonnull
    public PersistenceReadinessLevel readiness(
            @Nonnull PersistenceFeatureId featureId
    ) {
        return runtime.readiness(featureId);
    }

    /** Returns gameplay capabilities without exposing storage machinery. */
    @Nonnull
    public PersistenceDomainFacades facades() {
        return new PersistenceDomainFacades(
                runtime.operations(),
                runtime.queries()
        );
    }

    /** Returns the selected database after target opening completes. */
    @Nonnull
    public Optional<Path> databasePath() {
        return runtime.databasePath();
    }

    /** Returns whether the target was reused, created, or imported. */
    @Nonnull
    public Optional<PublicPersistenceTarget.Origin> targetOrigin() {
        return runtime.targetOrigin();
    }

    /** Returns passive bounded runtime metrics. */
    @Nonnull
    public PublicPersistenceMetricsSnapshot metrics() {
        return runtime.metrics();
    }

    /** Returns sanitized operational evidence after projections are ready. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<
            PublicPersistenceDiagnosticsSnapshot>> diagnostics() {
        return runtime.diagnostics();
    }

    /** Executes or resumes the ordered shutdown protocol. */
    @Nonnull
    public PublicPersistenceShutdownReport shutdown(
            @Nonnull Duration timeout
    ) {
        return runtime.shutdown(timeout);
    }

    @Override
    public void close() {
        runtime.close();
    }
}
