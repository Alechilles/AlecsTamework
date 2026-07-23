package com.alechilles.alecstamework.persistence.operation;

import javax.annotation.Nonnull;

/** Registered definition and immutable payload decoded for recovery dispatch. */
public record DecodedOperationPayload(@Nonnull OperationDefinition<?> definition,
                                      @Nonnull Object payload) {
    public DecodedOperationPayload {
        if (definition == null || payload == null
                || !definition.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Decoded operation definition and payload must match");
        }
    }
}
