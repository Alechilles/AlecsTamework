package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Read-only operator view of the replacement persistence runtime.
 *
 * <p>This boundary exposes only bounded status, metrics, and sanitized detail
 * reads. Lifecycle controls and gameplay mutation authorities remain owned by
 * the persistence composition root.</p>
 */
public final class PersistenceDiagnosticsReader {
    private final PublicPersistenceRuntime runtime;

    PersistenceDiagnosticsReader(
            @Nonnull PublicPersistenceRuntime runtime
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Returns payload-free operator state in every runtime phase. */
    @Nonnull
    public PublicPersistenceOperationalStatus status() {
        return runtime.operationalStatus();
    }

    /** Returns passive bounded counters for registered features. */
    @Nonnull
    public PublicPersistenceMetricsSnapshot metrics() {
        return runtime.metrics();
    }

    /** Returns sanitized durable detail when its isolated reader is ready. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<
            PublicPersistenceDiagnosticsSnapshot>> details() {
        return runtime.diagnostics();
    }
}
