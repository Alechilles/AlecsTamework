package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;

/**
 * Registered operation kind plus the exact codec for one positive payload version.
 *
 * @param <T> immutable operation payload type
 */
public interface OperationDefinition<T> {
    @Nonnull
    OperationKind kind();

    int payloadVersion();

    @Nonnull
    Class<T> payloadType();

    @Nonnull
    String encode(@Nonnull T payload) throws Exception;

    @Nonnull
    T decode(@Nonnull String payloadJson) throws Exception;
}
