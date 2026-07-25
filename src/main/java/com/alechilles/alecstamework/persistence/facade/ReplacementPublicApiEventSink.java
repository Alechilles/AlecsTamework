package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.TameworkEvent;
import javax.annotation.Nonnull;

/**
 * Narrow destination for already-mapped, checkpointed public persistence
 * events. Implementations must not acknowledge durable projection work based
 * on downstream listener success.
 */
@FunctionalInterface
public interface ReplacementPublicApiEventSink {
    void publish(@Nonnull TameworkEvent event);
}
