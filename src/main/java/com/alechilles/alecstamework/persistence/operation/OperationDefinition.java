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

    /**
     * Declares whether one narrowly identified UNKNOWN outcome may re-enter
     * its exact live resolver during automatic recovery.
     *
     * <p>The default remains manual review. Implementations may opt in only
     * when the live boundary is idempotent, positively verifies the frozen
     * request, and can commit directly from UNKNOWN without guessing.</p>
     */
    default boolean allowsUnknownLiveReverification(
            @Nonnull OperationEnvelope operation
    ) {
        return false;
    }
}
