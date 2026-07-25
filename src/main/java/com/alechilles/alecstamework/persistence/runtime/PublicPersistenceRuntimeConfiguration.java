package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicApiEventSink;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Complete dependencies and bounded timings for one replacement runtime. */
public record PublicPersistenceRuntimeConfiguration(
        @Nonnull Path dataDirectory,
        @Nonnull List<Path> persistenceSourceDirectories,
        @Nonnull String workerId,
        @Nonnull LongSupplier clock,
        @Nonnull RefundDeliveryBoundary refunds,
        @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
        @Nonnull ReplacementPublicApiEventSink publicEventSink,
        @Nonnull PublicPersistenceLiveBoundaries liveBoundaries,
        @Nonnull PublicPersistenceWorldReconciliationFactory worldReconciliationFactory,
        @Nonnull Duration shutdownTimeout
) {
    public PublicPersistenceRuntimeConfiguration {
        if (dataDirectory == null || persistenceSourceDirectories == null
                || persistenceSourceDirectories.stream()
                .anyMatch(path -> path == null)
                || workerId == null || workerId.isBlank()
                || clock == null || refunds == null || profileListener == null
                || publicEventSink == null
                || liveBoundaries == null || worldReconciliationFactory == null
                || shutdownTimeout == null || shutdownTimeout.isNegative()
                || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "Complete public persistence runtime configuration is required"
            );
        }
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
        LinkedHashSet<Path> normalizedSources = new LinkedHashSet<>();
        normalizedSources.add(dataDirectory);
        persistenceSourceDirectories.forEach(path ->
                normalizedSources.add(path.toAbsolutePath().normalize()));
        persistenceSourceDirectories = List.copyOf(normalizedSources);
        workerId = workerId.trim();
    }

    /**
     * Compatibility constructor with no checkpointed public event destination.
     */
    public PublicPersistenceRuntimeConfiguration(
            Path dataDirectory,
            List<Path> persistenceSourceDirectories,
            String workerId,
            LongSupplier clock,
            RefundDeliveryBoundary refunds,
            Consumer<NpcProfileChangedEvent> profileListener,
            PublicPersistenceLiveBoundaries liveBoundaries,
            PublicPersistenceWorldReconciliationFactory
                    worldReconciliationFactory,
            Duration shutdownTimeout
    ) {
        this(
                dataDirectory,
                persistenceSourceDirectories,
                workerId,
                clock,
                refunds,
                profileListener,
                ReplacementPublicApiEventSink.NO_OP,
                liveBoundaries,
                worldReconciliationFactory,
                shutdownTimeout
        );
    }

    /**
     * Compatibility constructor for a target whose only source candidate is
     * the target directory itself.
     */
    public PublicPersistenceRuntimeConfiguration(
            Path dataDirectory,
            String workerId,
            LongSupplier clock,
            RefundDeliveryBoundary refunds,
            Consumer<NpcProfileChangedEvent> profileListener,
            PublicPersistenceLiveBoundaries liveBoundaries,
            PublicPersistenceWorldReconciliationFactory
                    worldReconciliationFactory,
            Duration shutdownTimeout
    ) {
        this(
                dataDirectory,
                List.of(dataDirectory),
                workerId,
                clock,
                refunds,
                profileListener,
                ReplacementPublicApiEventSink.NO_OP,
                liveBoundaries,
                worldReconciliationFactory,
                shutdownTimeout
        );
    }

    /**
     * Compatibility constructor for focused tests whose reconciliation participant
     * has no dependency on the replacement facades.
     */
    public PublicPersistenceRuntimeConfiguration(
            Path dataDirectory,
            String workerId,
            LongSupplier clock,
            RefundDeliveryBoundary refunds,
            Consumer<NpcProfileChangedEvent> profileListener,
            PublicPersistenceLiveBoundaries liveBoundaries,
            PublicPersistenceWorldReconciliation worldReconciliation,
            Duration shutdownTimeout
    ) {
        this(
                dataDirectory,
                List.of(dataDirectory),
                workerId,
                clock,
                refunds,
                profileListener,
                ReplacementPublicApiEventSink.NO_OP,
                liveBoundaries,
                PublicPersistenceWorldReconciliationFactory.fixed(
                        worldReconciliation
                ),
                shutdownTimeout
        );
    }

    /**
     * Focused constructor for an explicit immutable source search path and a
     * fixed reconciliation participant.
     */
    public PublicPersistenceRuntimeConfiguration(
            Path dataDirectory,
            List<Path> persistenceSourceDirectories,
            String workerId,
            LongSupplier clock,
            RefundDeliveryBoundary refunds,
            Consumer<NpcProfileChangedEvent> profileListener,
            PublicPersistenceLiveBoundaries liveBoundaries,
            PublicPersistenceWorldReconciliation worldReconciliation,
            Duration shutdownTimeout
    ) {
        this(
                dataDirectory,
                persistenceSourceDirectories,
                workerId,
                clock,
                refunds,
                profileListener,
                ReplacementPublicApiEventSink.NO_OP,
                liveBoundaries,
                PublicPersistenceWorldReconciliationFactory.fixed(
                        worldReconciliation
                ),
                shutdownTimeout
        );
    }
}
