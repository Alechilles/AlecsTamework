package com.alechilles.alecstamework.companion.snapshot;

import javax.annotation.Nonnull;

/** One immutable codec for one snapshot payload version. */
public interface SnapshotCodec<T> {
    int version();

    @Nonnull
    Class<T> valueType();

    @Nonnull
    String encode(@Nonnull T value) throws Exception;

    @Nonnull
    T decode(@Nonnull String payloadJson) throws Exception;
}
