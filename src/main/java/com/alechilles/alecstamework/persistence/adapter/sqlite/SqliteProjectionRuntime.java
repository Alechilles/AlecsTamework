package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionPublicationScheduler;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Owns the shared projection coordinator and its publication scheduler. */
record SqliteProjectionRuntime(
        ProjectionCoordinator coordinator,
        ProjectionPublicationScheduler publicationScheduler
) {
    static SqliteProjectionRuntime create(
            SqlitePersistenceKernel kernel,
            LongSupplier clock,
            PersistenceThroughputMetrics throughputMetrics
    ) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(throughputMetrics, "throughputMetrics");
        ProjectionCoordinator coordinator = new ProjectionCoordinator(
                new SqliteProjectionGateway(
                        kernel.reads(), kernel.units(), throughputMetrics
                ),
                ProjectionRetryPolicy.DEFAULT,
                clock
        );
        return new SqliteProjectionRuntime(
                coordinator,
                new ProjectionPublicationScheduler(
                        coordinator, throughputMetrics
                )
        );
    }
}
