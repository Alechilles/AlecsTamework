package com.alechilles.alecstamework.persistence.projection;

import javax.annotation.Nonnull;

/** Outside-transaction probe used to compare canonical rebuild and projected state. */
public interface ProjectionRebuildProbe<T> {
    @Nonnull
    T rebuildCanonical() throws Exception;

    @Nonnull
    T readProjection() throws Exception;

    boolean equivalent(@Nonnull T canonical, @Nonnull T projected);
}
