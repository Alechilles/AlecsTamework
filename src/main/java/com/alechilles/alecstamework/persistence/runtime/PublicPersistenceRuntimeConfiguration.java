package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Complete dependencies and bounded timings for one replacement runtime. */
public record PublicPersistenceRuntimeConfiguration(
        @Nonnull Path dataDirectory,
        @Nonnull String workerId,
        @Nonnull LongSupplier clock,
        @Nonnull RefundDeliveryBoundary refunds,
        @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
        @Nonnull PublicPersistenceLiveBoundaries liveBoundaries,
        @Nonnull PublicPersistenceWorldReconciliation worldReconciliation,
        @Nonnull Duration shutdownTimeout
) {
    public PublicPersistenceRuntimeConfiguration {
        if (dataDirectory == null || workerId == null || workerId.isBlank()
                || clock == null || refunds == null || profileListener == null
                || liveBoundaries == null || worldReconciliation == null
                || shutdownTimeout == null || shutdownTimeout.isNegative()
                || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "Complete public persistence runtime configuration is required"
            );
        }
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
        workerId = workerId.trim();
    }
}
